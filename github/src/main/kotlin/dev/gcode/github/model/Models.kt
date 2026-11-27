package dev.gcode.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GhUser(
    val id: Long,
    val login: String,
    val name: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val type: String? = null,
) {
    /** Commit email that keeps the user's real address private. */
    val noreplyEmail: String get() = "$id+$login@users.noreply.github.com"
}

@Serializable
data class GhOrg(
    val id: Long,
    val login: String,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GhOwner(val login: String, val id: Long = 0, val type: String? = null)

@Serializable
data class GhPermissions(val admin: Boolean = false, val push: Boolean = false, val pull: Boolean = false)

@Serializable
data class GhRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: GhOwner,
    val private: Boolean = false,
    val description: String? = null,
    val fork: Boolean = false,
    val archived: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("clone_url") val cloneUrl: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    val language: String? = null,
    val visibility: String? = null,
    val permissions: GhPermissions? = null,
)

@Serializable
data class GhSearchResult<T>(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("incomplete_results") val incompleteResults: Boolean = false,
    val items: List<T> = emptyList(),
)

@Serializable
data class CreateRepoRequest(
    val name: String,
    val description: String? = null,
    val private: Boolean = false,
    @SerialName("auto_init") val autoInit: Boolean = false,
    @SerialName("gitignore_template") val gitignoreTemplate: String? = null,
    @SerialName("license_template") val licenseTemplate: String? = null,
)

@Serializable
data class ForkRequest(
    val organization: String? = null,
    val name: String? = null,
    @SerialName("default_branch_only") val defaultBranchOnly: Boolean? = null,
)

@Serializable
data class UpdateRepoRequest(
    val private: Boolean? = null,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class GhLicense(val key: String, val name: String, @SerialName("spdx_id") val spdxId: String? = null)

@Serializable
data class GhBranch(val name: String, val commit: GhCommitRef, val protected: Boolean = false)

@Serializable
data class GhCommitRef(val sha: String, val url: String? = null)

@Serializable
data class GhContent(
    val type: String,
    val name: String,
    val path: String,
    val sha: String,
    val size: Long = 0,
    val encoding: String? = null,
    val content: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Int? = null,
)
