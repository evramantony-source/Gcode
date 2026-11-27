package dev.gcode.github

/**
 * GitHub REST API constants, verified against docs.github.com on 2026-11-27.
 * The full client arrives in Phase 3.
 */
object GitHubApi {
    const val BASE_URL = "https://api.github.com"
    const val UPLOADS_URL = "https://uploads.github.com"

    /** Latest REST API version. It makes workflow dispatch return the new run id (HTTP 200). */
    const val API_VERSION = "2026-03-10"

    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    /** OAuth scopes requested at sign-in. `delete_repo` is requested later, only when needed. */
    val SIGN_IN_SCOPES = listOf("repo", "workflow", "read:org")

    fun defaultHeaders(token: String?, userAgent: String = "GCode-Android"): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", API_VERSION)
        put("User-Agent", userAgent)
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }
}
