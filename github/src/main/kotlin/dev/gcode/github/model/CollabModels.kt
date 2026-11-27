package dev.gcode.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A branch end of a pull request. */
@Serializable
data class GhPullRef(val ref: String, val sha: String = "", val label: String? = null, val repo: GhPullRepo? = null)

@Serializable
data class GhPullRepo(@SerialName("full_name") val fullName: String)

@Serializable
data class GhPull(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    val draft: Boolean = false,
    val merged: Boolean = false,
    /** null while GitHub is still computing mergeability; retry later. */
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val user: GhOwner? = null,
    val head: GhPullRef,
    val base: GhPullRef,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("merged_at") val mergedAt: String? = null,
    val comments: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
)

@Serializable
data class CreatePullRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String? = null,
    val draft: Boolean = false,
    @SerialName("maintainer_can_modify") val maintainerCanModify: Boolean? = null,
)

@Serializable
data class MergePullRequest(
    @SerialName("commit_title") val commitTitle: String? = null,
    @SerialName("commit_message") val commitMessage: String? = null,
    /** Head SHA the merge must match (409 otherwise). */
    val sha: String? = null,
    @SerialName("merge_method") val mergeMethod: String = "merge",
)

@Serializable
data class MergeResult(val sha: String? = null, val merged: Boolean = false, val message: String = "")

@Serializable
data class GhPullFile(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
)

@Serializable
data class GhLabel(val name: String, val color: String? = null)

@Serializable
data class GhIssue(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    @SerialName("state_reason") val stateReason: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val user: GhOwner? = null,
    val labels: List<GhLabel> = emptyList(),
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** Present when this "issue" is a pull request (the issues list includes PRs). */
    @SerialName("pull_request") val pullRequest: JsonObject? = null,
) {
    val isPullRequest: Boolean get() = pullRequest != null
}

@Serializable
data class CreateIssueRequest(
    val title: String,
    val body: String? = null,
    val labels: List<String>? = null,
    val assignees: List<String>? = null,
)

@Serializable
data class UpdateIssueRequest(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    /** completed, not_planned, duplicate or reopened; ignored unless [state] changes. */
    @SerialName("state_reason") val stateReason: String? = null,
)

@Serializable
data class GhComment(
    val id: Long,
    val body: String = "",
    val user: GhOwner? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class CommentRequest(val body: String)
