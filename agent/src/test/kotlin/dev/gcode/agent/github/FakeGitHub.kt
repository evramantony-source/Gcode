package dev.gcode.agent.github

import dev.gcode.agent.tools.github.AgentHost
import dev.gcode.agent.tools.github.DownloadedApk
import dev.gcode.agent.tools.github.StartedRun
import dev.gcode.core.model.RepoRef
import dev.gcode.github.api.ActionsApi
import dev.gcode.github.api.IssuesApi
import dev.gcode.github.api.PullsApi
import dev.gcode.github.api.ReleasesApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.CreateReleaseRequest
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.DispatchResponse
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhBranch
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhContent
import dev.gcode.github.model.GhIssue
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhLicense
import dev.gcode.github.model.GhOwner
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhPullFile
import dev.gcode.github.model.GhPullRef
import dev.gcode.github.model.GhRelease
import dev.gcode.github.model.GhReleaseAsset
import dev.gcode.github.model.GhRepo
import dev.gcode.github.model.GhSearchResult
import dev.gcode.github.model.GhStep
import dev.gcode.github.model.GhWorkflow
import dev.gcode.github.model.GhWorkflowRun
import dev.gcode.github.model.MergePullRequest
import dev.gcode.github.model.MergeResult
import dev.gcode.github.model.UpdateIssueRequest
import java.io.File

/** In-memory GitHub for tool tests. Runs are scripted: each started run completes with the next conclusion. */
class FakeGitHub(val active: RepoRef = RepoRef("octo", "app")) : ReposApi, PullsApi, IssuesApi, ActionsApi, ReleasesApi {
    val created = mutableListOf<CreateRepoRequest>()
    val pullsCreated = mutableListOf<CreatePullRequest>()
    val merges = mutableListOf<Pair<Int, MergePullRequest>>()
    val comments = mutableListOf<Pair<Int, String>>()
    val issuesCreated = mutableListOf<CreateIssueRequest>()
    val releasesCreated = mutableListOf<CreateReleaseRequest>()
    val uploads = mutableListOf<String>()
    val pullsByNumber = mutableMapOf<Int, GhPull>()

    /** Conclusions of the runs the host starts, in order; each run is "in_progress" on its first poll. */
    val conclusions = ArrayDeque<String>()
    val runs = mutableMapOf<Long, GhWorkflowRun>()
    private val polls = mutableMapOf<Long, Int>()
    private var nextRun = 100L

    fun repo(name: String, private: Boolean = true, defaultBranch: String = "main") =
        GhRepo(1, name.substringAfter('/'), name, GhOwner(name.substringBefore('/')), private = private, defaultBranch = defaultBranch, htmlUrl = "https://github.com/$name")

    fun pull(number: Int, base: String, mergeable: Boolean? = true) = GhPull(
        number, "PR $number", head = GhPullRef("feature", "headsha$number"), base = GhPullRef(base, "basesha"), mergeable = mergeable,
        htmlUrl = "https://github.com/octo/app/pull/$number",
    ).also { pullsByNumber[number] = it }

    fun startRun(): GhWorkflowRun {
        val id = nextRun++
        return GhWorkflowRun(id, "Build", headBranch = "main", headSha = "sha$id", status = "queued", runNumber = id.toInt(), htmlUrl = "https://github.com/octo/app/actions/runs/$id")
            .also { runs[id] = it }
    }

