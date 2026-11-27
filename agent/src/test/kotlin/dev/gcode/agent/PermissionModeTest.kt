package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionModeTest {
    @Test
    fun `unknown or missing id falls back to ask`() {
        assertThat(PermissionMode.fromId(null)).isEqualTo(PermissionMode.ASK)
        assertThat(PermissionMode.fromId("nope")).isEqualTo(PermissionMode.ASK)
    }

    @Test
    fun `round trips ids`() {
        PermissionMode.entries.forEach { assertThat(PermissionMode.fromId(it.id)).isEqualTo(it) }
    }

    @Test
    fun `only full auto needs explicit opt in`() {
        assertThat(PermissionMode.entries.filter { it.requiresOptIn }).containsExactly(PermissionMode.FULL_AUTO)
    }

    @Test
    fun `only reads are parallel safe`() {
        assertThat(ToolEffect.entries.filter { it.parallelSafe }).containsExactly(ToolEffect.READ)
    }
}
