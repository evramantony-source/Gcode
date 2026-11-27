package dev.gcode.mcp

/**
 * MCP protocol versions the client understands, newest first (spec checked 2026-11-27).
 * The streamable HTTP client arrives in Phase 9.
 */
object McpProtocol {
    const val LATEST = "2026-07-28"
    val SUPPORTED = listOf(LATEST, "2025-11-25", "2025-06-18", "2025-03-26")

    /**
     * Picks the version to use after `initialize`: the server's version if we support it, otherwise null
     * (the client must disconnect).
     */
    fun negotiate(serverVersion: String): String? = serverVersion.takeIf { it in SUPPORTED }

    /** Protocol-level sessions (`Mcp-Session-Id`) were removed in 2026-07-28. */
    fun usesSessionHeader(version: String): Boolean = version != LATEST && version in SUPPORTED
}
