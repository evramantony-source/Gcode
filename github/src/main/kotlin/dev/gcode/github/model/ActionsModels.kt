package dev.gcode.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GhWorkflow(val id: Long, val name: String, val path: String, val state: String = "active", @SerialName("html_url") val htmlUrl: String? = null) {
    val fileName: String get() = path.substringAfterLast('/')
}

@Serializable
data class GhWorkflowsPage(@SerialName("total_count") val totalCount: Int = 0, val workflows: List<GhWorkflow> = emptyList())

@Serializable
data class GhWorkflowRun(
    val id: Long,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    val event: String? = null,
    @SerialName("run_number") val runNumber: Int = 0,
    @SerialName("run_attempt") val runAttempt: Int = 1,
    @SerialName("workflow_id") val workflowId: Long = 0,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("run_started_at") val runStartedAt: String? = null,
) {
    val isCompleted: Boolean get() = status == "completed"
    val succeeded: Boolean get() = conclusion == "success"
}

@Serializable
data class GhRunsPage(@SerialName("total_count") val totalCount: Int = 0, @SerialName("workflow_runs") val runs: List<GhWorkflowRun> = emptyList())

@Serializable
data class GhStep(
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    val number: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class GhJob(
    val id: Long,
    @SerialName("run_id") val runId: Long = 0,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val steps: List<GhStep> = emptyList(),
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GhJobsPage(@SerialName("total_count") val totalCount: Int = 0, val jobs: List<GhJob> = emptyList())

@Serializable
data class GhArtifactRun(val id: Long = 0, @SerialName("head_sha") val headSha: String? = null, @SerialName("head_branch") val headBranch: String? = null)

@Serializable
data class GhArtifact(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val sizeInBytes: Long = 0,
    val expired: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("archive_download_url") val archiveDownloadUrl: String? = null,
    @SerialName("workflow_run") val workflowRun: GhArtifactRun? = null,
)

@Serializable
data class GhArtifactsPage(@SerialName("total_count") val totalCount: Int = 0, val artifacts: List<GhArtifact> = emptyList())

@Serializable
data class DispatchRequest(val ref: String, val inputs: Map<String, String>? = null)

/** API version 2026-03-10 returns the run created by a dispatch. */
@Serializable
data class DispatchResponse(
    @SerialName("workflow_run_id") val workflowRunId: Long = 0,
    @SerialName("run_url") val runUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GhReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)

@Serializable
data class GhRelease(
    val id: Long,
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("upload_url") val uploadUrl: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhReleaseAsset> = emptyList(),
)

@Serializable
data class CreateReleaseRequest(
    @SerialName("tag_name") val tagName: String,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    // Defaults match GitHub's, so only non-default values are sent (encodeDefaults = false).
    @SerialName("generate_release_notes") val generateReleaseNotes: Boolean = false,
)

@Serializable
data class GhPublicKey(@SerialName("key_id") val keyId: String, val key: String)

@Serializable
data class PutSecretRequest(@SerialName("encrypted_value") val encryptedValue: String, @SerialName("key_id") val keyId: String)

@Serializable
data class GhSecretInfo(val name: String, @SerialName("created_at") val createdAt: String? = null, @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class GhSecretsPage(@SerialName("total_count") val totalCount: Int = 0, val secrets: List<GhSecretInfo> = emptyList())

@Serializable
data class PutContentRequest(val message: String, val content: String, val branch: String? = null, val sha: String? = null)

@Serializable
data class PutContentResponse(val content: GhContent? = null, val commit: GhCommitRef? = null)
