package dev.gcode.gemini.transport

import dev.gcode.gemini.model.GeminiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An error response from the Gemini API (`{"error": {code, message, status, details}}`). */
class GeminiApiException(
    val httpCode: Int,
    val status: String?,
    override val message: String,
    /** From `google.rpc.RetryInfo.retryDelay`, when the server sent one. */
    val retryDelayMillis: Long? = null,
) : RuntimeException(message) {

    val isRateLimited: Boolean get() = httpCode == 429 || status == "RESOURCE_EXHAUSTED"

    /** The quota for this model is zero on this key's tier: retrying will never succeed. */
    val isZeroQuota: Boolean get() = isRateLimited && ZERO_LIMIT.containsMatchIn(message)

    /** The model cannot be used with this key (missing, not allowed, or no quota at all). */
    val isModelUnavailable: Boolean get() =
        httpCode == 404 || status == "NOT_FOUND" || (httpCode == 403 && status == "PERMISSION_DENIED") || isZeroQuota

    val isInvalidKey: Boolean get() =
        (httpCode == 400 && message.contains("API key", ignoreCase = true)) ||
            (httpCode == 403 && message.contains("API key", ignoreCase = true)) || httpCode == 401

    val isInvalidArgument: Boolean get() = httpCode == 400 && status == "INVALID_ARGUMENT"

    val isServerError: Boolean get() = httpCode in 500..599

    companion object {
        private val ZERO_LIMIT = Regex("""limit:\s*0\b""")
        private val DURATION = Regex("""^(\d+(?:\.\d+)?)s$""")

        fun parse(httpCode: Int, body: String?): GeminiApiException {
            val error = runCatching { GeminiJson.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
                ?: return GeminiApiException(httpCode, null, body?.take(500)?.ifBlank { null } ?: "HTTP $httpCode")
            val status = error["status"]?.jsonPrimitive?.contentOrNull
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "HTTP $httpCode"
            val code = error["code"]?.jsonPrimitive?.intOrNull ?: httpCode
            val retry = (error["details"] as? JsonArray)?.firstNotNullOfOrNull { detail ->
                val obj = detail as? JsonObject ?: return@firstNotNullOfOrNull null
                val type = obj["@type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!type.endsWith("google.rpc.RetryInfo")) return@firstNotNullOfOrNull null
                obj["retryDelay"]?.jsonPrimitive?.contentOrNull?.let(::parseDuration)
            }
            return GeminiApiException(code, status, message, retry)
        }

        /** Parses protobuf JSON durations such as "17s" or "1.5s" into milliseconds. */
        fun parseDuration(value: String): Long? =
            DURATION.matchEntire(value.trim())?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }
}
