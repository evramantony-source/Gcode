package dev.gcode.agent.tools

import dev.gcode.agent.ToolEffect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A request shown to the user before a tool runs. */
sealed interface ApprovalRequest {
    val tool: String
    val title: String

    /** A file change, shown as a diff sheet with Accept / Reject / Edit. */
    data class FileChange(override val tool: String, override val title: String, val path: String, val before: String?, val after: String?, val diff: String) : ApprovalRequest

    /** Any other action. [dangerous] marks the always-confirm set (named target, no mode can skip it). */
    data class Action(override val tool: String, override val title: String, val detail: String, val dangerous: Boolean) : ApprovalRequest

    /** Sending a denylisted or git-ignored file to the model. */
    data class SecretAccess(override val tool: String, override val title: String, val path: String, val reason: String) : ApprovalRequest

    /** Plan mode: the agent's plan, to approve before it may change anything. */
    data class Plan(override val tool: String, override val title: String, val markdown: String) : ApprovalRequest
}

sealed interface ApprovalResult {
    data object Approved : ApprovalResult

    /** The user edited the proposed file content before accepting. */
    data class ApprovedWithEdit(val content: String) : ApprovalResult

    /** Plan approved; the session switches to [mode] (a PermissionMode id) to carry it out. */
    data class PlanApproved(val mode: String) : ApprovalResult

    data class Rejected(val reason: String?) : ApprovalResult
}

/** Shows approval UI. The app implements it with sheets and dialogs. */
fun interface UserPrompter {
    suspend fun ask(request: ApprovalRequest): ApprovalResult
}

/** The outcome of a tool call, sent back to the model as a functionResponse. */
data class ToolResult(
    val ok: Boolean,
    val response: JsonObject,
    /** One line for the tool card. */
    val summary: String,
    /** Optional detail for the card (e.g. a diff), not sent to the model. */
    val display: String? = null,
) {
    companion object {
        fun ok(summary: String, vararg fields: Pair<String, Any?>, display: String? = null) =
            ToolResult(true, jsonOf(fields.toList() + ("result" to summary).takeIf { fields.none { it.first == "result" } }.let { listOfNotNull(it) }), summary, display)

        fun error(message: String) = ToolResult(false, buildJsonObject { put("error", message) }, message)

        fun jsonOf(fields: List<Pair<String, Any?>>): JsonObject = buildJsonObject {
            fields.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is JsonElement -> put(k, v)
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
    }
}

/**
 * A prepared call: what to ask the user (if anything), and how to apply it. Splitting preparation from
 * execution lets file tools compute the exact change and show it as a diff before writing.
 */
class Prepared(
    val approval: ApprovalRequest?,
    val apply: suspend (ApprovalResult) -> ToolResult,
)

/** A function exposed to Gemini (ARCHITECTURE.md section 6.4). */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** The effect of this particular call (e.g. a force push is DANGEROUS). */
    suspend fun effect(args: JsonObject): ToolEffect

    suspend fun prepare(args: JsonObject, context: ToolContext): Prepared
}

/** Per-call context. */
class ToolContext(
    val callId: String?,
    val progress: suspend (String) -> Unit = {},
)

/** Thrown for invalid arguments; becomes an error response the model can correct. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.requireStr(key: String): String = str(key)?.takeIf { it.isNotEmpty() } ?: throw ToolArgumentException("Missing required argument '$key'")
fun JsonObject.requireStrAllowEmpty(key: String): String = str(key) ?: throw ToolArgumentException("Missing required argument '$key'")
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
fun JsonObject.strings(key: String): List<String> =
    this[key]?.let { runCatching { it.jsonArray.mapNotNull { e -> e.jsonPrimitive.contentOrNull } }.getOrNull() ?: listOfNotNull((it as? JsonPrimitive)?.contentOrNull) }.orEmpty()
