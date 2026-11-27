package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiEndpointsTest {
    @Test
    fun `builds streaming url with sse`() {
        assertThat(GeminiEndpoints.streamGenerateContent("gemini-4-hydrogen")).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:streamGenerateContent?alt=sse",
        )
    }

    @Test
    fun `accepts models prefix`() {
        assertThat(GeminiEndpoints.countTokens("models/gemini-3.8-flash")).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:countTokens",
        )
    }

    @Test
    fun `rejects ids that would change the path`() {
        listOf("", "a/b", "a:b", "a b").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { GeminiEndpoints.generateContent(bad) }
        }
    }
}
