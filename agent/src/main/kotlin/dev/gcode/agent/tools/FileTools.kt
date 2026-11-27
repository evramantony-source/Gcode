package dev.gcode.agent.tools

import dev.gcode.agent.ToolEffect
import dev.gcode.agent.workspace.WorkspaceFs
import dev.gcode.core.common.Glob
import dev.gcode.git.TextDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Shared services for the file tools. */
class FileToolDeps(
    val fs: () -> WorkspaceFs?,
    val guard: () -> SecretGuard,
    val checkpoints: CheckpointStore,
) {
    fun requireFs(): WorkspaceFs = fs() ?: throw ToolArgumentException("No workspace is open. Ask the user to open a repository or a folder first.")
}

private const val MAX_READ_BYTES = 2_000_000
private const val DEFAULT_LINES = 2_000

private fun isBinary(bytes: ByteArray): Boolean = bytes.take(8_000).any { it == 0.toByte() }

private suspend fun readTextOrNull(fs: WorkspaceFs, path: String): String? =
    if (fs.exists(path) && !fs.isDirectory(path)) fs.readText(path) else null

/** Base for tools whose effect never depends on arguments. */
abstract class FixedEffectTool(override val staticEffect: ToolEffect) : Tool, StaticEffect {
    override suspend fun effect(args: JsonObject): ToolEffect = staticEffect
}

class ReadFileTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "read_file"
    override val description = "Read a text file from the workspace. Returns numbered lines. Use offset/limit for large files."
    override val parameters = schema {
        string("path", "File path relative to the workspace root", required = true)
        integer("offset", "First line to return (1-based)")
        integer("limit", "Maximum number of lines (default $DEFAULT_LINES)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.requireStr("path"))
        val reason = d.guard().reason(path)
        val approval = reason?.let { ApprovalRequest.SecretAccess(name, "Send $path to the model?", path, it) }
        return Prepared(approval) {
            if (!fs.exists(path)) return@Prepared ToolResult.error("$path does not exist")
            if (fs.isDirectory(path)) return@Prepared ToolResult.error("$path is a directory; use list_dir")
            val size = fs.size(path)
            if (size > MAX_READ_BYTES) return@Prepared ToolResult.error("$path is too large ($size bytes); use grep to find what you need")
            val bytes = fs.readBytes(path)
            if (isBinary(bytes)) return@Prepared ToolResult.error("$path looks like a binary file ($size bytes)")
            val lines = bytes.toString(Charsets.UTF_8).lines()
            val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
            val limit = (args.int("limit") ?: DEFAULT_LINES).coerceIn(1, 20_000)
            val slice = lines.drop(offset - 1).take(limit)
            val numbered = slice.mapIndexed { i, l -> "${offset + i}\t$l" }.joinToString("\n")
            ToolResult(
                true,
                ToolResult.jsonOf(
                    listOf(
                        "path" to path, "content" to numbered, "total_lines" to lines.size,
                        "has_more" to (offset - 1 + slice.size < lines.size),
                    ),
                ),
                "Read $path (${slice.size} of ${lines.size} lines)",
            )
        }
    }
}

/** Applies a change to one file after approval; shared by write_file, edit_file and multi_edit. */
private suspend fun prepareChange(
    tool: String,
    d: FileToolDeps,
    path: String,
    before: String?,
    after: String,
    title: String,
): Prepared {
    val fs = d.requireFs()
    if (path.isEmpty()) throw ToolArgumentException("A file path is required")
    if (path == ".git" || path.startsWith(".git/")) throw ToolArgumentException("Writing inside .git is not allowed")
    val diff = TextDiff.unified(path, before, after)
    val protectedReason = d.guard().reason(path)
    val approval = if (protectedReason != null) {
        ApprovalRequest.SecretAccess(tool, "Modify protected file $path?", path, protectedReason)
    } else {
        ApprovalRequest.FileChange(tool, title, path, before, after, diff)
    }
    return Prepared(approval) { answer ->
        val content = (answer as? ApprovalResult.ApprovedWithEdit)?.content ?: after
        d.checkpoints.record(path, before?.toByteArray(Charsets.UTF_8))
        fs.writeText(path, content)
        val finalDiff = if (content == after) diff else TextDiff.unified(path, before, content)
        val (added, removed) = TextDiff.stats(finalDiff)
        val edited = answer is ApprovalResult.ApprovedWithEdit
        ToolResult(
            true,
            ToolResult.jsonOf(
                listOf(
                    "result" to (if (before == null) "Created $path" else "Updated $path") + " (+$added -$removed)",
                    "user_modified" to if (edited) "The user edited your change before accepting. Final diff:\n$finalDiff" else null,
                ),
            ),
            "${if (before == null) "Created" else "Edited"} $path (+$added -$removed)",
            display = finalDiff,
        )
    }
}

class WriteFileTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.WORKSPACE_WRITE) {
    override val name = "write_file"
    override val description = "Create a file or replace its entire contents. Prefer edit_file for small changes to existing files."
    override val parameters = schema {
        string("path", "File path relative to the workspace root", required = true)
        string("content", "The complete new file contents", required = true)
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.requireStr("path"))
        if (fs.exists(path) && fs.isDirectory(path)) throw ToolArgumentException("$path is a directory")
        val before = readTextOrNull(fs, path)
        return prepareChange(name, d, path, before, args.requireStrAllowEmpty("content"), if (before == null) "Create $path" else "Overwrite $path")
    }
}

/** Exact string replacement; the old text must be unique unless replace_all is set. */
object Replacer {
    fun apply(text: String, old: String, new: String, replaceAll: Boolean): String {
        if (old.isEmpty()) throw ToolArgumentException("old_string is empty; use write_file to create files")
        if (old == new) throw ToolArgumentException("old_string and new_string are identical")
        val count = countOccurrences(text, old)
        return when {
            count == 0 -> throw ToolArgumentException("old_string was not found. Read the file again and copy the text exactly, including whitespace.")
            count > 1 && !replaceAll -> throw ToolArgumentException("old_string occurs $count times. Add surrounding lines to make it unique, or set replace_all.")
            replaceAll -> text.replace(old, new)
            else -> text.replaceFirst(old, new)
        }
    }

    fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}

class EditFileTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.WORKSPACE_WRITE) {
    override val name = "edit_file"
    override val description = "Replace exact text in a file. old_string must match exactly once (including indentation) unless replace_all is true."
    override val parameters = schema {
        string("path", "File path relative to the workspace root", required = true)
        string("old_string", "Exact text to replace", required = true)
        string("new_string", "Replacement text", required = true)
        boolean("replace_all", "Replace every occurrence")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.requireStr("path"))
        val before = readTextOrNull(fs, path) ?: throw ToolArgumentException("$path does not exist; use write_file to create it")
        val after = Replacer.apply(before, args.requireStrAllowEmpty("old_string"), args.requireStrAllowEmpty("new_string"), args.bool("replace_all") == true)
        return prepareChange(name, d, path, before, after, "Edit $path")
    }
}

class MultiEditTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.WORKSPACE_WRITE) {
    override val name = "multi_edit"
    override val description = "Apply several exact replacements to one file, in order. All edits succeed or none are applied."
    override val parameters = schema {
        string("path", "File path relative to the workspace root", required = true)
        objectArray("edits", "Replacements applied in order", required = true) {
            string("old_string", "Exact text to replace", required = true)
            string("new_string", "Replacement text", required = true)
            boolean("replace_all", "Replace every occurrence")
        }
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.requireStr("path"))
        val before = readTextOrNull(fs, path) ?: throw ToolArgumentException("$path does not exist")
        val edits = (args["edits"] as? JsonArray)?.map { it.jsonObject } ?: throw ToolArgumentException("edits must be an array")
        if (edits.isEmpty()) throw ToolArgumentException("edits is empty")
        var text = before
        edits.forEachIndexed { i, e ->
            text = try {
                Replacer.apply(text, e.requireStrAllowEmpty("old_string"), e.requireStrAllowEmpty("new_string"), e.bool("replace_all") == true)
            } catch (ex: ToolArgumentException) {
                throw ToolArgumentException("Edit ${i + 1}: ${ex.message}. No edits were applied.")
            }
        }
        return prepareChange(name, d, path, before, text, "Edit $path (${edits.size} changes)")
    }
}

class ListDirTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "list_dir"
    override val description = "List files and folders. depth 1 lists one level; up to 4 levels."
    override val parameters = schema {
        string("path", "Directory relative to the workspace root (default: root)")
        integer("depth", "How many levels to list (1-4, default 2)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.str("path").orEmpty())
        val depth = (args.int("depth") ?: 2).coerceIn(1, 4)
        if (path.isNotEmpty() && !fs.isDirectory(path)) return@Prepared ToolResult.error("$path is not a directory")
        val baseDepth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
        val entries = fs.walk(path, maxEntries = 3_000).filter { (it.path.count { c -> c == '/' } + 1 - baseDepth) <= depth }
        val guard = d.guard()
        val lines = entries.sortedBy { it.path }.take(MAX_LIST).map { e ->
            val mark = if (guard.reason(e.path, e.isDirectory) != null) " (protected)" else ""
            if (e.isDirectory) "${e.path}/" else "${e.path}  ${e.size}b$mark"
        }
        ToolResult(
            true,
            ToolResult.jsonOf(listOf("path" to path.ifEmpty { "." }, "entries" to lines.joinToString("\n"), "count" to entries.size, "truncated" to (entries.size > MAX_LIST))),
            "Listed ${path.ifEmpty { "workspace root" }} (${entries.size} entries)",
        )
    }

    private companion object {
        const val MAX_LIST = 1_000
    }
}

class GlobTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "glob"
    override val description = "Find files by glob pattern, e.g. **/*.kt or app/src/**/Main*.kt. Newest first."
    override val parameters = schema {
        string("pattern", "Glob pattern", required = true)
        string("path", "Directory to search (default: root)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val fs = d.requireFs()
        val glob = Glob(args.requireStr("pattern"))
        val base = WorkspaceFs.normalize(args.str("path").orEmpty())
        val guard = d.guard()
        val all = fs.walk(base).filter { !it.isDirectory && glob.matches(if (base.isEmpty()) it.path else it.path.removePrefix("$base/")) }
        val (hidden, visible) = all.partition { guard.reason(it.path) != null }
        val list = visible.sortedByDescending { it.modified }.take(MAX_RESULTS).map { it.path }
        ToolResult(
            true,
            ToolResult.jsonOf(listOf("files" to list.joinToString("\n"), "count" to visible.size, "skipped_protected" to hidden.size.takeIf { it > 0 })),
            "Found ${visible.size} files",
        )
    }

    private companion object {
        const val MAX_RESULTS = 500
    }
}

class GrepTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "grep"
    override val description = "Search file contents with a regular expression. Returns path:line: text. Protected and binary files are skipped."
    override val parameters = schema {
        string("pattern", "Regular expression (Java syntax)", required = true)
        string("path", "Directory or file to search (default: root)")
        string("glob", "Only search files matching this glob, e.g. *.kt")
        boolean("case_insensitive", "Ignore case")
        integer("max_results", "Maximum matching lines (default 200)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val fs = d.requireFs()
        val regex = try {
            Regex(args.requireStr("pattern"), if (args.bool("case_insensitive") == true) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return@Prepared ToolResult.error("Invalid regular expression: ${e.message}")
        }
        val base = WorkspaceFs.normalize(args.str("path").orEmpty())
        val glob = args.str("glob")?.takeIf { it.isNotBlank() }?.let(::Glob)
        val max = (args.int("max_results") ?: 200).coerceIn(1, 2_000)
        val guard = d.guard()
        val files = if (base.isNotEmpty() && fs.exists(base) && !fs.isDirectory(base)) {
            listOf(dev.gcode.agent.workspace.WsEntry(base, base.substringAfterLast('/'), false, fs.size(base), 0))
        } else {
            fs.walk(base).filter { !it.isDirectory }
        }
        val matches = mutableListOf<String>()
        var skipped = 0
        var scanned = 0
        for (f in files) {
            if (matches.size >= max) break
            if (glob != null && !glob.matches(f.path)) continue
            if (guard.reason(f.path) != null) { skipped++; continue }
            if (f.size > 1_000_000) continue
            val bytes = fs.readBytes(f.path)
            if (isBinary(bytes)) continue
            scanned++
            bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                if (matches.size < max && regex.containsMatchIn(line)) matches += "${f.path}:${i + 1}: ${line.take(300)}"
            }
        }
        ToolResult(
            true,
            ToolResult.jsonOf(listOf("matches" to matches.joinToString("\n"), "count" to matches.size, "files_scanned" to scanned, "skipped_protected" to skipped.takeIf { it > 0 })),
            "${matches.size} matches in $scanned files",
        )
    }
}

class DeleteFileTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.WORKSPACE_WRITE) {
    override val name = "delete_file"
    override val description = "Delete a file or a folder (recursively). Changes can be rewound from the chat."
    override val parameters = schema { string("path", "Path relative to the workspace root", required = true) }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val path = WorkspaceFs.normalize(args.requireStr("path"))
        if (path.isEmpty() || path == ".git" || path.startsWith(".git/")) throw ToolArgumentException("Cannot delete $path")
        if (!fs.exists(path)) throw ToolArgumentException("$path does not exist")
        val files = if (fs.isDirectory(path)) fs.walk(path).filter { !it.isDirectory }.map { it.path } else listOf(path)
        return Prepared(ApprovalRequest.Action(name, "Delete $path", "${files.size} file(s) will be deleted:\n" + files.take(30).joinToString("\n"), dangerous = false)) {
            files.forEach { d.checkpoints.record(it, fs.readBytes(it)) }
            fs.delete(path)
            ToolResult.ok("Deleted $path (${files.size} files)")
        }
    }
}

class MoveFileTool(private val d: FileToolDeps) : FixedEffectTool(ToolEffect.WORKSPACE_WRITE) {
    override val name = "move_file"
    override val description = "Move or rename a file or folder."
    override val parameters = schema {
        string("from", "Current path", required = true)
        string("to", "New path", required = true)
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val fs = d.requireFs()
        val from = WorkspaceFs.normalize(args.requireStr("from"))
        val to = WorkspaceFs.normalize(args.requireStr("to"))
        if (!fs.exists(from)) throw ToolArgumentException("$from does not exist")
        if (fs.exists(to)) throw ToolArgumentException("$to already exists")
        return Prepared(ApprovalRequest.Action(name, "Move $from → $to", "Move $from to $to", dangerous = false)) {
            val files = if (fs.isDirectory(from)) fs.walk(from).filter { !it.isDirectory }.map { it.path } else listOf(from)
            files.forEach { src ->
                d.checkpoints.record(src, fs.readBytes(src))
                d.checkpoints.record(to + src.removePrefix(from), null)
            }
            fs.move(from, to)
            ToolResult.ok("Moved $from to $to")
        }
    }
}

class TodoWriteTool(private val store: TodoStore) : FixedEffectTool(ToolEffect.READ) {
    override val name = "todo_write"
    override val description = "Replace the task list shown to the user. Keep exactly one item in_progress while working; mark items done as you finish them."
    override val parameters = schema {
        objectArray("todos", "The full list, in order", required = true) {
            string("content", "What to do", required = true)
            string("status", "pending, in_progress or done", required = true, enum = TodoItem.STATUSES)
        }
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val items = (args["todos"] as? JsonArray)?.map { e ->
            val o = e.jsonObject
            TodoItem(o.requireStr("content"), o.str("status")?.takeIf { it in TodoItem.STATUSES } ?: TodoItem.PENDING)
        } ?: return@Prepared ToolResult.error("todos must be an array")
        store.set(items)
        val done = items.count { it.status == TodoItem.DONE }
        ToolResult(true, buildJsonObject { put("result", "Todo list updated ($done/${items.size} done)") }, "Todo list: $done/${items.size} done")
    }
}

class SubmitPlanTool : FixedEffectTool(ToolEffect.READ) {
    override val name = ToolRegistryExecutor.SUBMIT_PLAN
    override val description = "Plan mode only: present your plan (Markdown) for the user's approval. Do not change anything before it is approved."
    override val parameters = schema { string("plan", "The plan in Markdown: goal, steps, files to change, risks", required = true) }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val plan = args.requireStr("plan")
        return Prepared(ApprovalRequest.Plan(name, "Approve this plan?", plan)) { answer ->
            val mode = (answer as? ApprovalResult.PlanApproved)?.mode
            ToolResult(
                true,
                buildJsonObject { put("result", "The user approved the plan" + (mode?.let { " and switched to $it mode. Carry it out now." } ?: ".")) },
                "Plan approved",
            )
        }
    }
}

/** Helper for tests and tools: JSON array of strings. */
fun jsonStrings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
