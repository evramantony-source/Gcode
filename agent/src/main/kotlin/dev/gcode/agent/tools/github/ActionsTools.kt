package dev.gcode.agent.tools.github

import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.FixedEffectTool
import dev.gcode.agent.tools.Prepared
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
import dev.gcode.github.actions.LogParser
import dev.gcode.github.model.CreateReleaseRequest
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhWorkflowRun
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Limits the autonomous build → fix → rebuild loop (ARCHITECTURE.md section 6.6). Counts consecutive failed
 * runs per repository within one agent turn; after [limit] fix attempts the agent must stop and report.
 */
class BuildFixGuard(private val limit: suspend (RepoRef) -> Int) {
    private val failures = mutableMapOf<String, Int>()

    fun failures(ref: RepoRef): Int = synchronized(failures) { failures[ref.fullName] ?: 0 }

    /** Null when another build may start, otherwise the reason it may not. */
    suspend fun blockReason(ref: RepoRef): String? {
        val used = failures(ref)
        val max = limit(ref)
        return if (used > max) {
            "Build-fix limit reached: $used failed builds in a row for ${ref.fullName} (limit $max fix attempts). " +
                "Stop here, summarise the remaining errors and what you tried, and ask the user how to continue."
        } else {
            null
        }
    }

    fun record(ref: RepoRef, succeeded: Boolean) = synchronized(failures) {
        if (succeeded) failures.remove(ref.fullName) else failures[ref.fullName] = (failures[ref.fullName] ?: 0) + 1
    }

    /** One line for tool results after a failed run. */
    suspend fun afterFailure(ref: RepoRef): String {
        val used = failures(ref)
        val max = limit(ref)
        val left = max - (used - 1)
        return if (left > 0) {
            "Build failed ($used in a row). Read the failed steps with gh_get_run_logs, fix the cause, commit, push and trigger again. Fix attempts left: $left of $max."
        } else {
            "Build failed again and no fix attempts are left (limit $max). Stop, summarise the errors and ask the user how to continue."
        }
    }
}

private fun runLine(run: GhWorkflowRun) =
    "run ${run.id} #${run.runNumber} ${run.name.orEmpty()} [${run.status}${run.conclusion?.let { "/$it" } ?: ""}] branch ${run.headBranch} sha ${run.headSha.take(7)} ${run.htmlUrl}"

private fun jobsText(jobs: List<GhJob>) = jobs.joinToString("\n") { job ->
    val failed = job.steps.filter { it.conclusion == "failure" }.joinToString { "\"${it.name}\"" }
    "job ${job.id} ${job.name} [${job.status}${job.conclusion?.let { "/$it" } ?: ""}]" + if (failed.isNotEmpty()) " failed steps: $failed" else ""
}

class GhSetupBuildWorkflowTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_setup_build_workflow"
    override val description = "Add GCode's GitHub Actions workflow (.github/workflows/gcode-build.yml) to the open Android repository, commit and push it. It builds a debug APK artifact on every push and on request."
    override val parameters = schema { }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.host.activeRepo() ?: throw ToolArgumentException("Open a cloned GitHub repository first")
        return Prepared(ApprovalRequest.Action(name, "Set up builds for ${ref.fullName}", "Commit .github/workflows/gcode-build.yml and push it to ${d.host.currentBranch() ?: "the current branch"}.", dangerous = false)) {
            ToolResult.ok(d.host.setUpBuildWorkflow(), "workflow" to "gcode-build.yml")
        }
    }
}

class GhTriggerWorkflowTool(private val d: GitHubToolDeps, private val guard: BuildFixGuard) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_trigger_workflow"
    override val description = "Start a build of the open repository on GitHub Actions: pushes the current branch, then follows the run that the push started for this commit or dispatches the workflow. Commit your changes first. Returns run_id; then call gh_wait_for_run."
    override val parameters = schema {
        string("workflow", "Workflow file name, e.g. gcode-build.yml (default: the repository's build workflow)")
        stringArray("inputs", "workflow_dispatch inputs as key=value")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.host.activeRepo() ?: throw ToolArgumentException("Open a cloned GitHub repository first")
        guard.blockReason(ref)?.let { reason -> return Prepared(null) { ToolResult.error(reason) } }
        val inputs = args.strings("inputs").associate { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) throw ToolArgumentException("inputs must be key=value, got '$entry'")
            entry.substring(0, i).trim() to entry.substring(i + 1)
        }
        val workflow = args.str("workflow")?.takeIf { it.isNotBlank() }
        val branch = d.host.currentBranch()
        return Prepared(ApprovalRequest.Action(name, "Build ${ref.fullName}", "Push ${branch ?: "the current branch"} and run ${workflow ?: "the build workflow"} on GitHub Actions.", dangerous = false)) {
            val run = d.host.startBuild(workflow, inputs)
            ToolResult.ok("Started run ${run.runId}", "run_id" to run.runId, "workflow" to run.workflow, "html_url" to run.htmlUrl)
        }
    }
}

class GhGetRunStatusTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_get_run_status"
    override val description = "Status of a workflow run with its jobs and failed steps. Without run_id, lists the latest runs of the repository (optionally for a branch)."
    override val parameters = schema {
        integer("run_id", "Run id")
        string("branch", "Latest runs on this branch (when run_id is omitted)")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val runId = args.int("run_id")?.toLong()
        if (runId == null) {
            val runs = d.actions.runs(ref, branch = args.str("branch"), perPage = 10)
            return@Prepared ToolResult(true, ToolResult.jsonOf(listOf("runs" to runs.joinToString("\n", transform = ::runLine).ifEmpty { "(no runs)" })), "${runs.size} runs")
        }
        val run = d.actions.run(ref, runId)
        val jobs = d.actions.jobs(ref, runId)
        ToolResult(true, ToolResult.jsonOf(listOf("run" to runLine(run), "jobs" to jobsText(jobs))), "Run ${run.runNumber}: ${run.conclusion ?: run.status}")
    }
}

class GhWaitForRunTool(private val d: GitHubToolDeps, private val guard: BuildFixGuard) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_wait_for_run"
    override val description = "Wait until a workflow run completes (polls every 15 s), then report its result and failed steps."
    override val parameters = schema {
        integer("run_id", "Run id", required = true)
        integer("timeout_minutes", "Give up after this long (default 30, max 60)")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val runId = args.int("run_id")?.toLong() ?: throw ToolArgumentException("run_id is required")
        val timeoutMs = (args.int("timeout_minutes") ?: 30).coerceIn(1, 60) * 60_000L
        var waited = 0L
        var run = d.actions.run(ref, runId)
        while (!run.isCompleted) {
            if (waited >= timeoutMs) {
                return@Prepared ToolResult(false, ToolResult.jsonOf(listOf("error" to "Still ${run.status} after ${waited / 60_000} minutes", "run" to runLine(run))), "Run still ${run.status}")
            }
            context.progress("Run #${run.runNumber}: ${run.status} (${waited / 1000} s)")
            d.sleep(POLL_MS)
            waited += POLL_MS
            run = d.actions.run(ref, runId)
        }
        val jobs = d.actions.jobs(ref, runId)
        val succeeded = run.conclusion == "success"
        if (run.conclusion != "cancelled" && run.conclusion != "skipped") guard.record(ref, succeeded)
        val next = when {
            succeeded -> "Build succeeded. Use gh_list_artifacts or gh_download_apk to get the APK."
            run.conclusion == "cancelled" -> "The run was cancelled."
            else -> guard.afterFailure(ref)
        }
        ToolResult(succeeded, ToolResult.jsonOf(listOf("run" to runLine(run), "conclusion" to run.conclusion, "jobs" to jobsText(jobs), "next" to next)), "Run #${run.runNumber}: ${run.conclusion}")
    }

    companion object {
        const val POLL_MS = 15_000L
    }
}

class GhGetRunLogsTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_get_run_logs"
    override val description = "Logs of a run's failed steps (error lines with context and the end of each failed step). Pass job_id to read one job even if it did not fail."
    override val parameters = schema {
        integer("run_id", "Run id", required = true)
        integer("job_id", "Only this job")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val runId = args.int("run_id")?.toLong() ?: throw ToolArgumentException("run_id is required")
        val jobs = d.actions.jobs(ref, runId)
        val jobId = args.int("job_id")?.toLong()
        val selected = if (jobId != null) jobs.filter { it.id == jobId } else jobs.filter { it.conclusion == "failure" || it.conclusion == "timed_out" }
        if (selected.isEmpty()) {
            return@Prepared ToolResult(true, ToolResult.jsonOf(listOf("logs" to "No failed jobs in run $runId.", "jobs" to jobsText(jobs))), "No failed jobs")
        }
        val text = buildString {
            selected.forEach { job ->
                context.progress("Downloading the log of ${job.name}")
                val steps = LogParser.split(d.actions.jobLog(ref, job.id), job)
                appendLine("=== job ${job.name} [${job.conclusion}]")
                val budget = 24_000 / selected.size
                if (steps.any { it.failed || it.errorLines.isNotEmpty() }) {
                    appendLine(LogParser.failureSummary(steps, maxChars = budget))
                } else {
                    appendLine(steps.flatMap { it.lines }.takeLast(150).joinToString("\n").takeLast(budget))
                }
            }
        }
        ToolResult(true, ToolResult.jsonOf(listOf("logs" to text.trim())), "Logs of ${selected.size} job(s)")
    }
}

class GhListArtifactsTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_list_artifacts"
    override val description = "List the artifacts a run uploaded (name, size, expired)."
    override val parameters = schema {
        integer("run_id", "Run id", required = true)
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val runId = args.int("run_id")?.toLong() ?: throw ToolArgumentException("run_id is required")
        val artifacts = d.actions.artifacts(ref, runId)
        val text = artifacts.joinToString("\n") { "${it.id} ${it.name} ${it.sizeInBytes / 1024} KiB${if (it.expired) " [expired]" else ""}" }
        ToolResult(true, ToolResult.jsonOf(listOf("artifacts" to text.ifEmpty { "(none)" })), "${artifacts.size} artifacts")
    }
}

class GhDownloadApkTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.READ) {
    override val name = "gh_download_apk"
    override val description = "Download a run's APK artifact to the device and extract the APK. Returns its path for gh_install_apk or gh_create_release."
    override val parameters = schema {
        integer("run_id", "Run id", required = true)
        string("artifact", "Artifact name (default: the first artifact with an APK)")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val ref = d.repo(args)
        val runId = args.int("run_id")?.toLong() ?: throw ToolArgumentException("run_id is required")
        context.progress("Downloading the artifact")
        val apk = d.host.downloadApk(ref, runId, args.str("artifact")?.takeIf { it.isNotBlank() })
        ToolResult.ok("Downloaded ${File(apk.path).name}", "path" to apk.path, "package" to apk.label, "size_bytes" to apk.sizeBytes)
    }
}

class GhInstallApkTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.DANGEROUS) {
    override val name = "gh_install_apk"
    override val description = "Install an APK downloaded with gh_download_apk on this phone. Always confirmed by the user, and Android asks again."
    override val parameters = schema { string("path", "APK path returned by gh_download_apk", required = true) }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val path = args.requireStr("path")
        if (!path.endsWith(".apk")) throw ToolArgumentException("path must be an .apk returned by gh_download_apk")
        return Prepared(ApprovalRequest.Action(name, "Install ${File(path).name}", "Install $path on this device.", dangerous = true)) {
            ToolResult.ok(d.host.installApk(path))
        }
    }
}

class GhCreateReleaseTool(private val d: GitHubToolDeps) : FixedEffectTool(ToolEffect.REMOTE) {
    override val name = "gh_create_release"
    override val description = "Create a tag and GitHub Release, optionally uploading an APK from gh_download_apk. Pushing a v* tag to a repository with the GCode workflow also builds a signed release when its signing secrets are set."
    override val parameters = schema {
        string("tag", "Tag name, e.g. v1.2.0", required = true)
        string("name", "Release title (default: the tag)")
        string("body", "Release notes in Markdown")
        string("target", "Branch or commit to tag (default: the default branch)")
        boolean("draft", "Create as a draft")
        boolean("prerelease", "Mark as a pre-release")
        boolean("generate_notes", "Let GitHub generate notes from merged PRs")
        string("apk_path", "APK to attach")
        string("repo", "owner/name (default: the open repository)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val ref = d.repo(args)
        val tag = args.requireStr("tag").trim()
        val apk = args.str("apk_path")?.takeIf { it.isNotBlank() }?.let(::File)
        if (apk != null && !apk.isFile) throw ToolArgumentException("No file at ${apk.path}")
        val request = CreateReleaseRequest(
            tagName = tag,
            targetCommitish = args.str("target")?.takeIf { it.isNotBlank() },
            name = args.str("name") ?: tag,
            body = args.str("body"),
            draft = args.bool("draft") == true,
            prerelease = args.bool("prerelease") == true,
            generateReleaseNotes = args.bool("generate_notes") == true,
        )
        val detail = "${ref.fullName}: tag $tag${request.targetCommitish?.let { " at $it" } ?: ""}" + (apk?.let { "\nAttach ${it.name}" } ?: "")
        return Prepared(ApprovalRequest.Action(name, "Create release $tag", detail, dangerous = false)) {
            val release = d.releases.createRelease(ref, request)
            val asset = apk?.let {
                context.progress("Uploading ${it.name}")
                d.releases.uploadAsset(release, it, it.name, "application/vnd.android.package-archive")
            }
            ToolResult.ok("Created release $tag", "html_url" to release.htmlUrl, "asset" to asset?.browserDownloadUrl)
        }
    }
}
