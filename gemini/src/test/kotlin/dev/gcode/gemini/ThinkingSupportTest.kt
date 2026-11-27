package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.select.ThinkingLevel
import dev.gcode.gemini.select.ThinkingSupport
import org.junit.Test

class ThinkingSupportTest {
    @Test
    fun `hydrogen supports xhigh, flash does not`() {
        assertThat(ThinkingSupport.optionsFor("gemini-4-hydrogen", true).levels).contains(ThinkingLevel.XHIGH)
        assertThat(ThinkingSupport.configFor("gemini-3.8-flash", true, ThinkingLevel.XHIGH, includeThoughts = false)).isNull()
        assertThat(ThinkingSupport.configFor("gemini-4-hydrogen", true, ThinkingLevel.XHIGH, false)?.thinkingLevel).isEqualTo("xhigh")
    }

    @Test
    fun `gemini 2_5 never gets a thinking level`() {
        val config = ThinkingSupport.configFor("gemini-2.5-flash-lite", true, ThinkingLevel.HIGH, includeThoughts = true)
        assertThat(config?.thinkingLevel).isNull()
        assertThat(config?.thinkingBudget).isNull()
        assertThat(config?.includeThoughts).isTrue()
    }

    @Test
    fun `rejected levels are dropped`() {
        val config = ThinkingSupport.configFor("gemini-4-neon", true, ThinkingLevel.LOW, false, rejected = setOf("gemini-4-neon:low"))
        assertThat(config).isNull()
    }

    @Test
    fun `unknown thinking models get low medium high`() {
        assertThat(ThinkingSupport.optionsFor("gemini-9-future", true).levels)
            .containsExactly(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
        assertThat(ThinkingSupport.optionsFor("gemma-4-31b-it", false).supported).isFalse()
    }
}