    // ---- ReposApi
    override suspend fun myRepos(maxPages: Int) = listOf(repo("octo/app"), repo("octo/site", private = false))
    override suspend fun orgRepos(org: String, maxPages: Int) = listOf(repo("$org/tools"))
    override suspend fun searchRepos(query: String, perPage: Int) = GhSearchResult(totalCount = 1, items = listOf(repo("someone/$query")))
    override suspend fun repo(ref: RepoRef) = repo(ref.fullName)
    override suspend fun createRepo(request: CreateRepoRequest, org: String?): GhRepo {
        created += request
        return repo("${org ?: "octo"}/${request.name}", request.private)
    }
    override suspend fun fork(ref: RepoRef, organization: String?, name: String?) = repo("${organization ?: "me"}/${ref.name}")
    override suspend fun deleteRepo(ref: RepoRef) = error("unused")
    override suspend fun setVisibility(ref: RepoRef, private: Boolean) = error("unused")
    override suspend fun gitignoreTemplates(): List<String> = emptyList()
    override suspend fun licenses(): List<GhLicense> = emptyList()
    override suspend fun branches(ref: RepoRef): List<GhBranch> = emptyList()
    override suspend fun deleteBranch(ref: RepoRef, branch: String) = error("unused")
    override suspend fun content(ref: RepoRef, path: String, gitRef: String?): List<GhContent> =
        if (path.isEmpty()) listOf(GhContent("file", "README.md", "README.md", "s1", 10), GhContent("dir", "app", "app", "s2"))
        else listOf(GhContent("file", path.substringAfterLast('/'), path, "s3", 5))
    override suspend fun rawFile(ref: RepoRef, path: String, gitRef: String?) = "content of $path"

    // ---- PullsApi
    override suspend fun pulls(ref: RepoRef, state: String, base: String?, head: String?, perPage: Int) = pullsByNumber.values.toList()
    private val pullReads = mutableMapOf<Int, Int>()
    override suspend fun pull(ref: RepoRef, number: Int): GhPull {
        val p = pullsByNumber[number] ?: error("404")
        val reads = (pullReads[number] ?: 0) + 1
        pullReads[number] = reads
        // Mergeability "computes" after the first read.
        return if (p.mergeable == null && reads > 2) p.copy(mergeable = true) else p
    }
    override suspend fun createPull(ref: RepoRef, request: CreatePullRequest): GhPull {
        pullsCreated += request
        return pull(9, request.base).copy(title = request.title)
    }
    override suspend fun mergePull(ref: RepoRef, number: Int, request: MergePullRequest): MergeResult {
        merges += number to request
        return MergeResult("mergesha", true, "merged")
    }
    override suspend fun pullFiles(ref: RepoRef, number: Int, maxPages: Int): List<GhPullFile> = emptyList()
    override suspend fun updatePullState(ref: RepoRef, number: Int, open: Boolean) = error("unused")

    // ---- IssuesApi
    override suspend fun issues(ref: RepoRef, state: String, labels: String?, perPage: Int) = listOf(GhIssue(1, "Crash", state = "open"))
    override suspend fun issue(ref: RepoRef, number: Int) = GhIssue(number, "Crash on start", body = "It crashes", user = GhOwner("alice"))
    override suspend fun createIssue(ref: RepoRef, request: CreateIssueRequest): GhIssue {
        issuesCreated += request
        return GhIssue(5, request.title, htmlUrl = "https://github.com/octo/app/issues/5")
    }
    override suspend fun updateIssue(ref: RepoRef, number: Int, request: UpdateIssueRequest) = error("unused")
    override suspend fun comments(ref: RepoRef, number: Int, maxPages: Int) = listOf(GhComment(1, "Same here", GhOwner("bob")))
    override suspend fun comment(ref: RepoRef, number: Int, body: String): GhComment {
        comments += number to body
        return GhComment(2, body, htmlUrl = "https://github.com/octo/app/issues/$number#c2")
    }

