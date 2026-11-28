package dev.gcode.agent.tools.mcp

import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.Prepared
import dev.gcode.agent.tools.StaticEffect
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.mcp.McpClient
import dev.gcode.mcp.McpTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A configured MCP server (settings). Auth headers are kept in the SecretStore, not here. */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** Trusted servers' `readOnlyHint` tools run without approval; untrusted servers' tools always ask (spec: hints are untrusted). */
    val trusted: Boolean = false,
)

/**
 * An MCP tool exposed to Gemini as `mcp__<server>__<tool>`. Calls are REMOTE (approval in Ask and
 * Auto-accept modes) unless the server is trusted and marks the tool read-only.
 */
class McpToolAdapter(
    private val server: McpServerConfig,
    private val client: McpClient,
    private val tool: McpTool,
) : Tool, StaticEffect {
    override val name: String = functionName(server.name, tool.name)
    override val description: String = "[MCP ${server.name}] " + (tool.description ?: tool.title ?: tool.name).take(1_000)
    override val parameters: JsonObject = SchemaSanitizer.sanitize(tool.inputSchema)
    override val staticEffect: ToolEffect = if (server.trusted && tool.annotations?.readOnlyHint == true) ToolEffect.READ else ToolEffect.REMOTE

    override suspend fun effect(args: JsonObject): ToolEffect = staticEffect

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val approval = ApprovalRequest.Action(
            name,
            "Run ${tool.title ?: tool.name} on ${server.name}",
            "MCP server ${server.name} (${server.url})\nArguments: $args" + if (tool.annotations?.destructiveHint == true) "\nThe server marks this tool as destructive." else "",
            dangerous = false,
        )
        return Prepared(approval) {
            context.progress("Calling ${server.name}")
            val result = client.callTool(tool.name, args)
            if (result.isError) {
                ToolResult(false, buildJsonObject { put("error", result.text.ifBlank { "The tool reported an error" }) }, "${tool.name} failed")
            } else {
                ToolResult(true, buildJsonObject { put("result", result.text) }, "${tool.name}: ${result.text.lineSequence().firstOrNull().orEmpty().take(80)}")
            }
        }
    }

    companion object {
        /** Gemini function names: letters, digits, underscores; at most 64 characters. */
        fun functionName(server: String, tool: String): String {
            fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_').ifEmpty { "x" }
            val full = "mcp__${clean(server)}__${clean(tool)}"
            return if (full.length <= 64) full else full.take(55) + "_" + Integer.toHexString(full.hashCode()).take(8)
        }
    }
}

/**
 * Reduces an MCP tool's JSON Schema to the OpenAPI subset Gemini's `functionDeclarations.parameters` accepts:
 * type, description, properties, required, items, enum, format, nullable. Unions become their first non-null
 * branch; `$ref`, `additionalProperties` and other keywords are dropped.
 */
object SchemaSanitizer {
    private val KEEP = setOf("type", "description", "properties", "required", "items", "enum", "format", "nullable", "minimum", "maximum", "minItems", "maxItems")

    fun sanitize(schema: JsonObject): JsonObject {
        val cleaned = clean(schema) as? JsonObject ?: JsonObject(emptyMap())
        val withType = if (cleaned["type"] == null) JsonObject(cleaned + ("type" to JsonPrimitive("object"))) else cleaned
        return if ((withType["type"] as? JsonPrimitive)?.contentOrNull == "object" && withType["properties"] == null) {
            JsonObject(withType + ("properties" to JsonObject(emptyMap())))
        } else {
            withType
        }
    }

    private fun clean(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return element
        // anyOf/oneOf: take the first non-null branch; ["string","null"] types become nullable.
        (o["anyOf"] ?: o["oneOf"])?.let { union ->
            val branches = (union as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val nullable = branches.any { (it["type"] as? JsonPrimitive)?.contentOrNull == "null" }
            val first = branches.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull != "null" } ?: return JsonObject(mapOf("type" to JsonPrimitive("string")))
            val merged = clean(JsonObject(first + o.filterKeys { it == "description" })) as JsonObject
            return if (nullable) JsonObject(merged + ("nullable" to JsonPrimitive(true))) else merged
        }
        val out = linkedMapOf<String, JsonElement>()
        o.forEach { (key, value) ->
            if (key !in KEEP) return@forEach
            out[key] = when (key) {
                "type" -> when (value) {
                    is JsonArray -> {
                        val types = value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        if ("null" in types) out["nullable"] = JsonPrimitive(true)
                        JsonPrimitive(types.firstOrNull { it != "null" } ?: "string")
                    }
                    else -> value
                }
                "properties" -> JsonObject((value as? JsonObject).orEmpty().mapValues { (_, v) -> clean(v) })
                "items" -> clean(value)
                "enum" -> JsonArray((value as? JsonArray).orEmpty().filter { it is JsonPrimitive && it.isString })
                "format" -> value.takeIf { (it as? JsonPrimitive)?.contentOrNull in setOf("enum", "date-time", "int32", "int64", "float", "double") } ?: return@forEach
                else -> value
            }
        }
        if (out["enum"] != null && (out["enum"] as JsonArray).isEmpty()) out.remove("enum")
        return JsonObject(out)
    }
}
