package dev.gcode.github.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An error response from the GitHub REST API. */
class GitHubApiException(
    val status: Int,
    override val message: String,
    val documentationUrl: String? = null,
    /** Validation details from `errors[]` (422), joined for display. */
    val details: List<String> = emptyList(),
    /** Seconds to wait from `Retry-After` or the rate-limit reset. */
    val retryAfterSeconds: Long? = null,
    val rateLimited: Boolean = false,
    /** Scopes the endpoint accepts (`X-Accepted-OAuth-Scopes`) that the token lacks. */
    val missingScopes: Set<String> = emptySet(),
) : RuntimeException(buildString {
    append("GitHub API $status: $message")
    if (details.isNotEmpty()) append(" (").append(details.joinToString("; ")).append(')')
}) {
    val isUnauthorized: Boolean get() = status == 401
    val isNotFound: Boolean get() = status == 404
    val isValidation: Boolean get() = status == 422
    val isGone: Boolean get() = status == 410

    companion object {
        fun parse(status: Int, body: String?, retryAfterSeconds: Long?, rateLimited: Boolean, missingScopes: Set<String>): GitHubApiException {
            val obj = runCatching { GitHubJson.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: body?.take(300)?.ifBlank { null } ?: "HTTP $status"
            val doc = obj?.get("documentation_url")?.jsonPrimitive?.contentOrNull
            val details = (obj?.get("errors") as? JsonArray)?.mapNotNull { e ->
                when (e) {
                    is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
                        ?: listOfNotNull(e["resource"], e["field"], e["code"]).joinToString(" ") { it.jsonPrimitive.content }
                    else -> runCatching { e.jsonPrimitive.content }.getOrNull()
                }
            }.orEmpty()
            return GitHubApiException(status, message, doc, details, retryAfterSeconds, rateLimited, missingScopes)
        }
    }
}
