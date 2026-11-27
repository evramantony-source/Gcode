package dev.gcode.agent.web

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.web.WebFetchTool
import dev.gcode.agent.tools.web.WebSearchTool
import dev.gcode.gemini.model.GeminiJson
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WebToolsTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private suspend fun WebFetchTool.fetch(args: JsonObject): ToolResult = prepare(args, ToolContext("c")).apply(ApprovalResult.Approved)
    private fun ToolResult.field(key: String) = response[key]?.jsonPrimitive?.content

    private val html = """
        <html><head><title>Release notes</title><script>var x = 1;</script><style>p{}</style></head>
        <body><nav><a href="/">Home</a> | <a href="/docs">Docs</a></nav>
        <main>
          <h1>Version 2.0</h1>
          <p>Faster <b>builds</b> and   fewer bugs.</p>
          <ul><li>New API</li><li>Removed <code>old()</code></li></ul>
          <pre><code>val x = 1
            indented()</code></pre>
          <table><tr><th>Name</th><th>Value</th></tr><tr><td>minSdk</td><td>26</td></tr></table>
        </main>
        <footer>Copyright</footer></body></html>
    """.trimIndent()

    @Test
    fun `html becomes readable text`() {
        val text = WebFetchTool.htmlToText(html, "https://example.com/")
        assertThat(text).contains("# Version 2.0")
        assertThat(text).contains("Faster builds and fewer bugs.")
        assertThat(text).contains("- New API")
        assertThat(text).contains("- Removed old()")
        assertThat(text).contains("```\nval x = 1\n    indented()\n```")
        assertThat(text).contains("| minSdk | 26")
        assertThat(text).doesNotContain("var x")
        assertThat(text).doesNotContain("Home")
        assertThat(text).doesNotContain("Copyright")
        assertThat(WebFetchTool.htmlToText(html, "https://example.com/", selector = "ul")).isEqualTo("- New API\n- Removed old()")
    }

    @Test
    fun `fetches a page and cuts long text`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(html).build())
        val tool = WebFetchTool(OkHttpClient(), allowPrivateHosts = true)
        val result = tool.fetch(buildJsonObject { put("url", server.url("/notes").toString()); put("max_chars", 500) })
        assertThat(result.ok).isTrue()
        assertThat(result.field("title")).isEqualTo("Release notes")
        assertThat(result.field("text")).startsWith("# Version 2.0")
        assertThat(server.takeRequest().headers["User-Agent"]).startsWith("GCode/")

        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/plain").body("x".repeat(2_000)).build())
        val long = tool.fetch(buildJsonObject { put("url", server.url("/big.txt").toString()); put("max_chars", 500) })
        assertThat(long.field("text")).hasLength(500)
        assertThat(long.field("truncated")).contains("2000")
    }

    @Test
    fun `refuses binaries, errors, other schemes and private networks`() = runTest {
        val tool = WebFetchTool(OkHttpClient(), allowPrivateHosts = true)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("PNG").build())
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { tool.fetch(buildJsonObject { put("url", server.url("/a.png").toString()) }) } }
        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("nope").build())
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { tool.fetch(buildJsonObject { put("url", server.url("/missing").toString()) }) } }
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { tool.fetch(buildJsonObject { put("url", "file:///etc/passwd") }) } }

        val strict = WebFetchTool(OkHttpClient(), resolve = { listOf(InetAddress.getByName("192.168.1.10")) })
        val e = assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { strict.fetch(buildJsonObject { put("url", "http://router.example/admin") }) } }
        assertThat(e.message).contains("private network")
    }

    @Test
    fun `private address ranges`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fd12::1", "fe80::1")
            .forEach { assertWithMessage(it).that(WebFetchTool.isPrivate(InetAddress.getByName(it))).isTrue() }
        listOf("8.8.8.8", "140.82.112.3", "100.128.0.1", "2001:4860:4860::8888")
            .forEach { assertWithMessage(it).that(WebFetchTool.isPrivate(InetAddress.getByName(it))).isFalse() }
    }

    @Test
    fun `web search uses google search grounding and returns sources`() = runTest {
        val requests = mutableListOf<Pair<String, GenerateContentRequest>>()
        val grounding = GeminiJson.parseToJsonElement(
            """{"webSearchQueries":["jsoup latest version"],
                "groundingChunks":[{"web":{"uri":"https://jsoup.org/news/","title":"jsoup news"}},
                                   {"web":{"uri":"https://jsoup.org/news/","title":"dup"}},
                                   {"web":{"uri":"https://mvnrepository.com/artifact/org.jsoup/jsoup","title":"Maven"}}]}""",
        ).jsonObject
        val transport = object : GeminiTransport {
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> = error("unused")
            override suspend fun generateContent(model: String, request: GenerateContentRequest): GenerateContentResponse {
                requests += model to request
                return GenerateContentResponse(listOf(Candidate(Content.model(Part.text("The latest jsoup is 1.24.1.")), "STOP", grounding)))
            }
            override suspend fun countTokens(model: String, contents: List<Content>) = 0
        }
        val result = WebSearchTool(transport) { "gemini-test" }.prepare(buildJsonObject { put("query", "jsoup latest version") }, ToolContext("c")).apply(ApprovalResult.Approved)
        assertThat(requests.single().first).isEqualTo("gemini-test")
        assertThat(requests.single().second.tools!!.single().googleSearch).isEqualTo(JsonObject(emptyMap()))
        assertThat(result.field("answer")).isEqualTo("The latest jsoup is 1.24.1.")
        assertThat(result.field("sources")).isEqualTo("1. jsoup news — https://jsoup.org/news/\n2. Maven — https://mvnrepository.com/artifact/org.jsoup/jsoup")
        assertThat(result.field("search_queries")).isEqualTo("jsoup latest version")
        assertThat(result.summary).startsWith("2 sources")
    }
}
