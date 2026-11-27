package dev.gcode.app.ui.code

/** One row of a side-by-side diff. A null side is blank (pure addition or deletion). */
data class SplitRow(
    val leftNumber: Int?,
    val left: String?,
    val rightNumber: Int?,
    val right: String?,
    val kind: Kind,
) {
    enum class Kind { CONTEXT, CHANGED, ADDED, REMOVED, HUNK, FILE }
}

/** Turns a unified diff into side-by-side rows, pairing removed and added lines within each block. */
object SideBySide {
    private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

    fun rows(diff: String): List<SplitRow> {
        val out = mutableListOf<SplitRow>()
        var left = 0
        var right = 0
        val removed = mutableListOf<Pair<Int, String>>()
        val added = mutableListOf<Pair<Int, String>>()
        fun flush() {
            for (i in 0 until maxOf(removed.size, added.size)) {
                val r = removed.getOrNull(i)
                val a = added.getOrNull(i)
                val kind = when {
                    r != null && a != null -> SplitRow.Kind.CHANGED
                    r != null -> SplitRow.Kind.REMOVED
                    else -> SplitRow.Kind.ADDED
                }
                out += SplitRow(r?.first, r?.second, a?.first, a?.second, kind)
            }
            removed.clear()
            added.clear()
        }
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("diff --git") || line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("index ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") -> {
                    flush()
                    if (line.startsWith("+++ ")) out += SplitRow(null, null, null, line.removePrefix("+++ ").removePrefix("b/"), SplitRow.Kind.FILE)
                }
                HUNK.matches(line) -> {
                    flush()
                    val m = HUNK.matchEntire(line)!!
                    left = m.groupValues[1].toInt()
                    right = m.groupValues[2].toInt()
                    out += SplitRow(null, line, null, null, SplitRow.Kind.HUNK)
                }
                line.startsWith("-") -> removed += left++ to line.substring(1)
                line.startsWith("+") -> added += right++ to line.substring(1)
                line.startsWith(" ") -> {
                    flush()
                    out += SplitRow(left++, line.substring(1), right++, line.substring(1), SplitRow.Kind.CONTEXT)
                }
                line.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> flush()
            }
        }
        flush()
        return out
    }
}
