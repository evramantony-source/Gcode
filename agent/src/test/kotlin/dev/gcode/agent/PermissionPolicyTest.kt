package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.permission.Decision
import dev.gcode.agent.permission.PermissionPolicy
import org.junit.Test

class PermissionPolicyTest {
    private val expected: Map<Pair<ToolEffect, PermissionMode>, Decision> = mapOf(
        (ToolEffect.READ to PermissionMode.ASK) to Decision.ALLOW,
        (ToolEffect.READ to PermissionMode.AUTO_ACCEPT_EDITS) to Decision.ALLOW,
        (ToolEffect.READ to PermissionMode.PLAN) to Decision.ALLOW,
        (ToolEffect.READ to PermissionMode.FULL_AUTO) to Decision.ALLOW,
        (ToolEffect.WORKSPACE_WRITE to PermissionMode.ASK) to Decision.ASK,
        (ToolEffect.WORKSPACE_WRITE to PermissionMode.AUTO_ACCEPT_EDITS) to Decision.ALLOW,
        (ToolEffect.WORKSPACE_WRITE to PermissionMode.PLAN) to Decision.DENY,
        (ToolEffect.WORKSPACE_WRITE to PermissionMode.FULL_AUTO) to Decision.ALLOW,
        (ToolEffect.GIT_LOCAL to PermissionMode.ASK) to Decision.ASK,
        (ToolEffect.GIT_LOCAL to PermissionMode.AUTO_ACCEPT_EDITS) to Decision.ASK,
        (ToolEffect.GIT_LOCAL to PermissionMode.PLAN) to Decision.DENY,
        (ToolEffect.GIT_LOCAL to PermissionMode.FULL_AUTO) to Decision.ALLOW,
        (ToolEffect.REMOTE to PermissionMode.ASK) to Decision.ASK,
        (ToolEffect.REMOTE to PermissionMode.AUTO_ACCEPT_EDITS) to Decision.ASK,
        (ToolEffect.REMOTE to PermissionMode.PLAN) to Decision.DENY,
        (ToolEffect.REMOTE to PermissionMode.FULL_AUTO) to Decision.ALLOW,
        (ToolEffect.DANGEROUS to PermissionMode.ASK) to Decision.CONFIRM,
        (ToolEffect.DANGEROUS to PermissionMode.AUTO_ACCEPT_EDITS) to Decision.CONFIRM,
        (ToolEffect.DANGEROUS to PermissionMode.PLAN) to Decision.DENY,
        (ToolEffect.DANGEROUS to PermissionMode.FULL_AUTO) to Decision.CONFIRM,
    )

    @Test
    fun `matrix matches the architecture for every effect and mode`() {
        for (effect in ToolEffect.entries) for (mode in PermissionMode.entries) {
            assertThat(PermissionPolicy.decide(effect, mode)).isEqualTo(expected.getValue(effect to mode))
        }
    }

    @Test
    fun `full auto never skips the always confirm set`() {
        assertThat(PermissionPolicy.decide(ToolEffect.DANGEROUS, PermissionMode.FULL_AUTO)).isEqualTo(Decision.CONFIRM)
    }

    @Test
    fun `plan mode only shows read tools`() {
        assertThat(ToolEffect.entries.filter { PermissionPolicy.visibleIn(it, PermissionMode.PLAN) }).containsExactly(ToolEffect.READ)
        assertThat(ToolEffect.entries.all { PermissionPolicy.visibleIn(it, PermissionMode.ASK) }).isTrue()
    }
}
