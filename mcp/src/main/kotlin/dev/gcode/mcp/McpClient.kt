package dev.gcode.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val McpJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class McpToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
data class McpTool(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    val annotations: McpToolAnnotations? = null,
)

data class McpServerInfo(val name: String?, val version: String?, val protocolVersion: String, val instructions: String?)

/** A tools/call result as text for the model, plus whether the tool reported an error. */
data class McpCallResult(val text: String, val isError: Boolean)

class McpException(val code: Int?, message: String, val httpStatus: Int? = null, val data: JsonElement? = null) : IOException(message)

/**
 * MCP client over the Streamable HTTP transport (ARCHITECTURE.md section 11). Tries the stateless
 * 2026-07-28 protocol first and falls back to `initialize` (2025-11-25 and earlier) when the server is older.
 */
class McpClient(
    private val http: OkHttpClient,
    private val endpoint: String,
    /** Extra headers, e.g. `Authorization`; read for every request so tokens can rotate. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
    private val clientName: String = "GCode",
    private val clientVersion: String = "1.0",
) {
    private val ids = AtomicLong(1)
    private val lock = Mutex()
    private val tools = mutableMapOf<String, McpTool>()

    @Volatile var protocolVersion: String? = null
        private set
    @Volatile private var sessionId: String? = null

    val isModern: Boolean get() = protocolVersion == McpProtocol.LATEST

    /** Discovers the protocol era and version (cached for this client). */
    suspend fun connect(): McpServerInfo = lock.withLock { connectLocked() }

    private suspend fun connectLocked(): McpServerInfo {
        val modern = try {
            val result = post("server/discover", JsonObject(emptyMap()), McpProtocol.LATEST)
            protocolVersion = McpProtocol.LATEST
            val info = (result["serverInfo"] ?: (result["_meta"] as? JsonObject)?.get(McpProtocol.META_SERVER_INFO)) as? JsonObject
            McpServerInfo(info.str("name"), info.str("version"), McpProtocol.LATEST, result.str("instructions"))
        } catch (e: McpException) {
            when {
                // A modern server that does not speak our version tells us which ones it does.
                e.code == McpProtocol.UNSUPPORTED_VERSION -> {
                    val supported = ((e.data as? JsonObject)?.get("supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    return initialize(McpProtocol.bestLegacy(supported) ?: throw McpException(e.code, "No common MCP version (server supports ${supported.joinToString()})"))
                }
                e.code == McpProtocol.HEADER_MISMATCH -> throw e
                else -> null
            }
        }
        return modern ?: initialize(McpProtocol.LEGACY.first())
    }

    /** Legacy lifecycle: initialize → (session id) → notifications/initialized. */
    private suspend fun initialize(version: String): McpServerInfo {
        sessionId = null
        val params = buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        }
        val (result, response) = postLegacy("initialize", params, null, captureSession = true)
        val serverVersion = result.str("protocolVersion") ?: version
        val negotiated = McpProtocol.negotiate(serverVersion)?.takeIf { it != McpProtocol.LATEST }
            ?: throw McpException(null, "The server answered with MCP $serverVersion, which GCode does not support")
        protocolVersion = negotiated
        sessionId = response
        notify("notifications/initialized")
        val info = result["serverInfo"] as? JsonObject
        return McpServerInfo(info.str("name"), info.str("version"), negotiated, result.str("instructions"))
    }

    suspend fun listTools(): List<McpTool> {
        ensureConnected()
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            (result["tools"] as? JsonArray)?.forEach { out += McpJson.decodeFromJsonElement(McpTool.serializer(), it) }
            cursor = result.str("nextCursor")
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        synchronized(tools) { tools.clear(); out.forEach { tools[it.name] = it } }
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        ensureConnected()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val schema = synchronized(tools) { tools[name] }?.inputSchema
        val result = request("tools/call", params, name, paramHeaders(schema, arguments))
        if (result.str("resultType") == "input_required") {
            return McpCallResult("The server asked for user input (elicitation or sampling), which GCode does not support yet.", isError = true)
        }
        return McpCallResult(contentText(result), (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Ends a legacy session (DELETE); stateless servers have nothing to close. */
    suspend fun close() {
        val session = sessionId ?: return
        sessionId = null
        runCatching {
            val request = Request.Builder().url(endpoint).delete().header(SESSION, session)
                .apply { protocolVersion?.let { header(VERSION, it) }; headers().forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(request).await().close()
        }
    }

    private suspend fun ensureConnected() {
        if (protocolVersion == null) connect()
    }

    private suspend fun request(method: String, params: JsonObject, name: String? = null, extraHeaders: Map<String, String> = emptyMap()): JsonObject {
        val version = protocolVersion ?: error("not connected")
        if (version == McpProtocol.LATEST) return post(method, params, version, name, extraHeaders)
        return try {
            postLegacy(method, params, version).first
        } catch (e: McpException) {
            // 404 with a session id: the session ended; start a new one once.
            if (e.httpStatus == 404 && sessionId != null) {
                lock.withLock { initialize(version) }
                postLegacy(method, params, protocolVersion).first
            } else {
                throw e
            }
        }
    }

    /** 2026-07-28: every request carries `_meta` and the method/name headers. */
    private suspend fun post(method: String, params: JsonObject, version: String, name: String? = null, extraHeaders: Map<String, String> = emptyMap()): JsonObject {
        val meta = buildJsonObject {
            put(McpProtocol.META_VERSION, version)
            put(McpProtocol.META_CAPABILITIES, JsonObject(emptyMap()))
            put(McpProtocol.META_CLIENT_INFO, buildJsonObject { put("name", clientName); put("version", clientVersion) })
        }
        val withMeta = JsonObject(params + ("_meta" to JsonObject(((params["_meta"] as? JsonObject).orEmpty()) + meta)))
        val h = mutableMapOf(VERSION to version, METHOD to method)
        name?.let { h[NAME] = headerValue(it) }
        h += extraHeaders
        return send(method, withMeta, h).first
    }

    private suspend fun postLegacy(method: String, params: JsonObject, version: String?, captureSession: Boolean = false): Pair<JsonObject, String?> {
        val h = mutableMapOf<String, String>()
        version?.let { h[VERSION] = it }
        sessionId?.let { h[SESSION] = it }
        val (result, response) = send(method, params, h)
        return result to (if (captureSession) response else null)
    }

    private suspend fun notify(method: String) {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }
        val request = baseRequest(body).apply {
            protocolVersion?.let { header(VERSION, it) }
            sessionId?.let { header(SESSION, it) }
        }.build()
        http.newCall(request).await().use { r ->
            if (!r.isSuccessful) throw McpException(null, "$method failed: HTTP ${r.code}", r.code)
        }
    }

    /** Sends one JSON-RPC request; returns the result and the response's session id header. */
    private suspend fun send(method: String, params: JsonObject, headers: Map<String, String>): Pair<JsonObject, String?> {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val request = baseRequest(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(request).await().use { r ->
            val type = r.body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            val message: JsonObject? = withContext(Dispatchers.IO) {
                when {
                    type == "text/event-stream" -> readSse(r, id)
                    else -> r.body.string().takeIf { it.isNotBlank() }?.let { runCatching { McpJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                }
            }
            val error = message?.get("error") as? JsonObject
            if (error != null) {
                throw McpException((error["code"] as? JsonPrimitive)?.intOrNull, error.str("message") ?: "MCP error", r.code, error["data"])
            }
            if (!r.isSuccessful) throw McpException(null, "$method: HTTP ${r.code}", r.code)
            val result = message?.get("result") as? JsonObject ?: throw McpException(null, "$method: no result in the response", r.code)
            result to r.header(SESSION)
        }
    }

    private suspend fun baseRequest(body: JsonObject): Request.Builder = Request.Builder().url(endpoint)
        .post(McpJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
        .header("Accept", "application/json, text/event-stream")
        .header("User-Agent", "$clientName/$clientVersion")
        .apply { headers().forEach { (k, v) -> header(k, v) } }

    /** Reads SSE events until the JSON-RPC response with [id]; notifications and other messages are skipped. */
    private fun readSse(response: Response, id: Long): JsonObject? {
        val source = response.body.source()
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                line.isEmpty() -> {
                    val payload = data.toString().trim()
                    data.clear()
                    if (payload.isEmpty()) continue
                    val message = runCatching { McpJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    val messageId = (message["id"] as? JsonPrimitive)?.contentOrNull
                    if (messageId == id.toString() && (message.containsKey("result") || message.containsKey("error"))) return message
                }
            }
        }
        return null
    }

    companion object {
        const val VERSION = "MCP-Protocol-Version"
        const val SESSION = "Mcp-Session-Id"
        const val METHOD = "Mcp-Method"
        const val NAME = "Mcp-Name"
        private const val MAX_PAGES = 20
        private val JSON = "application/json".toMediaType()

        /** Plain ASCII header values go as they are; others use the `=?base64?…?=` form. */
        fun headerValue(value: String): String =
            if (value.all { it.code in 0x20..0x7E } && !value.startsWith("=?base64?")) value
            else "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="

        /** `Mcp-Param-{Name}` headers for arguments whose schema property has `x-mcp-header`. */
        fun paramHeaders(schema: JsonObject?, arguments: JsonObject): Map<String, String> {
            val properties = schema?.get("properties") as? JsonObject ?: return emptyMap()
            return properties.mapNotNull { (key, prop) ->
                val headerName = ((prop as? JsonObject)?.get("x-mcp-header") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val value = arguments[key]?.takeIf { it !is JsonNull } ?: return@mapNotNull null
                val text = (value as? JsonPrimitive)?.contentOrNull ?: McpJson.encodeToString(JsonElement.serializer(), value)
                "Mcp-Param-$headerName" to headerValue(text)
            }.toMap()
        }

        /** Text for the model: text parts, placeholders for media, resource text, then structured content. */
        fun contentText(result: JsonObject): String {
            val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                when (o.str("type")) {
                    "text" -> o.str("text")
                    "image", "audio" -> "[${o.str("type")} ${o.str("mimeType").orEmpty()}, ${(o.str("data")?.length ?: 0) * 3 / 4} bytes]"
                    "resource_link" -> "[resource ${o.str("name") ?: ""} ${o.str("uri").orEmpty()}]".replace("  ", " ")
                    "resource" -> (o["resource"] as? JsonObject)?.let { r -> r.str("text") ?: "[resource ${r.str("uri").orEmpty()} ${r.str("mimeType").orEmpty()}]" }
                    else -> null
                }
            }
            val structured = result["structuredContent"]?.takeIf { it !is JsonNull }
            return when {
                parts.isNotEmpty() -> parts.joinToString("\n")
                structured != null -> McpJson.encodeToString(JsonElement.serializer(), structured)
                else -> ""
            }
        }
    }
}

private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        },
    )
}
