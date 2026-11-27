package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.EditFileTool
import dev.gcode.agent.tools.FileToolDeps
import dev.gcode.agent.tools.GlobTool
import dev.gcode.agent.tools.GrepTool
import dev.gcode.agent.tools.MemoryCheckpointStore
import dev.gcode.agent.tools.MultiEditTool
import dev.gcode.agent.tools.ReadFileTool
import dev.gcode.agent.tools.Replacer
import dev.gcode.agent.tools.Rewinder
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.WriteFileTool
import dev.gcode.agent.workspace.LocalWorkspaceFs
import dev.gcode.agent.workspace.WorkspaceException
import dev.gcode.agent.workspace.WorkspaceFs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var fs: LocalWorkspaceFs
    private val checkpoints = MemoryCheckpointStore()
    private lateinit var deps: FileToolDeps
    private val ctx = ToolContext("c1")

    @Before
    fun setUp() {
        fs = LocalWorkspaceFs(tmp.newFolder("ws"))
        deps = FileToolDeps({ fs }, { SecretGuard() }, checkpoints)
        File(fs.rootDir, "src").mkdirs()
        File(fs.rootDir, "src/Main.kt").writeText("fun main() {\n    println(\"hi\")\n    println(\"hi\")\n}\n")
        File(fs.rootDir, ".env").writeText("TOKEN=secret\n")
        File(fs.rootDir, "build.gradle.kts").writeText("plugins {}\n")
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is String -> put(k, v); is Boolean -> put(k, v); is Int -> put(k, v); is JsonPrimitive -> put(k, v); else -> put(k, v as kotlinx.serialization.json.JsonElement) } }
    }

    @Test
    fun `paths cannot escape the workspace`() = runTest {
        assertThat(WorkspaceFs.normalize("/src/./a/../Main.kt")).isEqualTo("src/Main.kt")
        assertThrows(WorkspaceException::class.java) { WorkspaceFs.normalize("../outside") }
        val outside = tmp.newFile("outside.txt").apply { writeText("x") }
        java.nio.file.Files.createSymbolicLink(File(fs.rootDir, "link").toPath(), outside.toPath())
        assertThrows(WorkspaceException::class.java) { fs.file("link") }
    }

    @Test
    fun `read returns numbered lines and slices`() = runTest {
        val r = ReadFileTool(deps).prepare(args("path" to "src/Main.kt", "offset" to 2, "limit" to 1), ctx)
        assertThat(r.approval).isNull()
        val result = r.apply(ApprovalResult.Approved)
        assertThat((result.response["content"] as JsonPrimitive).content).isEqualTo("2\t    println(\"hi\")")
        assertThat((result.response["total_lines"] as JsonPrimitive).content).isEqualTo("5")
    }

    @Test
    fun `reading a denylisted file needs secret approval`() = runTest {
        val r = ReadFileTool(deps).prepare(args("path" to ".env"), ctx)
        assertThat(r.approval).isInstanceOf(ApprovalRequest.SecretAccess::class.java)
    }

    @Test
    fun `edit requires a unique match unless replace_all`() = runTest {
        val tool = EditFileTool(deps)
        val e = runCatching { tool.prepare(args("path" to "src/Main.kt", "old_string" to "println(\"hi\")", "new_string" to "x"), ctx) }.exceptionOrNull()
        assertThat(e).hasMessageThat().contains("occurs 2 times")
        val missing = runCatching { tool.prepare(args("path" to "src/Main.kt", "old_string" to "nope", "new_string" to "x"), ctx) }.exceptionOrNull()
        assertThat(missing).hasMessageThat().contains("not found")

        val prepared = tool.prepare(args("path" to "src/Main.kt", "old_string" to "println(\"hi\")", "new_string" to "println(\"bye\")", "replace_all" to true), ctx)
        val change = prepared.approval as ApprovalRequest.FileChange
        assertThat(change.diff).contains("+    println(\"bye\")")
        prepared.apply(ApprovalResult.Approved)
        assertThat(File(fs.rootDir, "src/Main.kt").readText()).doesNotContain("\"hi\"")
        assertThat(checkpoints.since(-1)).hasSize(1)
    }

    @Test
    fun `multi edit is all or nothing`() = runTest {
        val edits = buildJsonArray {
            add(buildJsonObject { put("old_string", "fun main()"); put("new_string", "fun start()") })
            add(buildJsonObject { put("old_string", "missing"); put("new_string", "x") })
        }
        val e = runCatching { MultiEditTool(deps).prepare(buildJsonObject { put("path", "src/Main.kt"); put("edits", edits) }, ctx) }.exceptionOrNull()
        assertThat(e).hasMessageThat().contains("Edit 2")
        assertThat(File(fs.rootDir, "src/Main.kt").readText()).contains("fun main()")
    }

    @Test
    fun `user edits in the diff sheet win and are reported`() = runTest {
        val prepared = WriteFileTool(deps).prepare(args("path" to "NOTES.md", "content" to "draft\n"), ctx)
        val result = prepared.apply(ApprovalResult.ApprovedWithEdit("final\n"))
        assertThat(File(fs.rootDir, "NOTES.md").readText()).isEqualTo("final\n")
        assertThat((result.response["user_modified"] as JsonPrimitive).content).contains("+final")
    }

    @Test
    fun `glob and grep skip protected files`() = runTest {
        val glob = GlobTool(deps).prepare(args("pattern" to "*"), ctx).apply(ApprovalResult.Approved)
        assertThat((glob.response["files"] as JsonPrimitive).content).doesNotContain(".env")
        val grep = GrepTool(deps).prepare(args("pattern" to "TOKEN|println"), ctx).apply(ApprovalResult.Approved)
        val text = (grep.response["matches"] as JsonPrimitive).content
        assertThat(text).contains("src/Main.kt:2:")
        assertThat(text).doesNotContain("secret")
        assertThat((grep.response["skipped_protected"] as JsonPrimitive).content).isEqualTo("1")
    }

    @Test
    fun `rewind restores edited, created and deleted files`() = runTest {
        WriteFileTool(deps).prepare(args("path" to "NEW.md", "content" to "new\n"), ctx).apply(ApprovalResult.Approved)
        EditFileTool(deps).prepare(args("path" to "build.gradle.kts", "old_string" to "plugins {}", "new_string" to "plugins { id(\"x\") }"), ctx).apply(ApprovalResult.Approved)
        val restored = Rewinder.restore(checkpoints, fs, afterSeq = -1)
        assertThat(restored).containsExactly("build.gradle.kts", "NEW.md")
        assertThat(File(fs.rootDir, "NEW.md").exists()).isFalse()
        assertThat(File(fs.rootDir, "build.gradle.kts").readText()).isEqualTo("plugins {}\n")
    }

    @Test
    fun `replacer rejects identical strings and empty old text`() {
        assertThrows(ToolArgumentException::class.java) { Replacer.apply("a", "a", "a", false) }
        assertThrows(ToolArgumentException::class.java) { Replacer.apply("a", "", "b", false) }
    }
}
