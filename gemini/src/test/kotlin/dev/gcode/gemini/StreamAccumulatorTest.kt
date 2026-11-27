package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.model.GeminiJson
import dev.gcode.gemini.model.GenerateContentResponse
import org.junit.Test

class StreamAccumulatorTest {
    private fun chunk(json: String) = GeminiJson.decodeFromString(GenerateContentResponse.serializer(), json)

    @Test
    fun `concatenates plain text and keeps usage and finish reason`() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12}}"""))
        val content = acc.content()
        assertThat(content.parts).hasSize(1)
        assertThat(content.text).isEqualTo("Hello")
        assertThat(acc.finishReason).isEqualTo("STOP")
        assertThat(acc.usage?.promptTokenCount).isEqualTo(10)
    }

    @Test
    fun `never merges parts that carry a thought signature`() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"Answer "}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"done","thoughtSignature":"SIG1"}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"","thoughtSignature":"SIG2"}]}}]}"""))
        val parts = acc.content().parts
        assertThat(parts).hasSize(3)
        assertThat(parts[1].thoughtSignature).isEqualTo("SIG1")
        assertThat(parts[2].text).isEmpty()
        assertThat(parts[2].thoughtSignature).isEqualTo("SIG2")
    }

    @Test
    fun `keeps function calls whole with signature and id, and thoughts separate`() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"plan","thought":true}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":" more","thought":true}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"a.kt"},"id":"c1"},"thoughtSignature":"S"},{"functionCall":{"name":"list_dir","args":{},"id":"c2"}}]}}]}"""))
        val content = acc.content()
        assertThat(content.parts).hasSize(3)
        assertThat(content.thoughtText).isEqualTo("plan more")
        assertThat(content.functionCalls.map { it.name }).containsExactly("read_file", "list_dir").inOrder()
        assertThat(content.functionCalls.first().id).isEqualTo("c1")
        assertThat(content.parts[1].thoughtSignature).isEqualTo("S")
    }

    @Test
    fun `raw part json survives serialization round trip`() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"f","args":{}},"thoughtSignature":"X","futureField":{"k":1}}]}}]}"""))
        val encoded = GeminiJson.encodeToString(dev.gcode.gemini.model.Content.serializer(), acc.content())
        assertThat(encoded).contains("\"thoughtSignature\":\"X\"")
        assertThat(encoded).contains("\"futureField\":{\"k\":1}")
    }
}
