package dev.gcode.agent.extend

/**
 * Markdown with optional YAML-style frontmatter between `---` lines. Only the subset used by
 * commands and agents is supported: `key: value`, quoted values, `[a, b]` lists and `- item` lists.
 */
data class Frontmatter(val fields: Map<String, String>, val lists: Map<String, List<String>>, val body: String) {
    fun string(key: String): String? = fields[key]?.takeIf { it.isNotBlank() }

    /** A list field, also accepting a comma-separated string. */
    fun list(key: String): List<String>? = lists[key] ?: fields[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(text: String): Frontmatter {
            val normalized = text.replace("\r\n", "\n").removePrefix("﻿")
            if (!normalized.startsWith("---\n")) return Frontmatter(emptyMap(), emptyMap(), normalized.trim())
            val end = normalized.indexOf("\n---", 4)
            if (end < 0) return Frontmatter(emptyMap(), emptyMap(), normalized.trim())
            val header = normalized.substring(4, end)
            val body = normalized.substring(end + 4).removePrefix("\n").trim()
            val fields = linkedMapOf<String, String>()
            val lists = linkedMapOf<String, MutableList<String>>()
            var currentListKey: String? = null
            header.lines().forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
                val item = Regex("^\\s*-\\s+(.*)$").matchEntire(line)
                if (item != null && currentListKey != null) {
                    lists.getOrPut(currentListKey!!) { mutableListOf() } += unquote(item.groupValues[1])
                    return@forEach
                }
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                currentListKey = null
                when {
                    value.isEmpty() -> currentListKey = key
                    value.startsWith("[") && value.endsWith("]") -> {
                        // Kept as text too: "argument-hint: [focus]" is a string that looks like a list.
                        fields[key] = value
                        lists[key] = value.substring(1, value.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }.toMutableList()
                    }
                    else -> fields[key] = unquote(value)
                }
            }
            return Frontmatter(fields, lists, body)
        }

        private fun unquote(s: String): String =
            if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) s.substring(1, s.length - 1) else s
    }
}
