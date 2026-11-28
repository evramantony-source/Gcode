package dev.gcode.core.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * An MCP server the user added (ARCHITECTURE.md section 11). The auth header value is stored in the
 * SecretStore under `SecretKey.mcpHeader(id)`, never here.
 */
@Serializable
data class McpServerEntry(
    val id: String,
    val name: String,
    val url: String,
    /** Header the stored secret is sent in, e.g. Authorization; blank for none. */
    val headerName: String = "Authorization",
    val enabled: Boolean = true,
    /** Trusted servers' read-only tools run without approval. */
    val trusted: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(list: List<McpServerEntry>): String = json.encodeToString(ListSerializer(serializer()), list)
        fun decode(text: String?): List<McpServerEntry> = text?.let { runCatching { json.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() }.orEmpty()
    }
}
