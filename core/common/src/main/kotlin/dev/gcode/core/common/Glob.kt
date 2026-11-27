package dev.gcode.core.common

/**
 * Glob patterns on '/'-separated relative paths: `*` (within a segment), `**` (any depth),
 * `?`, `{a,b}` alternatives and `[abc]` classes. A pattern without '/' matches the file name anywhere.
 */
class Glob(pattern: String) {
    private val regex: Regex = Regex(toRegex(pattern.trim().removePrefix("./")))

    fun matches(path: String): Boolean = regex.matches(path.removePrefix("./").trimStart('/'))

    companion object {
        fun toRegex(glob: String): String {
            val anchored = glob.contains('/')
            val sb = StringBuilder(if (anchored) "^" else "^(?:.*/)?")
            var i = 0
            var inGroup = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> {
                        // "**/" matches zero or more directories; a trailing "**" matches everything.
                        if (glob.getOrNull(i + 2) == '/') {
                            sb.append("(?:.*/)?")
                            i += 2
                        } else {
                            sb.append(".*")
                            i += 1
                        }
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '{' -> { sb.append("(?:"); inGroup++ }
                    c == '}' && inGroup > 0 -> { sb.append(")"); inGroup-- }
                    c == ',' && inGroup > 0 -> sb.append("|")
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end > i) {
                            sb.append('[').append(glob.substring(i + 1, end).replace("\\", "\\\\")).append(']')
                            i = end
                        } else {
                            sb.append("\\[")
                        }
                    }
                    "\\.^$+()|".contains(c) -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return sb.append("$").toString()
        }
    }
}
