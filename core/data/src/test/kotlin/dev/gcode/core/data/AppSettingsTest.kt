package dev.gcode.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `defaults match the architecture`() {
        val settings = AppSettings()
        assertThat(settings.modelCacheHours).isEqualTo(24)
        assertThat(settings.compactionThresholdPercent).isEqualTo(80)
        assertThat(settings.theme).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test
    fun `rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { AppSettings(compactionThresholdPercent = 99) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings(modelCacheHours = 0) }
    }
}
