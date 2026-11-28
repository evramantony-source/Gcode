package dev.gcode.app.agent

import dev.gcode.agent.compaction.Compaction
import dev.gcode.agent.compaction.HistoryEntry
import dev.gcode.agent.loop.HistoryNormalizer
import dev.gcode.agent.loop.MessageKind
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.repo.StoredMessage
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.transport.GeminiTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context compaction for saved sessions (ARCHITECTURE.md section 5.6). Compacted messages stay in the
 * database for display and rewind; the model sees the latest summary followed by the kept messages.
 */
@Singleton
class ConversationCompactor @Inject constructor(
    private val sessions: SessionRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val transport: GeminiTransport,
) {
    /** Compacts when the prompt has reached the threshold. Returns true if it did. */
    suspend fun maybeCompact(sessionId: String, model: String): Boolean {
        val session = sessions.get(sessionId) ?: return false
        val limit = models.current().find(model)?.inputTokenLimit ?: session.contextLimit
        if (limit <= 0) return false
        val threshold = settings.current().compactionThresholdPercent
        val all = sessions.messages(sessionId)
        // Cheap estimate first: the last measured prompt plus whatever was added since.
        val lastMeasured = all.indexOfLast { it.usage != null }
        val added = all.drop(lastMeasured + 1).filter { !it.compacted }.map { it.content }
        val estimate = session.lastPromptTokens + (all.getOrNull(lastMeasured)?.usage?.candidatesTokenCount ?: 0) + Compaction.estimateTokens(added)
        if (!Compaction.shouldCompact(estimate, limit, (threshold - NEAR_MARGIN).coerceAtLeast(10))) return false
        // Near the threshold: count exactly with countTokens.
        val exact = runCatching { transport.countTokens(model, HistoryNormalizer.normalize(historyOf(all))) }.getOrDefault(estimate)
        if (!Compaction.shouldCompact(exact, limit, threshold)) return false
        return compact(sessionId, model, all, manual = false)
    }

    /** Manual Compact (menu or /compact): summarises everything so far. */
    suspend fun compactNow(sessionId: String, model: String): Boolean = compact(sessionId, model, sessions.messages(sessionId), manual = true)

    private suspend fun compact(sessionId: String, model: String, all: List<StoredMessage>, manual: Boolean): Boolean {
        val visible = entries(all)
        val boundary = (if (manual) Compaction.manualBoundary(visible) else Compaction.boundary(visible)) ?: return false
        val toSummarize = visible.filter { it.seq < boundary }
        if (toSummarize.isEmpty()) return false
        val previous = latestSummary(all)?.content?.text
        val summary = Compaction.summarize(transport, model, previous, toSummarize)
        val message = Compaction.summaryMessage(summary)
        sessions.append(sessionId, message, kind = MessageEntity.KIND_SUMMARY)
        sessions.markCompacted(sessionId, boundary)
        val limit = models.current().find(model)?.inputTokenLimit ?: 0
        val kept = visible.filter { it.seq >= boundary }.map { it.content }
        sessions.recordUsage(sessionId, Compaction.estimateTokens(listOf(message) + kept), limit, model)
        return true
    }

    companion object {
        /** Start counting exactly this many percentage points below the threshold. */
        private const val NEAR_MARGIN = 5

        private fun kindOf(kind: String) = when (kind) {
            MessageEntity.KIND_TOOL_RESULTS -> MessageKind.TOOL_RESULTS
            MessageEntity.KIND_SUMMARY -> MessageKind.SUMMARY
            else -> MessageKind.CHAT
        }

        /** Model-visible messages without summaries. */
        fun entries(all: List<StoredMessage>): List<HistoryEntry> =
            all.filter { !it.compacted && it.kind != MessageEntity.KIND_SUMMARY }.map { HistoryEntry(it.seq, it.content, kindOf(it.kind)) }

        fun latestSummary(all: List<StoredMessage>): StoredMessage? = all.lastOrNull { it.kind == MessageEntity.KIND_SUMMARY && !it.compacted }

        /** What the model sees: the latest summary first, then every message that is not compacted. */
        fun historyOf(all: List<StoredMessage>): List<Content> =
            listOfNotNull(latestSummary(all)?.content) + all.filter { !it.compacted && it.kind != MessageEntity.KIND_SUMMARY }.map { it.content }
    }
}
