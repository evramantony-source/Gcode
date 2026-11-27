package dev.gcode.gemini.select

import dev.gcode.gemini.model.ModelInfo

/** Parsed `gemini-<version>-<variant>[-<suffix>]` id, used to rank models. */
data class ParsedModelId(
    val id: String,
    val version: List<Int>,
    val variant: Variant,
    /** True for ids without a preview/exp/dated suffix. */
    val stable: Boolean,
) : Comparable<ParsedModelId> {
    enum class Variant { PRO, FLASH, FLASH_LITE, OTHER }

    /** Newer version first, then stable before preview. */
    override fun compareTo(other: ParsedModelId): Int {
        val v = compareVersions(other.version, version)
        if (v != 0) return v
        if (stable != other.stable) return if (stable) -1 else 1
        return id.compareTo(other.id)
    }

    companion object {
        private val PATTERN = Regex("""^gemini-(\d+(?:\.\d+)*)-(flash-lite|flash|pro)(?:-(.+))?$""")
        private val ALIAS = Regex("""-latest$""")

        fun parse(id: String): ParsedModelId? {
            if (ALIAS.containsMatchIn(id)) return null
            val m = PATTERN.matchEntire(id) ?: return null
            val version = m.groupValues[1].split('.').map { it.toInt() }
            val variant = when (m.groupValues[2]) {
                "flash-lite" -> Variant.FLASH_LITE
                "flash" -> Variant.FLASH
                "pro" -> Variant.PRO
                else -> Variant.OTHER
            }
            val suffix = m.groupValues[3]
            // A suffix such as -tts or -image means a media-only model, not a chat model.
            if (suffix.isNotEmpty() && ModelCatalog.isMediaOnly(id)) return null
            return ParsedModelId(id, version, variant, stable = suffix.isEmpty())
        }

        fun compareVersions(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/** The models a key can use, as returned by `models.list`. */
class ModelCatalog(models: List<ModelInfo>) {
    /** Every model that supports generateContent (shown in the picker). */
    val chatModels: List<ModelInfo> = models.filter { it.supportsGenerateContent }.sortedBy { it.id }

    /** Models suitable for the agent: text chat with function calling. */
    val agentModels: List<ModelInfo> = chatModels.filter { isAgentCapable(it.id) }

    fun find(id: String): ModelInfo? = chatModels.firstOrNull { it.id == id }

    fun contains(id: String): Boolean = find(id) != null

    fun newest(variant: ParsedModelId.Variant): String? =
        agentModels.mapNotNull { ParsedModelId.parse(it.id) }.filter { it.variant == variant }.minOrNull()?.id

    companion object {
        private val MEDIA_MARKERS = listOf(
            "-tts", "-image", "-live", "transcribe", "translate", "embedding", "native-audio", "lyria", "veo",
            "imagen", "genie", "omni", "robotics", "deep-research", "antigravity", "decision", "aqa", "nano-banana",
            "computer-use",
        )

        fun isMediaOnly(id: String): Boolean = MEDIA_MARKERS.any { id.contains(it) }

        fun isAgentCapable(id: String): Boolean = id.startsWith("gemini-") && !isMediaOnly(id)

        /** Display group for the picker. */
        fun family(id: String): String = when {
            id.startsWith("gemini-4") -> "Gemini 4"
            ParsedModelId.parse(id)?.variant == ParsedModelId.Variant.PRO -> "Pro"
            ParsedModelId.parse(id)?.variant == ParsedModelId.Variant.FLASH -> "Flash"
            ParsedModelId.parse(id)?.variant == ParsedModelId.Variant.FLASH_LITE -> "Flash-Lite"
            id.startsWith("gemma") -> "Gemma"
            else -> "Other"
        }
    }
}
