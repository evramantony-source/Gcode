package dev.gcode.mcp

/**
 * MCP protocol versions the client understands, newest first (spec checked 2026-11-27).
 * 2026-07-28 is stateless (no initialize, no Mcp-Session-Id, `_meta` on every request);
 * the older versions use initialize + notifications/initialized and an optional session id.
 */
object McpProtocol {
    const val LATEST = "2026-07-28"
    val SUPPORTED = listOf(LATEST, "2025-11-25", "2025-06-18", "2025-03-26")
    val LEGACY = SUPPORTED.drop(1)

    /** The version to use after `initialize`: the server's version if we support it, otherwise null (disconnect). */
    fun negotiate(serverVersion: String): String? = serverVersion.takeIf { it in SUPPORTED }

    /** Protocol-level sessions (`Mcp-Session-Id`) were removed in 2026-07-28. */
    fun usesSessionHeader(version: String): Boolean = version != LATEST && version in SUPPORTED

    /** The newest legacy version both sides support, for the initialize fallback. */
    fun bestLegacy(serverSupported: List<String>): String? = LEGACY.firstOrNull { it in serverSupported }

    const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    // JSON-RPC error codes
    const val METHOD_NOT_FOUND = -32601
    const val HEADER_MISMATCH = -32020
    const val UNSUPPORTED_VERSION = -32022
}
