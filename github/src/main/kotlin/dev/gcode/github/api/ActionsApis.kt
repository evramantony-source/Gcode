package dev.gcode.github.api

import dev.gcode.core.model.RepoRef
import dev.gcode.github.model.CommentRequest
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhIssue
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhPullFile
import dev.gcode.github.model.MergePullRequest
import dev.gcode.github.model.MergeResult
import dev.gcode.github.model.UpdateIssueRequest
import dev.gcode.github.model.CreateReleaseRequest
import dev.gcode.github.model.DispatchResponse
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhPublicKey
import dev.gcode.github.model.GhRelease
import dev.gcode.github.model.GhReleaseAsset
import dev.gcode.github.model.GhSecretInfo
import dev.gcode.github.model.GhWorkflow
import dev.gcode.github.model.GhWorkflowRun
import java.io.File

/** GitHub Actions (ARCHITECTURE.md section 8.4). */
interface ActionsApi {
    suspend fun workflows(ref: RepoRef): List<GhWorkflow>

    /** Starts a workflow_dispatch run; API 2026-03-10 returns the new run id. */
    suspend fun dispatch(ref: RepoRef, workflow: String, gitRef: String, inputs: Map<String, String> = emptyMap()): DispatchResponse

    suspend fun runs(ref: RepoRef, workflow: String? = null, branch: String? = null, headSha: String? = null, event: String? = null, perPage: Int = 20): List<GhWorkflowRun>
    suspend fun run(ref: RepoRef, runId: Long): GhWorkflowRun
    suspend fun jobs(ref: RepoRef, runId: Long): List<GhJob>

    /** Plain-text log of one job (follows the 1-minute redirect). */
    suspend fun jobLog(ref: RepoRef, jobId: Long): String

    suspend fun artifacts(ref: RepoRef, runId: Long): List<GhArtifact>

    /** Streams an artifact zip to [dest] (the redirect host never sees the token). */
    suspend fun downloadArtifact(ref: RepoRef, artifactId: Long, dest: File, progress: (Long, Long) -> Unit = { _, _ -> })

    suspend fun cancelRun(ref: RepoRef, runId: Long)
    suspend fun rerunFailedJobs(ref: RepoRef, runId: Long)
}

interface ReleasesApi {
    suspend fun releases(ref: RepoRef): List<GhRelease>
    suspend fun createRelease(ref: RepoRef, request: CreateReleaseRequest): GhRelease
    suspend fun uploadAsset(release: GhRelease, file: File, name: String, contentType: String): GhReleaseAsset
}

interface SecretsApi {
    suspend fun secretNames(ref: RepoRef): List<GhSecretInfo>
    suspend fun publicKey(ref: RepoRef): GhPublicKey

    /** Encrypts [value] with a libsodium sealed box and stores it. */
    suspend fun putSecret(ref: RepoRef, name: String, value: String)
}

interface ContentsApi {
    /** Creates or updates one file with a commit (no clone needed). */
    suspend fun putFile(ref: RepoRef, path: String, content: ByteArray, message: String, branch: String? = null): String?
}

interface PullsApi {
    suspend fun pulls(ref: RepoRef, state: String = "open", base: String? = null, head: String? = null, perPage: Int = 30): List<GhPull>
    suspend fun pull(ref: RepoRef, number: Int): GhPull
    suspend fun createPull(ref: RepoRef, request: CreatePullRequest): GhPull

    /** 405 when not mergeable, 409 when [MergePullRequest.sha] does not match the head. */
    suspend fun mergePull(ref: RepoRef, number: Int, request: MergePullRequest): MergeResult
    suspend fun pullFiles(ref: RepoRef, number: Int, maxPages: Int = 10): List<GhPullFile>
    suspend fun updatePullState(ref: RepoRef, number: Int, open: Boolean): GhPull
}

/** Issues and the comment thread shared by issues and pull requests. */
interface IssuesApi {
    /** Issues only: pull requests, which the endpoint also returns, are filtered out. */
    suspend fun issues(ref: RepoRef, state: String = "open", labels: String? = null, perPage: Int = 30): List<GhIssue>
    suspend fun issue(ref: RepoRef, number: Int): GhIssue
    suspend fun createIssue(ref: RepoRef, request: CreateIssueRequest): GhIssue
    suspend fun updateIssue(ref: RepoRef, number: Int, request: UpdateIssueRequest): GhIssue
    suspend fun comments(ref: RepoRef, number: Int, maxPages: Int = 5): List<GhComment>
    suspend fun comment(ref: RepoRef, number: Int, body: String): GhComment
}
