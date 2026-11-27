package dev.gcode.gemini.transport

import okio.BufferedSource

/**
 * Minimal server-sent-events reader: returns the `data` payload of each event.
 * Multi-line data is joined with '\n'; comments, `event:` and `id:` lines are ignored.
 */
class SseReader(private val source: BufferedSource) {
    /** Next event's data, or null at end of stream. A `[DONE]` sentinel also ends the stream. */
    fun next(): String? {
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: return if (hasData) data.toString().takeUnless { it == DONE } else null
            when {
                line.isEmpty() -> if (hasData) {
                    val payload = data.toString()
                    return if (payload == DONE) null else payload
                }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (hasData) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    hasData = true
                }
                else -> Unit // event:, id:, retry: are not used by the Gemini API
            }
        }
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}
