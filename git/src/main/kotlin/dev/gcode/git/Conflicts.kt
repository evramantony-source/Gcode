package dev.gcode.git

/** One conflict in a file: our side, their side and (diff3 style) the common base. */
data class ConflictHunk(
    val index: Int,
    val ours: String,
    val theirs: String,
    val base: String?,
    val oursLabel: String,
    val theirsLabel: String,
)

sealed interface ConflictSegment {
    data class Text(val text: String) : ConflictSegment
    data class Hunk(val hunk: ConflictHunk) : ConflictSegment
}

/** How the user resolved one hunk. */
sealed interface Resolution {
    data object Ours : Resolution
    data object Theirs : Resolution

    /** Ours followed by theirs. */
    data object Both : Resolution

    /** Text written by the user or proposed by the AI and accepted. */
    data class Custom(val text: String) : Resolution
}

/**
 * Parses and resolves Git conflict markers (`<<<<<<<`, optional `|||||||` base, `=======`, `>>>>>>>`).
 * Line endings are kept exactly, so resolving every hunk with one side reproduces that side byte for byte.
 */
object Conflicts {
    private const val OURS = "<<<<<<<"
    private const val BASE = "|||||||"
    private const val SPLIT = "======="
    private const val THEIRS = ">>>>>>>"

    fun hasMarkers(content: String): Boolean = lines(content).any { it.startsWith(OURS) } && lines(content).any { it.startsWith(THEIRS) }

    fun parse(content: String): List<ConflictSegment> {
        val out = mutableListOf<ConflictSegment>()
        val text = StringBuilder()
        val all = lines(content)
        var i = 0
        var index = 0
        while (i < all.size) {
            val line = all[i]
            if (!isMarker(line, OURS)) {
                text.append(line)
                i++
                continue
            }
            // Find the end of this conflict; an unterminated block is kept as plain text.
            val split = (i + 1 until all.size).firstOrNull { isMarker(all[it], SPLIT) }
            val end = split?.let { s -> (s + 1 until all.size).firstOrNull { isMarker(all[it], THEIRS) } }
            if (split == null || end == null) {
                text.append(line)
                i++
                continue
            }
            if (text.isNotEmpty()) {
                out += ConflictSegment.Text(text.toString())
                text.clear()
            }
            val baseAt = (i + 1 until split).firstOrNull { isMarker(all[it], BASE) }
            val ours = all.subList(i + 1, baseAt ?: split).joinToString("")
            val base = baseAt?.let { all.subList(it + 1, split).joinToString("") }
            val theirs = all.subList(split + 1, end).joinToString("")
            out += ConflictSegment.Hunk(ConflictHunk(index++, ours, theirs, base, label(line, OURS), label(all[end], THEIRS)))
            i = end + 1
        }
        if (text.isNotEmpty()) out += ConflictSegment.Text(text.toString())
        return out
    }

    fun hunks(segments: List<ConflictSegment>): List<ConflictHunk> = segments.filterIsInstance<ConflictSegment.Hunk>().map { it.hunk }

    /** The file with every hunk replaced by its resolution. Throws if a hunk is unresolved. */
    fun resolve(segments: List<ConflictSegment>, resolutions: Map<Int, Resolution>): String = buildString {
        segments.forEach { segment ->
            when (segment) {
                is ConflictSegment.Text -> append(segment.text)
                is ConflictSegment.Hunk -> {
                    val h = segment.hunk
                    when (val r = resolutions[h.index] ?: throw IllegalStateException("Conflict ${h.index + 1} is not resolved")) {
                        Resolution.Ours -> append(h.ours)
                        Resolution.Theirs -> append(h.theirs)
                        Resolution.Both -> append(h.ours).append(if (h.ours.isEmpty() || h.ours.endsWith("\n")) "" else "\n").append(h.theirs)
                        is Resolution.Custom -> append(r.text).append(if (r.text.isEmpty() || r.text.endsWith("\n") || !h.ours.endsWith("\n")) "" else "\n")
                    }
                }
            }
        }
    }

    private fun isMarker(line: String, marker: String): Boolean {
        if (!line.startsWith(marker)) return false
        val rest = line.removePrefix(marker)
        // "=======" must be the whole line; the others may carry a label after a space.
        return if (marker == SPLIT) rest.isBlank() else rest.isEmpty() || rest[0] == ' ' || rest[0] == '\n' || rest[0] == '\r'
    }

    private fun label(line: String, marker: String) = line.removePrefix(marker).trim()

    /** Lines with their terminators. */
    private fun lines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val nl = content.indexOf('\n', start)
            if (nl < 0) {
                out += content.substring(start)
                break
            }
            out += content.substring(start, nl + 1)
            start = nl + 1
        }
        return out
    }
}
