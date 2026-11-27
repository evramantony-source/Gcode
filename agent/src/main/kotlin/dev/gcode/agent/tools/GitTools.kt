package dev.gcode.agent.tools

import dev.gcode.agent.ToolEffect
import dev.gcode.git.Author
import dev.gcode.git.GitCredentials
import dev.gcode.git.JGitRepository
import dev.gcode.git.MergeOutcome
import kotlinx.serialization.json.JsonObject

/** Access to the open repository for the git tools. */
interface GitAccess {
    suspend fun <T> withRepo(block: suspend (JGitRepository) -> T): T
    suspend fun credentials(): GitCredentials?
    suspend fun author(): Author
    suspend fun defaultBranch(): String?
}

class GitToolDeps(val git: () -> GitAccess?) {
    fun require(): GitAccess = git() ?: throw ToolArgumentException("No Git repository is open. Open a cloned repository first.")
}

abstract class GitTool(protected val d: GitToolDeps, effect: ToolEffect) : FixedEffectTool(effect)

class GitStatusTool(d: GitToolDeps) : GitTool(d, ToolEffect.READ) {
    override val name = "git_status"
    override val description = "Show the current branch, ahead/behind counts and changed, staged and untracked files."
    override val parameters = schema { }
    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val s = d.require().withRepo { it.status() }
        val text = buildString {
            appendLine("branch: ${s.branch} upstream: ${s.upstream ?: "none"} ahead: ${s.ahead} behind: ${s.behind}")
            s.staged.forEach { appendLine("staged ${it.kind.name.lowercase()}: ${it.path}") }
            s.unstaged.forEach { appendLine("unstaged ${it.kind.name.lowercase()}: ${it.path}") }
            s.untracked.forEach { appendLine("untracked: $it") }
            s.conflicting.forEach { appendLine("conflict: $it") }
            if (s.isClean) appendLine("working tree clean")
        }
        ToolResult(true, ToolResult.jsonOf(listOf("status" to text.trim())), "On ${s.branch}: ${s.changedCount} changed files")
    }
}

class GitDiffTool(d: GitToolDeps) : GitTool(d, ToolEffect.READ) {
    override val name = "git_diff"
    override val description = "Unified diff of unstaged changes, or staged changes with staged=true. Optionally limited to paths."
    override val parameters = schema {
        boolean("staged", "Diff the index against HEAD instead of the working tree against the index")
        stringArray("paths", "Limit to these paths")
    }
    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val diff = d.require().withRepo { it.diff(staged = args.bool("staged") == true, paths = args.strings("paths")) }
        ToolResult(true, ToolResult.jsonOf(listOf("diff" to diff.ifEmpty { "(no changes)" })), if (diff.isEmpty()) "No changes" else "Diff: ${diff.lines().size} lines")
    }
}

class GitLogTool(d: GitToolDeps) : GitTool(d, ToolEffect.READ) {
    override val name = "git_log"
    override val description = "Recent commits (newest first). Optionally for one path, or show one commit's diff with sha."
    override val parameters = schema {
        integer("max_count", "Number of commits (default 20)")
        string("path", "Only commits touching this path")
        string("sha", "Show the diff of this commit instead")
    }
    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val access = d.require()
        args.str("sha")?.takeIf { it.isNotBlank() }?.let { sha ->
            val diff = access.withRepo { it.commitDiff(sha) }
            return@Prepared ToolResult(true, ToolResult.jsonOf(listOf("diff" to diff)), "Diff of ${sha.take(7)}")
        }
        val commits = access.withRepo { it.log((args.int("max_count") ?: 20).coerceIn(1, 200), args.str("path")) }
        val text = commits.joinToString("\n") { "${it.shortSha} ${it.authorName}: ${it.title}" }
        ToolResult(true, ToolResult.jsonOf(listOf("log" to text.ifEmpty { "(no commits)" })), "${commits.size} commits")
    }
}

class GitAddTool(d: GitToolDeps) : GitTool(d, ToolEffect.GIT_LOCAL) {
    override val name = "git_add"
    override val description = "Stage files for the next commit. Use paths [\".\"] to stage everything (including deletions)."
    override val parameters = schema { stringArray("paths", "Paths to stage", required = true) }
    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val paths = args.strings("paths").ifEmpty { throw ToolArgumentException("paths is required") }
        return Prepared(ApprovalRequest.Action(name, "Stage ${paths.size} path(s)", paths.joinToString("\n"), dangerous = false)) {
            val status = d.require().withRepo { it.add(paths); it.status() }
            ToolResult.ok("Staged. ${status.staged.size} file(s) staged now.")
        }
    }
}

