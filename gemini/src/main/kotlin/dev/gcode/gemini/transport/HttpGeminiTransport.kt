package dev.gcode.gemini.transport

import dev.gcode.gemini.GeminiEndpoints
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.CountTokensRequest
import dev.gcode.gemini.model.CountTokensResponse
import dev.gcode.gemini.model.GeminiJson
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ListModelsResponse
import dev.gcode.gemini.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** generateContent-family REST transport over OkHttp (decision D1). */
class HttpGeminiTransport(
    private val client: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = GeminiEndpoints.BASE_URL,
) : GeminiTransport {

    private val versionRoot get() = "${baseUrl.trimEnd('/')}/${GeminiEndpoints.API_VERSION}"

    override suspend fun listModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = "$versionRoot/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = execute(request(url.toString()).get().build(), ListModelsResponse.serializer())
            models += page.models
            pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        return models
    }

    override suspend fun generateContent(model: String, request: GenerateContentRequest): GenerateContentResponse {
        val body = GeminiJson.encodeToString(GenerateContentRequest.serializer(), request).toRequestBody(JSON)
        val url = "$versionRoot/models/${GeminiEndpoints.normalizeModelId(model)}:generateContent"
        return execute(request(url).post(body).build(), GenerateContentResponse.serializer())
    }

    override suspend fun countTokens(model: String, contents: List<Content>): Int {
        val body = GeminiJson.encodeToString(CountTokensRequest.serializer(), CountTokensRequest(contents)).toRequestBody(JSON)
        val url = "$versionRoot/models/${GeminiEndpoints.normalizeModelId(model)}:countTokens"
        return execute(request(url).post(body).build(), CountTokensResponse.serializer()).totalTokens
    }

    override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> =
        channelFlow {
            val body = GeminiJson.encodeToString(GenerateContentRequest.serializer(), request).toRequestBody(JSON)
            val url = "$versionRoot/models/${GeminiEndpoints.normalizeModelId(model)}:streamGenerateContent?alt=sse"
            val call = client.newCall(request(url).post(body).header("Accept", "text/event-stream").build())
            val response = call.await()
            // A blocked read does not see coroutine cancellation, so cancel the HTTP call when the collector stops.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                response.use {
                    if (!it.isSuccessful) throw GeminiApiException.parse(it.code, it.body.string())
                    withContext(Dispatchers.IO) {
                        val reader = SseReader(it.body.source())
                        while (true) {
                            ensureActive()
                            val data = reader.next() ?: break
                            if (data.isBlank()) continue
                            // Some errors arrive inside the stream as {"error": {...}}.
                            if (data.trimStart().startsWith("{\"error\"")) throw GeminiApiException.parse(it.code, data)
                            send(GeminiJson.decodeFromString(GenerateContentResponse.serializer(), data))
                        }
                    }
                }
            } finally {
                watcher.cancel()
            }
        }

    private suspend fun request(url: String): Request.Builder {
        val key = apiKey() ?: throw GeminiApiException(401, "UNAUTHENTICATED", "No Gemini API key configured")
        return Request.Builder().url(url).header(GeminiEndpoints.API_KEY_HEADER, key)
    }

    private suspend fun <T> execute(request: Request, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw GeminiApiException.parse(response.code, text)
            GeminiJson.decodeFromString(serializer, text)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_PAGES = 20
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
}
