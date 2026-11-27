package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.transport.SseReader
import okio.Buffer
import org.junit.Test

class SseReaderTest {
    private fun reader(text: String) = SseReader(Buffer().writeUtf8(text))

    @Test
    fun `reads data events and ignores comments and fields`() {
        val r = reader(": keep-alive\n\ndata: {\"a\":1}\n\nevent: x\nid: 2\ndata: {\"b\":2}\n\n")
        assertThat(r.next()).isEqualTo("{\"a\":1}")
        assertThat(r.next()).isEqualTo("{\"b\":2}")
        assertThat(r.next()).isNull()
    }

    @Test
    fun `joins multi line data and handles missing trailing blank line`() {
        val r = reader("data: line1\ndata: line2\n\ndata: last")
        assertThat(r.next()).isEqualTo("line1\nline2")
        assertThat(r.next()).isEqualTo("last")
        assertThat(r.next()).isNull()
    }

    @Test
    fun `done sentinel ends the stream`() {
        val r = reader("data: {}\n\ndata: [DONE]\n\ndata: {\"late\":true}\n\n")
        assertThat(r.next()).isEqualTo("{}")
        assertThat(r.next()).isNull()
    }
}
