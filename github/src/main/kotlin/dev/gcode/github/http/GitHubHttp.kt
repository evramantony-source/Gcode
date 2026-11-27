package dev.gcode.github.http

import dev.gcode.github.GitHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Primary rate-limit state from the last response. */
data class RateLimit(val limit: Int, val remaining: Int, val resetEpochSeconds: Long)

/** Raw response, for callers that need headers. */
data class GitHubResponse(val code: Int, val body: String, val headers: Headers)

/**
 * Low-level GitHub REST transport (ARCHITECTURE.md section 8.2): auth and version headers,
 * Link-header pagination, ETag conditional requests (a 304 does not count against the rate limit),
 * primary/secondary rate-limit handling, and error parsing.
 */
class GitHubHttp(
    private val client: OkHttpClient,
    private val token: suspend () -> String?,
    private val baseUrl: String = GitHubApi.BASE_URL,
    private val userAgent: String = "GCode-Android",
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private data class Cached(val etag: String, val body: String)

    private val etagCache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MAX_CACHE
    }
    private val _rateLimit = MutableStateFlow<RateLimit?>(null)
    val rateLimit: StateFlow<RateLimit?> = _rateLimit.asStateFlow()

    /** Scopes of the current classic/OAuth token, from the last `X-OAuth-Scopes` header (null for fine-grained tokens). */
    @Volatile
    var lastOAuthScopes: Set<String>? = null
        private set

    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val base = if (path.startsWith("http")) path.toHttpUrl() else (baseUrl.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl()
        return base.newBuilder().apply { query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }.build()
    }

    suspend fun <T> get(path: String, serializer: KSerializer<T>, query: Map<String, String?> = emptyMap(), useEtag: Boolean = true): T =
        decode(send("GET", url(path, query), null, useEtag = useEtag).body, serializer)

    /** Follows `Link: rel="next"` up to [maxPages] pages of [perPage] items. */
    suspend fun <T> getPaged(
        path: String,
        serializer: KSerializer<List<T>>,
        query: Map<String, String?> = emptyMap(),
        perPage: Int = 100,
        maxPages: Int = 10,
    ): List<T> {
        val out = mutableListOf<T>()
        var next: HttpUrl? = url(path, query + ("per_page" to perPage.toString()))
        var pages = 0
        while (next != null && pages < maxPages) {
            val response = send("GET", next, null)
            out += decode(response.body, serializer)
            next = nextLink(response.headers["Link"])
            pages++
        }
        return out
    }

    suspend fun <B, T> send(method: String, path: String, body: B, bodySerializer: KSerializer<B>, responseSerializer: KSerializer<T>): T {
        val json = GitHubJson.encodeToString(bodySerializer, body).toRequestBody(JSON)
        return decode(send(method, url(path), json).body, responseSerializer)
    }

    suspend fun <B> sendNoContent(method: String, path: String, body: B?, bodySerializer: KSerializer<B>?): GitHubResponse {
        val json = if (body != null && bodySerializer != null) GitHubJson.encodeToString(bodySerializer, body).toRequestBody(JSON) else null
        return send(method, url(path), json ?: if (method == "PUT" || method == "POST") EMPTY_JSON else null)
    }

    suspend fun delete(path: String): GitHubResponse = send("DELETE", url(path), null)

    /**
     * Performs the request with rate-limit aware retries. Returns the body text for 2xx (and the cached
     * body for 304). Throws [GitHubApiException] for other statuses.
     */
    suspend fun send(
        method: String,
        url: HttpUrl,
        body: RequestBody?,
        accept: String = "application/vnd.github+json",
        useEtag: Boolean = method == "GET",
        followRedirects: Boolean = true,
    ): GitHubResponse {
        var attempt = 0
        while (true) {
            val key = url.toString()
            val cached = if (useEtag) synchronized(etagCache) { etagCache[key] } else null
            val request = Request.Builder().url(url).method(method, body)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", GitHubApi.API_VERSION)
                .header("User-Agent", userAgent)
                .apply {
                    token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    cached?.let { header("If-None-Match", it.etag) }
                }.build()
            val httpClient = if (followRedirects) client else client.newBuilder().followRedirects(false).build()
            val response = httpClient.newCall(request).await()
            response.use { r ->
                recordHeaders(r.headers)
                val text = withContext(Dispatchers.IO) { r.body.string() }
                when {
                    r.code == 304 && cached != null -> return GitHubResponse(200, cached.body, r.headers)
                    r.isSuccessful || (!followRedirects && r.code in 300..399) -> {
                        if (useEtag) r.header("ETag")?.let { etag -> synchronized(etagCache) { etagCache[key] = Cached(etag, text) } }
                        return GitHubResponse(r.code, text, r.headers)
                    }
                    else -> {
                        val wait = retryAfterSeconds(r)
                        val limited = isRateLimited(r)
                        if (limited && wait != null && wait <= MAX_AUTO_WAIT_SECONDS && attempt < MAX_RETRIES) {
                            attempt++
                            sleep(wait * 1000)
                            return@use
                        }
                        throw GitHubApiException.parse(r.code, text, wait, limited, missingScopes(r))
                    }
                }
            }
        }
    }

    private fun recordHeaders(h: Headers) {
        val limit = h["X-RateLimit-Limit"]?.toIntOrNull()
        val remaining = h["X-RateLimit-Remaining"]?.toIntOrNull()
        val reset = h["X-RateLimit-Reset"]?.toLongOrNull()
        if (limit != null && remaining != null && reset != null) _rateLimit.value = RateLimit(limit, remaining, reset)
        h["X-OAuth-Scopes"]?.let { scopes -> lastOAuthScopes = parseScopes(scopes) }
    }

    private fun isRateLimited(r: Response): Boolean =
        r.code == 429 || (r.code == 403 && (r.header("X-RateLimit-Remaining") == "0" || r.header("Retry-After") != null ||
            r.peekBodyText().contains("rate limit", ignoreCase = true)))

    private fun retryAfterSeconds(r: Response): Long? {
        r.header("Retry-After")?.toLongOrNull()?.let { return it }
        if (r.header("X-RateLimit-Remaining") == "0") {
            val reset = r.header("X-RateLimit-Reset")?.toLongOrNull() ?: return null
            return (reset - System.currentTimeMillis() / 1000).coerceAtLeast(1)
        }
        return if (r.code == 403 || r.code == 429) SECONDARY_DEFAULT_WAIT_SECONDS.takeIf { isSecondary(r) } else null
    }

    private fun isSecondary(r: Response) = r.peekBodyText().contains("secondary rate limit", ignoreCase = true)

    private fun missingScopes(r: Response): Set<String> {
        val accepted = r.header("X-Accepted-OAuth-Scopes")?.let(::parseScopes).orEmpty()
        val have = r.header("X-OAuth-Scopes")?.let(::parseScopes) ?: return emptySet()
        return if (accepted.isNotEmpty() && accepted.none { it in have }) accepted - have else emptySet()
    }

    private fun Response.peekBodyText(): String = runCatching { peekBody(4096).string() }.getOrDefault("")

    fun <T> decode(text: String, serializer: KSerializer<T>): T = GitHubJson.decodeFromString(serializer, text)

    /**
     * Streams a GET response (e.g. an artifact zip behind a 302) to [dest]. OkHttp drops the Authorization
     * header when a redirect changes host, so the storage host never sees the token.
     */
    suspend fun download(url: HttpUrl, dest: java.io.File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GitHubApi.API_VERSION)
            .header("User-Agent", userAgent)
            .apply { token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        val response = client.newCall(request).await()
        response.use { r ->
            if (!r.isSuccessful) throw GitHubApiException.parse(r.code, withContext(Dispatchers.IO) { r.body.string() }, null, false, emptySet())
            withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                val total = r.body.contentLength()
                r.body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
            }
        }
    }

    /** POSTs a raw body (release asset uploads go to uploads.github.com). */
    suspend fun upload(url: HttpUrl, body: RequestBody): GitHubResponse = send("POST", url, body, useEtag = false)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON = "{}".toRequestBody(JSON)
        private const val MAX_CACHE = 200
        private const val MAX_RETRIES = 2
        private const val MAX_AUTO_WAIT_SECONDS = 60L
        private const val SECONDARY_DEFAULT_WAIT_SECONDS = 60L
        private val NEXT = Regex("""<([^>]+)>\s*;\s*rel="next"""")

        fun nextLink(link: String?): HttpUrl? = link?.let { NEXT.find(it)?.groupValues?.get(1)?.toHttpUrl() }

        fun parseScopes(header: String): Set<String> = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
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