    // ---- ActionsApi
    override suspend fun workflows(ref: RepoRef) = listOf(GhWorkflow(1, "Build", ".github/workflows/gcode-build.yml"))
    override suspend fun dispatch(ref: RepoRef, workflow: String, gitRef: String, inputs: Map<String, String>) = DispatchResponse(startRun().id)
    override suspend fun runs(ref: RepoRef, workflow: String?, branch: String?, headSha: String?, event: String?, perPage: Int) = runs.values.toList().reversed()
    override suspend fun run(ref: RepoRef, runId: Long): GhWorkflowRun {
        val run = runs.getValue(runId)
        if (run.isCompleted) return run
        val n = (polls[runId] ?: 0) + 1
        polls[runId] = n
        if (n < 2) return run.copy(status = "in_progress")
        val done = run.copy(status = "completed", conclusion = conclusions.removeFirst())
        runs[runId] = done
        return done
    }
    override suspend fun jobs(ref: RepoRef, runId: Long): List<GhJob> {
        val run = runs.getValue(runId)
        val failed = run.conclusion == "failure"
        return listOf(
            GhJob(
                runId * 10, runId, "build", status = run.status, conclusion = run.conclusion,
                steps = listOf(
                    GhStep("Set up job", "completed", "success", 1, "2026-11-27T10:00:00Z", "2026-11-27T10:00:01Z"),
                    GhStep("Build with Gradle", "completed", if (failed) "failure" else "success", 2, "2026-11-27T10:00:02Z", "2026-11-27T10:00:09Z"),
                ),
            ),
        )
    }
    override suspend fun jobLog(ref: RepoRef, jobId: Long) = """
        2026-11-27T10:00:00.5000000Z Setting up
        2026-11-27T10:00:03.0000000Z > Task :app:compileDebugKotlin FAILED
        2026-11-27T10:00:03.1000000Z e: file:///home/runner/work/app/app/src/main/kotlin/Main.kt:3:5 Unresolved reference 'greet'.
        2026-11-27T10:00:04.0000000Z BUILD FAILED in 7s
    """.trimIndent()
    override suspend fun artifacts(ref: RepoRef, runId: Long) = listOf(GhArtifact(7, "app-debug-apk", 1_000_000))
    override suspend fun downloadArtifact(ref: RepoRef, artifactId: Long, dest: File, progress: (Long, Long) -> Unit) = error("unused")
    override suspend fun cancelRun(ref: RepoRef, runId: Long) = error("unused")
    override suspend fun rerunFailedJobs(ref: RepoRef, runId: Long) = error("unused")

    // ---- ReleasesApi
    override suspend fun releases(ref: RepoRef): List<GhRelease> = emptyList()
    override suspend fun createRelease(ref: RepoRef, request: CreateReleaseRequest): GhRelease {
        releasesCreated += request
        return GhRelease(3, request.tagName, htmlUrl = "https://github.com/octo/app/releases/tag/${request.tagName}", uploadUrl = "https://uploads.github.com/x{?name,label}")
    }
    override suspend fun uploadAsset(release: GhRelease, file: File, name: String, contentType: String): GhReleaseAsset {
        uploads += "$name:$contentType"
        return GhReleaseAsset(1, name, file.length(), contentType, "https://github.com/octo/app/releases/download/${release.tagName}/$name")
    }
}

class FakeHost(private val gh: FakeGitHub, private val limit: Int = 3) : AgentHost {
    val builds = mutableListOf<Pair<String?, Map<String, String>>>()
    val installs = mutableListOf<String>()
    var active: RepoRef? = gh.active
    override suspend fun activeRepo() = active
    override suspend fun currentBranch() = "feature"
    override suspend fun cloneRepo(fullName: String, singleBranch: Boolean, progress: suspend (String) -> Unit): String {
        progress("Receiving objects 50%")
        active = RepoRef.parse(fullName)
        return "Cloned $fullName"
    }
    override suspend fun setUpBuildWorkflow() = "Committed and pushed .github/workflows/gcode-build.yml"
    override suspend fun startBuild(workflow: String?, inputs: Map<String, String>): StartedRun {
        builds += workflow to inputs
        val run = gh.startRun()
        return StartedRun(run.id, workflow ?: "gcode-build.yml", run.htmlUrl)
    }
    override suspend fun downloadApk(ref: RepoRef, runId: Long, artifact: String?) = DownloadedApk("/data/builds/$runId/app-debug.apk", "dev.example 1.0 (1)", 1_000_000)
    override suspend fun installApk(path: String): String {
        installs += path
        return "Installed"
    }
    override suspend fun buildFixLimit(ref: RepoRef) = limit
}
