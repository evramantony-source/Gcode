package dev.gcode.agent.permission

import dev.gcode.agent.PermissionMode
import dev.gcode.agent.ToolEffect

/** What the executor must do before running a tool call. */
enum class Decision {
    /** Run without asking. */
    ALLOW,

    /** Ask the user (diff sheet for file changes, approve/reject otherwise). */
    ASK,

    /** Always-confirm dialog that names the target; no mode can skip it. */
    CONFIRM,

    /** Not available in this mode (Plan mode is read-only). */
    DENY,
}

/**
 * The permission matrix (ARCHITECTURE.md section 7.1). Pure, so it is exhaustively unit-tested.
 * Secret-file access is checked separately and always needs confirmation.
 */
object PermissionPolicy {
    fun decide(effect: ToolEffect, mode: PermissionMode): Decision = when (effect) {
        ToolEffect.READ -> Decision.ALLOW
        ToolEffect.WORKSPACE_WRITE -> when (mode) {
            PermissionMode.ASK -> Decision.ASK
            PermissionMode.AUTO_ACCEPT_EDITS, PermissionMode.FULL_AUTO -> Decision.ALLOW
            PermissionMode.PLAN -> Decision.DENY
        }
        ToolEffect.GIT_LOCAL, ToolEffect.REMOTE -> when (mode) {
            PermissionMode.ASK, PermissionMode.AUTO_ACCEPT_EDITS -> Decision.ASK
            PermissionMode.FULL_AUTO -> Decision.ALLOW
            PermissionMode.PLAN -> Decision.DENY
        }
        ToolEffect.DANGEROUS -> if (mode == PermissionMode.PLAN) Decision.DENY else Decision.CONFIRM
    }

    /** Tools offered to the model in a mode: Plan mode sees only read-only tools (plus planning tools). */
    fun visibleIn(effect: ToolEffect, mode: PermissionMode): Boolean = mode != PermissionMode.PLAN || effect == ToolEffect.READ
}
