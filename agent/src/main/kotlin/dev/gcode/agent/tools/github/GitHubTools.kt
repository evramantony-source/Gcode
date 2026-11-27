package dev.gcode.agent.tools.github

import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.FixedEffectTool
import dev.gcode.agent.tools.Prepared
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.bool
import dev.gcode.agent.tools.int
import dev.gcode.agent.tools.requireStr
import dev.gcode.agent.tools.schema
import dev.gcode.agent.tools.str
import dev.gcode.agent.tools.strings
import dev.gcode.core.model.RepoRef
import dev.gcode.github.api.ActionsApi
import dev.gcode.github.api.IssuesApi
import dev.gcode.github.api.PullsApi
import dev.gcode.github.api.ReleasesApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhRepo
import dev.gcode.github.model.MergePullRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** A run started by [AgentHost.startBuild]. */
data class StartedRun(val runId: Long, val workflow: String, val htmlUrl: String?)

/** An APK extracted from a run's artifact into app storage. */
data class DownloadedApk(val path: String, val label: String?, val sizeBytes: Long)

/**
 * App services the GitHub and Actions tools need beyond the REST API: the open clone, the build
 * pipeline (push, run lookup, dispatch), APK files and the installer.
 */
interface AgentHost {
    /** The open workspace's GitHub repository, if it is a clone. */
    suspend fun activeRepo(): RepoRef?
    suspend fun currentBranch(): String?

    /** Clones [fullName] into app storage and opens it as the workspace. Returns a short description. */
    suspend fun cloneRepo(fullName: String, singleBranch: Boolean, progress: suspend (String) -> Unit): String

    /** Adds the GCode build workflow to the open repository, commits and pushes it. */
    suspend fun setUpBuildWorkflow(): String

    /** Pushes the current branch, then follows the run the push started or dispatches [workflow]. */
    suspend fun startBuild(workflow: String?, inputs: Map<String, String>): StartedRun

    suspend fun downloadApk(ref: RepoRef, runId: Long, artifact: String?): DownloadedApk

    /** Starts the system installer for an APK in app storage. */
    suspend fun installApk(path: String): String

    /** Per-repository limit for autonomous build-fix attempts (default 3). */
    suspend fun buildFixLimit(ref: RepoRef): Int
}

class GitHubToolDeps(
    val repos: ReposApi,
    val pulls: PullsApi,
    val issues: IssuesApi,
    val actions: ActionsApi,
    val releases: ReleasesApi,
    val host: AgentHost,
    val guard: SecretGuard = SecretGuard(),
    /** Sleep used while waiting for runs and mergeability (replaced in tests). */
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The `repo` argument ("owner/name"), or the open repository. */
    suspend fun repo(args: JsonObject): RepoRef {
        args.str("repo")?.takeIf { it.isNotBlank() }?.let { return RepoRef.parse(it.trim()) ?: throw ToolArgumentException("repo must look like owner/name") }
        return host.activeRepo() ?: throw ToolArgumentException("No GitHub repository is open. Pass repo as owner/name or open a clone.")
    }
}

private fun repoLine(r: GhRepo) = buildString {
    append(r.fullName)
    append(if (r.private) " [private]" else " [public]")
    if (r.fork) append(" [fork]")
    if (r.archived) append(" [archived]")
    append(" default:${r.defaultBranch}")
    if (r.stars > 0) append(" ★${r.stars}")
    r.language?.let { append(" $it") }
    r.description?.takeIf { it.isNotBlank() }?.let { append(" — ${it.take(120)}") }
}

private fun pullLine(p: GhPull) = "#${p.number} [${if (p.merged) "merged" else p.state}${if (p.draft) ", draft" else ""}] ${p.title} (${p.head.ref} → ${p.base.ref}) ${p.htmlUrl}"

class GhListReposTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_list_repos"
    override val description = "List the user's GitHub repositories (owned, collaborator and organisation), most recently pushed first, or one organisation's repositories."
    override val parameters = schema {
        string("org", "Only this organisation's repositories")
        integer("limit", "Maximum repositories to return (default 30)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val limit = (args.int("limit") ?: 30).coerceIn(1, 200)
        val repos = args.str("org")?.takeIf { it.isNotBlank() }?.let { d.repos.orgRepos(it, maxPages = 2) } ?: d.repos.myRepos(maxPages = 2)
        val text = repos.take(limit).joinToString("\n", transform = ::repoLine)
        ToolResult(true, ToolResult.jsonOf(listOf("repositories" to text.ifEmpty { "(none)" })), "${minOf(limit, repos.size)} repositories")
    }
}

class GhSearchReposTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_search_repos"
    override val description = "Search GitHub repositories with GitHub search syntax (e.g. \"compose markdown language:kotlin stars:>100\", \"user:octocat\")."
    override val parameters = schema {
        string("query", "Search query", required = true)
        integer("limit", "Maximum results (default 20)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
        val result = d.repos.searchRepos(args.requireStr("query"), perPage = limit)
        val text = result.items.joinToString("\n", transform = ::repoLine)
        ToolResult(true, ToolResult.jsonOf(listOf("total_count" to result.totalCount, "repositories" to text.ifEmpty { "(no results)" })), "${result.items.size} of ${result.totalCount} results")
    }
}

class GhCreateRepoTool(private val d: GitHubToolDeps) : Tool {
    override val name = "gh_create_repo"
    override val description = "Create a GitHub repository (private by default). Making a repository public always needs the user's confirmation."
    override val parameters = schema {
        string("name", "Repository name", required = true)
        string("description", "Short description")
        boolean("private", "Private repository (default true)")
        string("org", "Create in this organisation instead of the user's account")
        boolean("auto_init", "Create an initial commit with a README (default true)")
        string("gitignore_template", "e.g. Android, Kotlin, Node")
        string("license_template", "SPDX-like key, e.g. apache-2.0, mit")
    }

    override suspend fun effect(args: JsonObject): ToolEffect = if (args.bool("private") == false) ToolEffect.DANGEROUS else ToolEffect.REMOTE

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val name = args.requireStr("name").trim()
        if (!Regex("^[A-Za-z0-9._-]{1,100}$").matches(name)) throw ToolArgumentException("Repository names use letters, digits, '.', '_' and '-'")
        val private = args.bool("private") != false
        val org = args.str("org")?.takeIf { it.isNotBlank() }
        val target = "${org ?: "your account"}/$name"
        val request = CreateRepoRequest(
            name = name,
            description = args.str("description"),
            private = private,
            autoInit = args.bool("auto_init") != false,
            gitignoreTemplate = args.str("gitignore_template")?.takeIf { it.isNotBlank() },
            licenseTemplate = args.str("license_template")?.takeIf { it.isNotBlank() },
        )
        val title = if (private) "Create private repository $name" else "Create PUBLIC repository $name"
        return Prepared(ApprovalRequest.Action(this.name, title, "$title in $target." + if (private) "" else " Public repositories are visible to everyone.", dangerous = !private)) {
            val repo = d.repos.createRepo(request, org)
            ToolResult.ok("Created ${repo.fullName}", "full_name" to repo.fullName, "html_url" to repo.htmlUrl, "default_branch" to repo.defaultBranch, "private" to repo.private)
        }
    }
}

class GhCloneRepoTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.GIT_LOCAL) {
    override val name = "gh_clone_repo"
    override val description = "Clone a GitHub repository onto the device and open it as the workspace for the file and Git tools."
    override val parameters = schema {
        string("repo", "owner/name", required = true)
        boolean("single_branch", "Clone only the default branch (default true; smaller download)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = RepoRef.parse(args.requireStr("repo").trim()) ?: throw ToolArgumentException("repo must look like owner/name")
        return Prepared(ApprovalRequest.Action(name, "Clone ${ref.fullName}", "Clone ${ref.fullName} into app storage and open it as the workspace.", dangerous = false)) {
            val result = d.host.cloneRepo(ref.fullName, args.bool("single_branch") != false) { context.progress(it) }
            ToolResult.ok(result, "repo" to ref.fullName)
        }
    }
}

class GhForkRepoTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_fork_repo"
    override val description = "Fork a repository into the user's account (or an organisation). GitHub creates forks asynchronously; it may take a few seconds before it can be cloned."
    override val parameters = schema {
        string("repo", "owner/name to fork", required = true)
        string("org", "Fork into this organisation")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = RepoRef.parse(args.requireStr("repo").trim()) ?: throw ToolArgumentException("repo must look like owner/name")
        val org = args.str("org")?.takeIf { it.isNotBlank() }
        return Prepared(ApprovalRequest.Action(name, "Fork ${ref.fullName}", "Fork ${ref.fullName} into ${org ?: "your account"}.", dangerous = false)) {
            val fork = d.repos.fork(ref, org)
            ToolResult.ok("Forked to ${fork.fullName}", "full_name" to fork.fullName, "html_url" to fork.htmlUrl)
        }
    }
}

class GhGetFileTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_get_file"
    override val description = "Read a file (or list a directory) from a GitHub repository without cloning it."
    override val parameters = schema {
        string("repo", "owner/name (default: the open repository)")
        string("path", "Path in the repository; empty or / lists the root", required = false)
        string("ref", "Branch, tag or commit (default: the default branch)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        val path = args.str("path").orEmpty().trim('/')
        val gitRef = args.str("ref")?.takeIf { it.isNotBlank() }
        val reason = path.takeIf { it.isNotEmpty() }?.let { d.guard.reason(it) }
        val approval = reason?.let { ApprovalRequest.SecretAccess(name, "Read ${ref.fullName}/$path", path, it) }
        return Prepared(approval) {
            val entries = d.repos.content(ref, path, gitRef)
            val single = entries.singleOrNull()
            if (single != null && single.type == "file" && single.path == path) {
                val text = d.repos.rawFile(ref, path, gitRef)
                ToolResult(true, ToolResult.jsonOf(listOf("path" to path, "content" to text)), "Read ${ref.fullName}/$path (${text.length} chars)")
            } else {
                val listing = entries.joinToString("\n") { "${if (it.type == "dir") "dir " else "file"} ${it.path}${if (it.type == "file") " (${it.size} bytes)" else ""}" }
                ToolResult(true, ToolResult.jsonOf(listOf("path" to path.ifEmpty { "/" }, "entries" to listing.ifEmpty { "(empty)" })), "${entries.size} entries in ${ref.fullName}/$path")
            }
        }
    }
}

class GhCreatePrTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_create_pr"
    override val description = "Open a pull request. Push the head branch first (git_push). head defaults to the current branch, base to the default branch."
    override val parameters = schema {
        string("title", "Pull request title", required = true)
        string("body", "Description in Markdown")
        string("head", "Branch with the changes (default: current branch)")
        string("base", "Branch to merge into (default: the repository's default branch)")
        boolean("draft", "Open as a draft")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        val head = args.str("head")?.takeIf { it.isNotBlank() } ?: d.host.currentBranch() ?: throw ToolArgumentException("head is required (no current branch)")
        val base = args.str("base")?.takeIf { it.isNotBlank() } ?: d.repos.repo(ref).defaultBranch
        if (head == base) throw ToolArgumentException("head and base are both '$base'; create and push a feature branch first")
        val request = CreatePullRequest(args.requireStr("title"), head = head, base = base, body = args.str("body"), draft = args.bool("draft") == true)
        return Prepared(ApprovalRequest.Action(name, "Open PR: ${request.title}", "${ref.fullName}: $head → $base\n\n${request.body.orEmpty()}".trim(), dangerous = false)) {
            val pr = d.pulls.createPull(ref, request)
            ToolResult.ok("Opened #${pr.number}", "number" to pr.number, "html_url" to pr.htmlUrl)
        }
    }
}

class GhListPrsTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_list_prs"
    override val description = "List pull requests, most recently updated first."
    override val parameters = schema {
        string("state", "open (default), closed or all", enum = listOf("open", "closed", "all"))
        string("base", "Only PRs into this branch")
        integer("limit", "Maximum results (default 20)")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val prs = d.pulls.pulls(ref, state = args.str("state") ?: "open", base = args.str("base"), perPage = (args.int("limit") ?: 20).coerceIn(1, 100))
        ToolResult(true, ToolResult.jsonOf(listOf("pull_requests" to prs.joinToString("\n", transform = ::pullLine).ifEmpty { "(none)" })), "${prs.size} pull requests")
    }
}

class GhMergePrTool(private val d: GitHubToolDeps) : Tool {
    override val name = "gh_merge_pr"
    override val description = "Merge a pull request. Merging into the default branch always needs the user's confirmation."
    override val parameters = schema {
        integer("number", "Pull request number", required = true)
        string("method", "merge (default), squash or rebase", enum = listOf("merge", "squash", "rebase"))
        string("commit_title", "Title for the merge or squash commit")
        string("repo", "owner/name (default: the open repository)")
    }

    private suspend fun intoDefault(args: JsonObject): Pair<GhPull, Boolean> {
        val ref = d.repo(args)
        val number = args.int("number") ?: throw ToolArgumentException("number is required")
        val pr = d.pulls.pull(ref, number)
        return pr to (pr.base.ref == d.repos.repo(ref).defaultBranch)
    }

    override suspend fun effect(args: JsonObject): ToolEffect =
        runCatching { if (intoDefault(args).second) ToolEffect.DANGEROUS else ToolEffect.REMOTE }.getOrDefault(ToolEffect.DANGEROUS)

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        var (pr, toDefault) = intoDefault(args)
        if (pr.merged) throw ToolArgumentException("#${pr.number} is already merged")
        if (pr.state != "open") throw ToolArgumentException("#${pr.number} is ${pr.state}")
        // GitHub computes mergeability in the background; mergeable is null until it is done.
        repeat(3) { if (pr.mergeable == null) { d.sleep(2_000); pr = d.pulls.pull(ref, pr.number) } }
        if (pr.mergeable == false) throw ToolArgumentException("#${pr.number} cannot be merged (${pr.mergeableState ?: "conflicts"}). Resolve conflicts first.")
        val method = args.str("method") ?: "merge"
        val title = "Merge #${pr.number} into ${pr.base.ref}"
        val detail = "${ref.fullName}: ${pr.title}\n${pr.head.ref} → ${pr.base.ref} ($method)" + if (toDefault) "\nThis changes the default branch." else ""
        return Prepared(ApprovalRequest.Action(name, title, detail, dangerous = toDefault)) {
            val result = d.pulls.mergePull(ref, pr.number, MergePullRequest(commitTitle = args.str("commit_title"), sha = pr.head.sha, mergeMethod = method))
            if (result.merged) ToolResult.ok("Merged #${pr.number}", "sha" to result.sha, "message" to result.message) else ToolResult.error(result.message.ifBlank { "Not merged" })
        }
    }
}

class GhCommentTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_comment"
    override val description = "Comment on an issue or pull request."
    override val parameters = schema {
        integer("number", "Issue or pull request number", required = true)
        string("body", "Comment in Markdown", required = true)
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        val number = args.int("number") ?: throw ToolArgumentException("number is required")
        val body = args.requireStr("body")
        return Prepared(ApprovalRequest.Action(name, "Comment on #$number", "${ref.fullName}#$number\n\n$body", dangerous = false)) {
            val comment = d.issues.comment(ref, number, body)
            ToolResult.ok("Commented on #$number", "html_url" to comment.htmlUrl)
        }
    }
}

class GhListIssuesTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_list_issues"
    override val description = "List issues (pull requests excluded), most recently updated first."
    override val parameters = schema {
        string("state", "open (default), closed or all", enum = listOf("open", "closed", "all"))
        string("labels", "Comma-separated label names")
        integer("limit", "Maximum results (default 20)")
        string("repo", "owner/name (default: the open repository)")
        integer("number", "Read one issue with its comments instead")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        args.int("number")?.let { number ->
            val issue = d.issues.issue(ref, number)
            val comments = d.issues.comments(ref, number, maxPages = 2)
            val text = buildString {
                appendLine("#${issue.number} [${issue.state}] ${issue.title}")
                appendLine("labels: ${issue.labels.joinToString { it.name }.ifEmpty { "none" }} · by ${issue.user?.login}")
                appendLine(issue.body.orEmpty())
                comments.forEach { appendLine("--- ${it.user?.login} (${it.createdAt}):\n${it.body}") }
            }
            return@Prepared ToolResult(true, ToolResult.jsonOf(listOf("issue" to text.trim())), "Issue #$number with ${comments.size} comments")
        }
        val issues = d.issues.issues(ref, state = args.str("state") ?: "open", labels = args.str("labels"), perPage = (args.int("limit") ?: 20).coerceIn(1, 100))
        val text = issues.joinToString("\n") { "#${it.number} [${it.state}] ${it.title}${if (it.labels.isNotEmpty()) " (" + it.labels.joinToString { l -> l.name } + ")" else ""}" }
        ToolResult(true, ToolResult.jsonOf(listOf("issues" to text.ifEmpty { "(none)" })), "${issues.size} issues")
    }
}

class GhCreateIssueTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_create_issue"
    override val description = "Open an issue."
    override val parameters = schema {
        string("title", "Issue title", required = true)
        string("body", "Description in Markdown")
        stringArray("labels", "Label names")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        val request = CreateIssueRequest(args.requireStr("title"), body = args.str("body"), labels = args.strings("labels").ifEmpty { null })
        return Prepared(ApprovalRequest.Action(name, "Open issue: ${request.title}", "${ref.fullName}\n\n${request.body.orEmpty()}".trim(), dangerous = false)) {
            val issue = d.issues.createIssue(ref, request)
            ToolResult.ok("Opened issue #${issue.number}", "number" to issue.number, "html_url" to issue.htmlUrl)
        }
    }
}

/** The GitHub, Actions and release tools (Phase 7), and the system-prompt guidance that goes with them. */
object GitHubTools {
    fun create(d: GitHubToolDeps, guard: BuildFixGuard): List<dev.gcode.agent.tools.Tool> = listOf(
        GhListReposTool(d), GhSearchReposTool(d), GhCreateRepoTool(d), GhCloneRepoTool(d), GhForkRepoTool(d), GhGetFileTool(d),
        GhCreatePrTool(d), GhListPrsTool(d), GhMergePrTool(d), GhCommentTool(d), GhListIssuesTool(d), GhCreateIssueTool(d),
        GhSetupBuildWorkflowTool(d), GhTriggerWorkflowTool(d, guard), GhGetRunStatusTool(d), GhWaitForRunTool(d, guard),
        GhGetRunLogsTool(d), GhListArtifactsTool(d), GhDownloadApkTool(d), GhInstallApkTool(d), GhCreateReleaseTool(d),
    )

    val GUIDANCE = """
        # GitHub and builds
        - Builds run only on GitHub Actions. To build: commit your changes (git_add, git_commit), then gh_trigger_workflow (it pushes the branch and finds or starts the run), then gh_wait_for_run.
        - An Android app without a build workflow: offer gh_setup_build_workflow, which adds .github/workflows/gcode-build.yml.
        - When a build fails: read the failed steps with gh_get_run_logs, find the root cause (the first real error, not the last line), fix it, commit, and trigger again. The tools report how many fix attempts are left; when none are left, stop and summarise the errors for the user.
        - After a successful build use gh_download_apk. Install (gh_install_apk) only when the user asks; it is always confirmed.
        - Work on a branch and open a pull request (gh_create_pr) for larger changes. Never merge into the default branch unless the user asks.
        - Check current library versions, APIs and error messages with web_search or web_fetch instead of guessing.
    """.trimIndent()
}