class GitCommitTool(d: GitToolDeps) : GitTool(d, ToolEffect.GIT_LOCAL) {
    override val name = "git_commit"
    override val description = "Commit the staged changes with a message (first line: short summary)."
    override val parameters = schema { string("message", "Commit message", required = true) }
    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val access = d.require()
        val message = args.requireStr("message")
        val staged = access.withRepo { it.status().staged }
        if (staged.isEmpty()) throw ToolArgumentException("Nothing is staged. Use git_add first.")
        return Prepared(ApprovalRequest.Action(name, "Commit: ${message.lineSequence().first()}", staged.joinToString("\n") { "${it.kind.name.lowercase()} ${it.path}" }, dangerous = false)) {
            val author = access.author()
            val info = access.withRepo { it.commit(message, author) }
            ToolResult.ok("Committed ${info.shortSha}: ${info.title}", "sha" to info.sha)
        }
    }
}

class GitBranchTool(d: GitToolDeps) : Tool {
    private val deps = d
    override val name = "git_branch"
    override val description = "List, create or delete branches. Creating also checks out the new branch."
    override val parameters = schema {
        string("action", "list, create or delete", required = true, enum = listOf("list", "create", "delete"))
        string("name", "Branch name (create/delete)")
        string("start_point", "Create from this branch or commit (default: HEAD)")
        boolean("force", "Delete even if not merged")
    }

    override suspend fun effect(args: JsonObject): ToolEffect = when (args.str("action")) {
        "list" -> ToolEffect.READ
        "delete" -> ToolEffect.DANGEROUS
        else -> ToolEffect.GIT_LOCAL
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val access = deps.require()
        return when (val action = args.requireStr("action")) {
            "list" -> Prepared(null) {
                val branches = access.withRepo { it.branches() }
                ToolResult(true, ToolResult.jsonOf(listOf("branches" to branches.joinToString("\n") { (if (it.isCurrent) "* " else "  ") + it.name })), "${branches.size} branches")
            }
            "create" -> {
                val name = args.requireStr("name")
                Prepared(ApprovalRequest.Action(this.name, "Create branch $name", "From ${args.str("start_point") ?: "HEAD"}", dangerous = false)) {
                    access.withRepo { it.createBranch(name, args.str("start_point"), checkout = true) }
                    ToolResult.ok("Created and switched to $name")
                }
            }
            "delete" -> {
                val name = args.requireStr("name")
                Prepared(ApprovalRequest.Action(this.name, "Delete branch $name", "Delete the local branch $name" + if (args.bool("force") == true) " (even if not merged)" else "", dangerous = true)) {
                    access.withRepo { it.deleteBranch(name, args.bool("force") == true) }
                    ToolResult.ok("Deleted branch $name")
                }
            }
            else -> throw ToolArgumentException("Unknown action $action")
        }
    }
}

class GitCheckoutTool(d: GitToolDeps) : GitTool(d, ToolEffect.GIT_LOCAL) {
    override val name = "git_checkout"
    override val description = "Switch to a branch (a remote branch such as origin/x gets a local tracking branch)."
    override val parameters = schema { string("branch", "Branch to switch to", required = true) }
    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val branch = args.requireStr("branch")
        return Prepared(ApprovalRequest.Action(name, "Switch to $branch", "Check out $branch", dangerous = false)) {
            d.require().withRepo { it.checkout(branch) }
            ToolResult.ok("Switched to $branch")
        }
    }
}

class GitMergeTool(private val d: GitToolDeps) : Tool {
    override val name = "git_merge"
    override val description = "Merge a branch into the current branch. Conflicts are reported; resolve them, then git_add and git_commit."
    override val parameters = schema { string("branch", "Branch to merge in", required = true) }

