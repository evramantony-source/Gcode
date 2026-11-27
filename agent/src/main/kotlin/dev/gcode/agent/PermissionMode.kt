package dev.gcode.agent

/**
 * Agent permission modes (ARCHITECTURE.md section 7.1). The full policy arrives in Phase 4.
 */
enum class PermissionMode(val id: String, val requiresOptIn: Boolean) {
    ASK("ask", requiresOptIn = false),
    AUTO_ACCEPT_EDITS("auto_accept_edits", requiresOptIn = false),
    PLAN("plan", requiresOptIn = false),
    FULL_AUTO("full_auto", requiresOptIn = true),
    ;

    companion object {
        val DEFAULT = ASK

        fun fromId(id: String?): PermissionMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What a tool call can change. Used by the permission policy and the parallel executor. */
enum class ToolEffect {
    READ,
    WORKSPACE_WRITE,
    GIT_LOCAL,
    REMOTE,
    DANGEROUS,
    ;

    /** Only read-only calls may run concurrently within one model turn. */
    val parallelSafe: Boolean get() = this == READ
}
