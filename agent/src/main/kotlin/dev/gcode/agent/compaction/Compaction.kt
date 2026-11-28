package dev.gcode.agent.compaction

import dev.gcode.agent.loop.MessageKind
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.serialization.json.JsonPrimitive

/** A saved message as the compactor sees it. */
data class HistoryEntry(val seq: Int, val content: Content, val kind: MessageKind)

/**
 * Context compaction (ARCHITECTURE.md section 5.6): when the prompt reaches the threshold, everything
 * before the current turn is replaced by a summary. Cuts happen only at turn boundaries (a user message
 * with text), so a function call is never separated from its response.
 */
object Compaction {
    const val SUMMARY_HEADER = "Summary of the earlier conversation (compacted by GCode):"
    private const val TOOL_OUTPUT_CHARS = 1_500
    private const val MAX_TRANSCRIPT_CHARS = 600_000

    fun shouldCompact(promptTokens: Int, inputLimit: Int, thresholdPercent: Int): Boolean =
        inputLimit > 0 && promptTokens.toLong() * 100 >= inputLimit.toLong() * thresholdPercent

    /** Rough size of contents in tokens (≈ 4 characters per token; images count as their data). */
    fun estimateTokens(contents: List<Content>): Int = contents.sumOf { c -> c.parts.sumOf { p -> p.json.toString().length } } / 4

    /** A user message with text starts a turn (tool results are user messages without text). */
    fun startsTurn(entry: HistoryEntry): Boolean =
        entry.kind == MessageKind.CHAT && entry.content.role == Content.ROLE_USER && entry.content.parts.any { it.text?.isNotBlank() == true }

    /**
     * The sequence number the kept history starts at: the latest turn start, if anything before it can be
     * summarised. [entries] are the model-visible messages without summaries, in order.
     */
    fun boundary(entries: List<HistoryEntry>): Int? {
        val lastTurn = entries.lastOrNull(::startsTurn) ?: return null
        return lastTurn.seq.takeIf { entries.any { it.seq < lastTurn.seq } }
    }

    /** Same as [boundary] for a manual Compact between turns: keep nothing but the summary when the last turn has ended. */
    fun manualBoundary(entries: List<HistoryEntry>): Int? = entries.lastOrNull()?.let { it.seq + 1 }

    /** The summary request: the earlier conversation as a readable transcript (no tool declarations needed). */
    fun summaryRequest(previousSummary: String?, entries: List<HistoryEntry>): GenerateContentRequest {
        val transcript = transcript(previousSummary, entries)
        return GenerateContentRequest(
            contents = listOf(Content.user("$transcript\n\n$INSTRUCTIONS")),
            // Default temperature: Gemini 3 models are tuned for it.
            systemInstruction = Content(parts = listOf(Part.text(SYSTEM))),
        )
    }

    fun summaryMessage(summary: String): Content = Content.user("$SUMMARY_HEADER\n\n${summary.trim()}")

    fun transcript(previousSummary: String?, entries: List<HistoryEntry>): String {
        val out = StringBuilder()
        previousSummary?.let { out.append("[Earlier summary]\n").append(it.removePrefix(SUMMARY_HEADER).trim()).append("\n\n") }
        entries.forEach { e ->
            val speaker = when {
                e.kind == MessageKind.TOOL_RESULTS -> "Tool results"
                e.content.role == Content.ROLE_USER -> "User"
                else -> "Assistant"
            }
            out.append("[$speaker]\n")
            e.content.parts.forEach { p ->
                when {
                    p.isThought -> Unit
                    p.text != null -> out.append(p.text).append('\n')
                    p.functionCall != null -> out.append("→ ${p.functionCall!!.name}(${p.functionCall!!.args.toString().take(TOOL_OUTPUT_CHARS)})\n")
                    p.functionResponse != null -> {
                        val r = p.functionResponse!!
                        val text = r.response.entries.joinToString("; ") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }
                        out.append("← ${r.name}: ${text.take(TOOL_OUTPUT_CHARS)}${if (text.length > TOOL_OUTPUT_CHARS) " …" else ""}\n")
                    }
                    p.inlineData != null -> out.append("[attachment ${p.inlineData!!.mimeType}]\n")
                }
            }
            out.append('\n')
        }
        // Extremely long histories: keep the start (goals) and the most recent part.
        return if (out.length <= MAX_TRANSCRIPT_CHARS) out.toString()
        else out.substring(0, MAX_TRANSCRIPT_CHARS / 4) + "\n…(middle of the conversation omitted)…\n" + out.substring(out.length - MAX_TRANSCRIPT_CHARS * 3 / 4)
    }

    /** Calls the model for a summary of [entries] (plus the previous summary, if any). */
    suspend fun summarize(transport: GeminiTransport, model: String, previousSummary: String?, entries: List<HistoryEntry>): String {
        val response = transport.generateContent(model, summaryRequest(previousSummary, entries))
        return response.candidates.firstOrNull()?.content?.text?.takeIf { it.isNotBlank() } ?: error("The model returned an empty summary")
    }

    private const val SYSTEM = "You compact long conversations between a user and GCode, an agentic coding assistant on Android. " +
        "You write summaries that let the assistant continue the work without the original messages."

    private val INSTRUCTIONS = """
        Summarise the conversation above so the assistant can continue seamlessly. Use these Markdown sections:
        ## Goal — what the user wants, with their exact requirements and constraints.
        ## Done so far — decisions made, files created or changed (paths) and why, commits, pushes, builds and their results.
        ## Current state — what is in progress, open errors (exact messages), and the todo list.
        ## Remember — preferences and facts the user stated.
        Be specific (names, paths, versions, run ids). Keep code to short excerpts. Do not invent anything.
    """.trimIndent()
}
