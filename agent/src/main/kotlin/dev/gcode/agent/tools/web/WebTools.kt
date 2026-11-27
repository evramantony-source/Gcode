package dev.gcode.agent.tools.web

import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.FixedEffectTool
import dev.gcode.agent.tools.Prepared
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.int
import dev.gcode.agent.tools.requireStr
import dev.gcode.agent.tools.schema
import dev.gcode.agent.tools.str
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import dev.gcode.gemini.model.Tool as GeminiTool

/**
 * Fetches a public web page and returns readable text (ARCHITECTURE.md section 6.5). Pages on the device's
 * own network (loopback, private, link-local addresses) are refused, so a prompt-injected page cannot make
 * the agent probe the user's LAN.
 */
class WebFetchTool(
    private val client: OkHttpClient,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val allowPrivateHosts: Boolean = false,
) : FixedEffectTool(ToolEffect.READ) {
    override val name = "web_fetch"
    override val description = "Fetch a public web page (http/https) and return its title and readable text (HTML converted to Markdown-like text). Use for documentation, release notes and issues on the web."
    override val parameters = schema {
        string("url", "Absolute http(s) URL", required = true)
        string("selector", "Optional CSS selector to keep only part of the page, e.g. main, article, #content")
        integer("max_chars", "Maximum characters of text to return (default 20000, max 100000)")
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val url = args.requireStr("url").trim().toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?: throw ToolArgumentException("url must be an absolute http or https URL")
        if (!allowPrivateHosts) checkPublic(url)
        val maxChars = (args.int("max_chars") ?: 20_000).coerceIn(500, 100_000)
        val page = fetch(url)
        val text = if (page.isHtml) htmlToText(page.body, page.finalUrl, args.str("selector")) else page.body
        val title = if (page.isHtml) Jsoup.parse(page.body).title() else ""
        val truncated = text.length > maxChars
        ToolResult(
            true,
            ToolResult.jsonOf(
                listOfNotNull(
                    "url" to page.finalUrl,
                    "status" to page.status,
                    "content_type" to page.contentType,
                    ("title" to title).takeIf { title.isNotBlank() },
                    "text" to text.take(maxChars),
                    ("truncated" to "Text cut at $maxChars of ${text.length} characters").takeIf { truncated },
                ),
            ),
            "Fetched ${url.host} (${text.length} chars)",
        )
    }

    private fun checkPublic(url: HttpUrl) {
        val addresses = try {
            resolve(url.host)
        } catch (e: IOException) {
            throw ToolArgumentException("Cannot resolve ${url.host}: ${e.message}")
        }
        if (addresses.isEmpty() || addresses.any(::isPrivate)) throw ToolArgumentException("${url.host} is a local or private network address; web_fetch only reads public pages")
    }

    private data class Page(val finalUrl: String, val status: Int, val contentType: String, val body: String) {
        val isHtml: Boolean get() = contentType.contains("html")
    }

    private suspend fun fetch(url: HttpUrl): Page {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain,text/markdown,application/json;q=0.9,*/*;q=0.5")
            .build()
        val response = client.newCall(request).await()
        return response.use { r ->
            if (!allowPrivateHosts && r.request.url.host != url.host) checkPublic(r.request.url)
            val type = r.body.contentType()
            val mime = type?.let { "${it.type}/${it.subtype}" } ?: "text/plain"
            if (!(type == null || type.type == "text" || mime.contains("json") || mime.contains("xml") || mime.contains("javascript"))) {
                throw ToolArgumentException("${r.request.url} is $mime, not text")
            }
            val bytes = withContext(Dispatchers.IO) {
                val buffer = Buffer()
                val source = r.body.source()
                while (buffer.size < MAX_BYTES && source.read(buffer, 64 * 1024) != -1L) Unit
                buffer.readByteArray(minOf(buffer.size, MAX_BYTES))
            }
            val charset = type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            if (!r.isSuccessful) throw ToolArgumentException("HTTP ${r.code} from ${r.request.url}")
            Page(r.request.url.toString(), r.code, mime, String(bytes, charset))
        }
    }

    companion object {
        const val USER_AGENT = "GCode/1.0 (Android; +https://github.com/evramantony-source/Gcode)"
        private const val MAX_BYTES = 2L * 1024 * 1024

        fun isPrivate(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress ||
                address.isMulticastAddress || (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC) ||
                // 100.64.0.0/10 carrier-grade NAT
                (address.address.size == 4 && address.address[0].toInt() and 0xFF == 100 && (address.address[1].toInt() and 0xC0) == 64)

        /** HTML → readable text: headings as #, list items as -, code blocks fenced, scripts and navigation removed. */
        fun htmlToText(html: String, baseUrl: String, selector: String? = null): String {
            val doc = Jsoup.parse(html, baseUrl)
            doc.select("script, style, noscript, svg, iframe, template, nav, footer, header[role=banner], form, button").remove()
            val root: Element = selector?.takeIf { it.isNotBlank() }?.let { runCatching { doc.selectFirst(it) }.getOrNull() }
                ?: doc.selectFirst("main, article, [role=main]") ?: doc.body() ?: doc
            val out = StringBuilder()
            NodeTraversor.traverse(
                object : NodeVisitor {
                    override fun head(node: Node, depth: Int) {
                        when (node) {
                            is TextNode -> if (!insidePre(node)) out.append(node.text())
                            is Element -> when (val tag = node.normalName()) {
                                "h1", "h2", "h3", "h4", "h5", "h6" -> out.append("\n\n").append("#".repeat(tag[1].digitToInt())).append(' ')
                                "li" -> out.append("\n- ")
                                "br" -> out.append('\n')
                                "pre" -> out.append("\n\n```\n").append(node.wholeText().trimEnd()).append("\n```\n\n")
                                "tr" -> out.append('\n')
                                "td", "th" -> out.append(" | ")
                                in BLOCKS -> out.append("\n\n")
                            }
                        }
                    }

                    override fun tail(node: Node, depth: Int) {
                        if (node is Element && (node.normalName() in BLOCKS || node.normalName().matches(HEADING))) out.append("\n\n")
                    }
                },
                root,
            )
            return out.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private fun insidePre(node: Node): Boolean {
            var parent = node.parentNode()
            while (parent != null) {
                if (parent is Element && parent.normalName() == "pre") return true
                parent = parent.parentNode()
            }
            return false
        }

        private val HEADING = Regex("h[1-6]")
        private val BLOCKS = setOf("p", "div", "section", "article", "main", "blockquote", "table", "ul", "ol", "dl", "dt", "dd", "figure", "details", "summary")
    }
}

/**
 * Web search through Gemini's Google Search grounding, as a separate model call (ARCHITECTURE.md section 6.5):
 * returns the grounded answer and its sources.
 */
class WebSearchTool(private val transport: GeminiTransport, private val model: suspend () -> String) : FixedEffectTool(ToolEffect.READ) {
    override val name = "web_search"
    override val description = "Search the web with Google Search and get a short grounded answer with source URLs. Use for current versions, release notes, error messages and documentation."
    override val parameters = schema { string("query", "What to search for", required = true) }

    override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(null) {
        val query = args.requireStr("query")
        val request = GenerateContentRequest(
            contents = listOf(Content.user("Search the web and answer concisely, with exact versions, names and dates where relevant.\n\nQuery: $query")),
            tools = listOf(GeminiTool(googleSearch = JsonObject(emptyMap()))),
        )
        val response = transport.generateContent(model(), request)
        val candidate = response.candidates.firstOrNull() ?: return@Prepared ToolResult.error("No answer (${response.promptFeedback ?: "empty response"})")
        val answer = candidate.content?.text.orEmpty()
        val sources = sources(candidate.groundingMetadata)
        val queries = (candidate.groundingMetadata?.get("webSearchQueries") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        ToolResult(
            true,
            ToolResult.jsonOf(
                listOfNotNull(
                    "answer" to answer.ifBlank { "(no text)" },
                    "sources" to sources.mapIndexed { i, (title, uri) -> "${i + 1}. $title — $uri" }.joinToString("\n").ifEmpty { "(no sources returned)" },
                    ("search_queries" to queries.joinToString("; ")).takeIf { queries.isNotEmpty() },
                ),
            ),
            "${sources.size} sources for \"${query.take(60)}\"",
        )
    }

    companion object {
        /** (title, uri) pairs from `groundingMetadata.groundingChunks[].web`. */
        fun sources(metadata: JsonObject?): List<Pair<String, String>> =
            (metadata?.get("groundingChunks") as? JsonArray).orEmpty().mapNotNull { chunk ->
                val web = (chunk as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
                val uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ((web["title"] as? JsonPrimitive)?.contentOrNull ?: uri) to uri
            }.distinctBy { it.second }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        },
    )
}
