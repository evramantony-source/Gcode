package dev.gcode.core.common

/**
 * Masks secrets (API keys, tokens) in text before it is logged or shown in diagnostics.
 * Known secret values are replaced exactly; well-known token shapes are masked by pattern.
 */
object SecretRedactor {
    const val MASK = "[REDACTED]"

    private val PATTERNS = listOf(
        // GitHub tokens: classic, fine-grained, OAuth, user-to-server, server-to-server, refresh.
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b"""),
        // Google API keys.
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
        // Authorization headers.
        Regex("""(?i)(authorization:\s*(?:bearer|token)\s+)\S+"""),
        Regex("""(?i)(x-goog-api-key:\s*)\S+"""),
    )

    fun redact(text: String, knownSecrets: Collection<String> = emptyList()): String {
        var result = text
        knownSecrets.filter { it.length >= MIN_SECRET_LENGTH }
            .sortedByDescending { it.length }
            .forEach { result = result.replace(it, MASK) }
        for (pattern in PATTERNS) {
            result = pattern.replace(result) { match ->
                // Keep a captured header prefix (group 1) if the pattern has one.
                val prefix = if (match.groups.size > 1) match.groups[1]?.value.orEmpty() else ""
                prefix + MASK
            }
        }
        return result
    }

    private const val MIN_SECRET_LENGTH = 6
}
