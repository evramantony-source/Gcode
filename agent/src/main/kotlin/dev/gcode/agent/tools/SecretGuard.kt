package dev.gcode.agent.tools

import dev.gcode.core.common.Glob

/**
 * Decides which files may not be sent to the model without explicit approval (ARCHITECTURE.md section 7.4):
 * a configurable denylist plus git-ignored files. `.git` internals are always protected.
 */
class SecretGuard(
    denylist: List<String> = DEFAULT_DENYLIST,
    /** Returns true when the path is ignored by .gitignore rules (null when the workspace has none). */
    private val isIgnored: ((path: String, isDirectory: Boolean) -> Boolean)? = null,
) {
    private val globs = denylist.filter { it.isNotBlank() }.map { Glob(it) }

    /** Why the file is protected, or null when it may be read freely. */
    fun reason(path: String, isDirectory: Boolean = false): String? = when {
        path == ".git" || path.startsWith(".git/") -> "Git internals"
        globs.any { it.matches(path) } -> "matches the secrets denylist"
        isIgnored?.invoke(path, isDirectory) == true -> "ignored by .gitignore"
        else -> null
    }

    companion object {
        val DEFAULT_DENYLIST = listOf(
            ".env", ".env.*", "*.jks", "*.keystore", "*.p12", "*.pem", "*.key", "*.p8",
            "local.properties", "keystore.properties", "id_rsa*", "id_ed25519*", "credentials*.json", "google-services.json",
        )
    }
}
