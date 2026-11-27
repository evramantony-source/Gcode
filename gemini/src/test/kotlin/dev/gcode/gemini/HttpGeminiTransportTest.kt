package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.transport.GeminiApiException
import dev.gcode.gemini.transport.HttpGeminiTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpGeminiTransportTest {
    private val server = MockWebServer()
    private lateinit var transport: HttpGeminiTransport

    @Before
    fun setUp() {
        server.start()
        transport = HttpGeminiTransport(OkHttpClient(), apiKey = { "test-key" }, baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `lists all model pages with the api key header`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"models":[{"name":"models/gemini-4-hydrogen","supportedGenerationMethods":["generateContent"],"inputTokenLimit":1048576,"thinking":true}],"nextPageToken":"p2"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}]}""").build())
        val models = transport.listModels()
        assertThat(models.map { it.id }).containsExactly("gemini-4-hydrogen", "gemini-3.8-flash").inOrder()
        val first = server.takeRequest()
        assertThat(first.headers["x-goog-api-key"]).isEqualTo("test-key")
        assertThat(first.url.encodedPath).isEqualTo("/v1beta/models")
        assertThat(server.takeRequest().url.queryParameter("pageToken")).isEqualTo("p2")
    }

    @Test
    fun `streams sse chunks from streamGenerateContent`() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hi\"}]}}]}\r\n\r\n" +
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\" there\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"totalTokenCount\":5}}\r\n\r\n"
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(sse).build())
        val chunks = transport.streamGenerateContent("gemini-4-hydrogen", GenerateContentRequest(listOf(Content.user("hello")))).toList()
        assertThat(chunks).hasSize(2)
        assertThat(chunks[1].usageMetadata?.promptTokenCount).isEqualTo(3)
        val recorded = server.takeRequest()
        assertThat(recorded.url.encodedPath).isEqualTo("/v1beta/models/gemini-4-hydrogen:streamGenerateContent")
        assertThat(recorded.url.queryParameter("alt")).isEqualTo("sse")
        assertThat(recorded.body?.utf8()).contains("\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]")
    }

    @Test
    fun `parses api errors including retry info`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(429).body(
                """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"17s"}]}}""",
            ).build(),
        )
        val error = runCatching { transport.streamGenerateContent("m", GenerateContentRequest(listOf(Content.user("x")))).toList() }
            .exceptionOrNull() as GeminiApiException
        assertThat(error.isRateLimited).isTrue()
        assertThat(error.retryDelayMillis).isEqualTo(17_000)
        assertThat(error.isZeroQuota).isFalse()
    }

    @Test
    fun `counts tokens`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"totalTokens":42}""").build())
        assertThat(transport.countTokens("gemini-3.8-flash", listOf(Content.user("hello")))).isEqualTo(42)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1beta/models/gemini-3.8-flash:countTokens")
    }
}