    override suspend fun effect(args: JsonObject): ToolEffect {
        val access = d.git() ?: return ToolEffect.GIT_LOCAL
        val current = access.withRepo { it.currentBranch() }
        // Merging into the default branch is in the always-confirm set.
        return if (current != null && current == access.defaultBranch()) ToolEffect.DANGEROUS else ToolEffect.GIT_LOCAL
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val access = d.require()
        val branch = args.requireStr("branch")
        val current = access.withRepo { it.currentBranch() }
        return Prepared(ApprovalRequest.Action(name, "Merge $branch into $current", "Merge $branch into $current", dangerous = current == access.defaultBranch())) {
            when (val outcome = access.withRepo { it.merge(branch) }) {
                MergeOutcome.AlreadyUpToDate -> ToolResult.ok("Already up to date")
                is MergeOutcome.Merged -> ToolResult.ok(if (outcome.fastForward) "Fast-forwarded to $branch" else "Merged $branch")
                is MergeOutcome.Conflicts -> ToolResult(false, ToolResult.jsonOf(listOf("error" to "Merge conflicts", "conflicts" to outcome.paths.joinToString("\n"))), "Conflicts in ${outcome.paths.size} files")
                is MergeOutcome.Failed -> ToolResult.error("Merge failed: ${outcome.reason} ${outcome.paths.joinToString()}")
            }
        }
    }
}

class GitPullTool(d: GitToolDeps) : GitTool(d, ToolEffect.GIT_LOCAL) {
    override val name = "git_pull"
    override val description = "Fetch and integrate remote changes into the current branch."
    override val parameters = schema { boolean("rebase", "Rebase instead of merge") }
    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val access = d.require()
        return Prepared(ApprovalRequest.Action(name, "Pull", "Pull from origin" + if (args.bool("rebase") == true) " (rebase)" else "", dangerous = false)) {
            val creds = access.credentials()
            when (val outcome = access.withRepo { it.pull(creds, rebase = args.bool("rebase") == true) }) {
                MergeOutcome.AlreadyUpToDate -> ToolResult.ok("Already up to date")
                is MergeOutcome.Merged -> ToolResult.ok("Pulled remote changes")
                is MergeOutcome.Conflicts -> ToolResult(false, ToolResult.jsonOf(listOf("error" to "Pull stopped with conflicts", "conflicts" to outcome.paths.joinToString("\n"))), "Conflicts")
                is MergeOutcome.Failed -> ToolResult.error("Pull failed: ${outcome.reason}")
            }
        }
    }
}

class GitPushTool(private val d: GitToolDeps) : Tool {
    override val name = "git_push"
    override val description = "Push the current branch to origin. force=true overwrites remote history (with a lease) and always needs confirmation."
    override val parameters = schema {
        string("branch", "Branch to push (default: current)")
        boolean("force", "Force push with lease")
    }

    override suspend fun effect(args: JsonObject): ToolEffect = if (args.bool("force") == true) ToolEffect.DANGEROUS else ToolEffect.REMOTE

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val access = d.require()
        val force = args.bool("force") == true
        val branch = args.str("branch") ?: access.withRepo { it.currentBranch() } ?: throw ToolArgumentException("Not on a branch")
        val title = if (force) "Force push $branch" else "Push $branch"
        return Prepared(ApprovalRequest.Action(name, title, "$title to origin", dangerous = force)) {
            val creds = access.credentials()
            val outcome = access.withRepo { it.push(creds, branch = branch, force = force) }
            if (outcome.ok) ToolResult.ok("Pushed $branch", "updates" to outcome.updates.joinToString("\n")) else ToolResult.error(outcome.message ?: "Push failed")
        }
    }
}

/** All Phase 4 local tools. GitHub, Actions and web tools are added by [dev.gcode.agent.tools] in later phases. */
object CoreTools {
    fun create(files: FileToolDeps, git: GitToolDeps, todos: TodoStore): List<Tool> = listOf(
        ReadFileTool(files), ListDirTool(files), GlobTool(files), GrepTool(files),
        WriteFileTool(files), EditFileTool(files), MultiEditTool(files), DeleteFileTool(files), MoveFileTool(files),
        GitStatusTool(git), GitDiffTool(git), GitLogTool(git), GitAddTool(git), GitCommitTool(git), GitBranchTool(git),
        GitCheckoutTool(git), GitMergeTool(git), GitPullTool(git), GitPushTool(git),
        TodoWriteTool(todos), SubmitPlanTool(),
    )
}
