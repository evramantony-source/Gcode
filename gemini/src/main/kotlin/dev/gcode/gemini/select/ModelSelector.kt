package dev.gcode.gemini.select

/**
 * Chooses the active model (ARCHITECTURE.md section 5.2):
 * preferred (default gemini-4-hydrogen) -> newest Flash -> newest Flash-Lite,
 * skipping models that are unavailable for this key or cooling down after rate limits.
 */
class ModelSelector(private val health: ModelHealth) {

    data class Selection(val modelId: String, val isFallback: Boolean, val reason: String?)

    /** The fallback chain, without duplicates. Models missing from the catalog are dropped. */
    fun chain(catalog: ModelCatalog, preferred: String): List<String> {
        val ids = buildList {
            add(preferred)
            catalog.newest(ParsedModelId.Variant.FLASH)?.let(::add)
            catalog.newest(ParsedModelId.Variant.FLASH_LITE)?.let(::add)
        }.distinct()
        return ids.filter { catalog.contains(it) }
    }

    /** Candidates for auto-switching after rate limits: the chain first, then other agent models, newest first. */
    fun switchCandidates(catalog: ModelCatalog, preferred: String): List<String> {
        val rest = catalog.agentModels.map { it.id }
            .sortedWith(compareBy<String, ParsedModelId?>(nullsLast()) { ParsedModelId.parse(it) }.thenBy { it })
        return (chain(catalog, preferred) + rest).distinct()
    }

    fun select(catalog: ModelCatalog, preferred: String, now: Long): Selection? {
        val chain = chain(catalog, preferred)
        val usable = chain.firstOrNull { health.isUsable(it, now) }
            ?: return catalog.agentModels.firstOrNull { health.isUsable(it.id, now) }
                ?.let { Selection(it.id, isFallback = true, reason = "No model in the fallback chain is usable") }
        if (usable == preferred) return Selection(usable, isFallback = false, reason = null)
        val reason = when {
            !catalog.contains(preferred) -> "$preferred is not available for this API key"
            else -> health.reason(preferred, now) ?: "$preferred is unavailable"
        }
        return Selection(usable, isFallback = true, reason = reason)
    }
}

/** Per-model availability: unavailable for the key (24 h) or cooling down after rate limits. */
class ModelHealth {
    private data class Block(val until: Long, val reason: String)

    private val blocks = mutableMapOf<String, Block>()

    @Synchronized
    fun markUnavailable(model: String, now: Long, reason: String) {
        blocks[model] = Block(now + UNAVAILABLE_MS, reason)
    }

    @Synchronized
    fun markRateLimited(model: String, now: Long, cooldownMs: Long = COOLDOWN_MS) {
        blocks[model] = Block(now + cooldownMs, "$model is rate limited")
    }

    @Synchronized
    fun clear(model: String) {
        blocks.remove(model)
    }

    @Synchronized
    fun isUsable(model: String, now: Long): Boolean = (blocks[model]?.until ?: 0) <= now

    @Synchronized
    fun reason(model: String, now: Long): String? = blocks[model]?.takeIf { it.until > now }?.reason

    companion object {
        const val UNAVAILABLE_MS = 24 * 60 * 60 * 1000L
        const val COOLDOWN_MS = 10 * 60 * 1000L
    }
}
