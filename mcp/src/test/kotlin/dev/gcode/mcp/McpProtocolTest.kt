package dev.gcode.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpProtocolTest {
    @Test
    fun `accepts supported versions`() {
        assertThat(McpProtocol.negotiate("2026-07-28")).isEqualTo("2026-07-28")
        assertThat(McpProtocol.negotiate("2025-06-18")).isEqualTo("2025-06-18")
    }

    @Test
    fun `rejects unknown versions`() {
        assertThat(McpProtocol.negotiate("2024-11-05")).isNull()
    }

    @Test
    fun `session header only for older revisions`() {
        assertThat(McpProtocol.usesSessionHeader("2026-07-28")).isFalse()
        assertThat(McpProtocol.usesSessionHeader("2025-11-25")).isTrue()
    }
}
