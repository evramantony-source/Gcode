package dev.gcode.mcp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private val server = MockWebServer()
    private lateinit var client: McpClient

    @Before
    fun setUp() {
        server.start()
        client = McpClient(OkHttpClient(), server.url("/mcp").toString(), headers = { mapOf("Authorization" to "Bearer t0k") })
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply { headers.forEach { addHeader(it.first, it.second) } }.body(body).build()

    private fun sse(vararg events: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "data: $it\n\n" }).build()

    private fun RecordedRequest.json(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(body!!.utf8()).jsonObject

    private val tools = """{"tools":[{"name":"search_docs","description":"Search","inputSchema":{"type":"object","properties":{"q":{"type":"string"},"region":{"type":"string","x-mcp-header":"Region"}}},"annotations":{"readOnlyHint":true}}]"""

    @Test
    fun `2026-07-28 server is stateless with meta and method headers`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"docs","version":"2.0"}}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":$tools}}"""))
        server.enqueue(sse("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""", """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Found 2 pages"}],"isError":false}}"""))

        val info = client.connect()
        assertThat(info.protocolVersion).isEqualTo("2026-07-28")
        assertThat(info.name).isEqualTo("docs")
        val discover = server.takeRequest()
        assertThat(discover.headers["MCP-Protocol-Version"]).isEqualTo("2026-07-28")
        assertThat(discover.headers["Mcp-Method"]).isEqualTo("server/discover")
        assertThat(discover.headers["Accept"]).isEqualTo("application/json, text/event-stream")
        assertThat(discover.headers["Authorization"]).isEqualTo("Bearer t0k")
        assertThat(discover.headers["Mcp-Session-Id"]).isNull()
        val meta = discover.json()["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertThat(meta[McpProtocol.META_VERSION]!!.jsonPrimitive.content).isEqualTo("2026-07-28")
        assertThat(meta[McpProtocol.META_CLIENT_INFO]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("GCode")

        val listed = client.listTools()
        assertThat(listed.single().annotations?.readOnlyHint).isTrue()
        assertThat(server.takeRequest().headers["Mcp-Method"]).isEqualTo("tools/list")

        val result = client.callTool("search_docs", buildJsonObject { put("q", "compose"); put("region", "eu") })
        assertThat(result).isEqualTo(McpCallResult("Found 2 pages", isError = false))
        val call = server.takeRequest()
        assertThat(call.headers["Mcp-Method"]).isEqualTo("tools/call")
        assertThat(call.headers["Mcp-Name"]).isEqualTo("search_docs")
        assertThat(call.headers["Mcp-Param-Region"]).isEqualTo("eu")
        assertThat(call.json()["params"]!!.jsonObject["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content).isEqualTo("compose")
    }

    @Test
    fun `2025-11-25 server gets initialize, a session id and SSE responses`() = runTest {
        // An older server rejects the modern request without a JSON-RPC body it recognises.
        server.enqueue(MockResponse.Builder().code(400).body("Bad Request: no valid session").build())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"legacy","version":"1"}}}""", 200, "Mcp-Session-Id" to "sess-42"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(sse("""{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"t1","inputSchema":{"type":"object"}}],"nextCursor":"p2"}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"tools":[{"name":"t2","inputSchema":{"type":"object"}}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"image","mimeType":"image/png","data":"AAAA"}],"structuredContent":{"ok":true},"isError":true}}"""))
        server.enqueue(MockResponse.Builder().code(204).build())

        val info = client.connect()
        assertThat(info.protocolVersion).isEqualTo("2025-11-25")
        server.takeRequest() // server/discover
        val init = server.takeRequest()
        assertThat(init.json()["method"]!!.jsonPrimitive.content).isEqualTo("initialize")
        assertThat(init.json()["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content).isEqualTo("2025-11-25")
        assertThat(init.headers["Mcp-Session-Id"]).isNull()
        val initialized = server.takeRequest()
        assertThat(initialized.json()["method"]!!.jsonPrimitive.content).isEqualTo("notifications/initialized")
        assertThat(initialized.headers["Mcp-Session-Id"]).isEqualTo("sess-42")
        assertThat(initialized.headers["MCP-Protocol-Version"]).isEqualTo("2025-11-25")

        assertThat(client.listTools().map { it.name }).containsExactly("t1", "t2").inOrder()
        val page1 = server.takeRequest()
        assertThat(page1.headers["Mcp-Session-Id"]).isEqualTo("sess-42")
        assertThat(page1.headers["Mcp-Method"]).isNull()
        assertThat(server.takeRequest().json()["params"]!!.jsonObject["cursor"]!!.jsonPrimitive.content).isEqualTo("p2")

        val result = client.callTool("t1", JsonObject(emptyMap()))
        assertThat(result.isError).isTrue()
        assertThat(result.text).isEqualTo("[image image/png, 3 bytes]")
        server.takeRequest()

        client.close()
        val delete = server.takeRequest()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.headers["Mcp-Session-Id"]).isEqualTo("sess-42")
    }

    @Test
    fun `unsupported version error picks a common legacy version`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2025-06-18"],"requested":"2026-07-28"}}}""", 400))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}"""))
        server.enqueue(MockResponse.Builder().code(202).build())
        assertThat(client.connect().protocolVersion).isEqualTo("2025-06-18")
        server.takeRequest()
        assertThat(server.takeRequest().json()["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content).isEqualTo("2025-06-18")
    }

    @Test
    fun `expired session is re-initialised once`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}""", 200, "Mcp-Session-Id" to "s1"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(404).build()) // session gone
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}""", 200, "Mcp-Session-Id" to "s2"))
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(json("""{"jsonrpc":"2.0","id":6,"result":{"tools":[]}}"""))
        client.connect()
        assertThat(client.listTools()).isEmpty()
        repeat(6) { server.takeRequest() }
        assertThat(server.takeRequest().headers["Mcp-Session-Id"]).isEqualTo("s2")
    }

    @Test
    fun `json-rpc errors surface with their code`() = runTest {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown tool: nope"}}"""))
        client.connect()
        val e = runCatching { client.callTool("nope", JsonObject(emptyMap())) }.exceptionOrNull() as McpException
        assertThat(e.code).isEqualTo(-32602)
        assertThat(e.message).contains("Unknown tool")
    }

    @Test
    fun `header values outside ascii are base64 encoded`() {
        assertThat(McpClient.headerValue("plain")).isEqualTo("plain")
        assertThat(McpClient.headerValue("café")).isEqualTo("=?base64?Y2Fmw6k=?=")
        assertThat(McpClient.contentText(buildJsonObject { put("structuredContent", buildJsonObject { put("n", 1) }) })).isEqualTo("""{"n":1}""")
    }
}
