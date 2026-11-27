package dev.gcode.core.data.repo

import androidx.room.withTransaction
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.db.SessionEntity
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GeminiJson
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.model.UsageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** A stored message, decoded. */
data class StoredMessage(
    val seq: Int,
    val content: Content,
    val usage: UsageMetadata?,
    val modelId: String?,
    val kind: String,
    val compacted: Boolean,
    val createdAt: Long,
)

class SessionRepository(
    private val db: GCodeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = db.sessions()
    private val messages = db.messages()
    private val partsSerializer = ListSerializer(Part.serializer())

    fun observeSessions(): Flow<List<SessionEntity>> = sessions.observeAll()

    fun observeSession(id: String): Flow<SessionEntity?> = sessions.observe(id)

    suspend fun get(id: String): SessionEntity? = sessions.get(id)

    suspend fun latest(): SessionEntity? = sessions.latest()

    suspend fun create(title: String = DEFAULT_TITLE, modelId: String? = null, mode: String = "ask"): SessionEntity {
        val now = clock()
        val session = SessionEntity(UUID.randomUUID().toString(), title, now, now, modelId, mode = mode)
        sessions.upsert(session)
        return session
    }

    suspend fun rename(id: String, title: String) = sessions.rename(id, title.trim().ifEmpty { DEFAULT_TITLE }, clock())

    suspend fun delete(id: String) = sessions.delete(id)

    fun observeMessages(sessionId: String): Flow<List<StoredMessage>> = messages.observe(sessionId).map { rows -> rows.map(::decode) }

    suspend fun messages(sessionId: String): List<StoredMessage> = messages.list(sessionId).map(::decode)

    /** Appends a message and returns its sequence number. Saved before any tool runs. */
    suspend fun append(sessionId: String, content: Content, usage: UsageMetadata? = null, modelId: String? = null, kind: String = MessageEntity.KIND_CHAT): Int =
        db.withTransaction {
            val seq = messages.maxSeq(sessionId) + 1
            messages.insert(
                MessageEntity(
                    sessionId = sessionId,
                    seq = seq,
                    role = content.role ?: Content.ROLE_USER,
                    partsJson = GeminiJson.encodeToString(partsSerializer, content.parts),
                    usageJson = usage?.let { GeminiJson.encodeToString(UsageMetadata.serializer(), it) },
                    modelId = modelId,
                    createdAt = clock(),
                    kind = kind,
                ),
            )
            val session = sessions.get(sessionId)
            if (session != null && session.title == DEFAULT_TITLE && content.role == Content.ROLE_USER && kind == MessageEntity.KIND_CHAT) {
                content.text.trim().takeIf { it.isNotEmpty() }?.let { sessions.rename(sessionId, titleFrom(it), clock()) }
            }
            seq
        }

    suspend fun recordUsage(sessionId: String, promptTokens: Int, limit: Int, modelId: String?) =
        sessions.touchUsage(sessionId, clock(), promptTokens, limit, modelId)

    suspend fun setTodos(sessionId: String, json: String) = sessions.setTodos(sessionId, json, clock())

    suspend fun setMode(sessionId: String, mode: String) = sessions.setMode(sessionId, mode, clock())

    suspend fun setRepo(sessionId: String, repo: String?, branch: String?) = sessions.setRepo(sessionId, repo, branch, clock())

    /** Marks messages before [seq] as compacted (kept for display and rewind, excluded from the model history). */
    suspend fun markCompacted(sessionId: String, seq: Int) = db.withTransaction {
        messages.markCompactedBefore(sessionId, seq)
        sessions.setCompactedUpTo(sessionId, seq)
    }

    /** Deletes messages from [seq] onwards (conversation rewind). Compaction before that point is undone. */
    suspend fun truncateFrom(sessionId: String, seq: Int) = db.withTransaction {
        messages.deleteFrom(sessionId, seq)
        val session = sessions.get(sessionId)
        if (session != null && session.compactedUpTo >= seq) {
            messages.clearCompacted(sessionId)
            sessions.setCompactedUpTo(sessionId, 0)
        }
    }

    private fun decode(e: MessageEntity) = StoredMessage(
        seq = e.seq,
        content = Content(e.role, GeminiJson.decodeFromString(partsSerializer, e.partsJson)),
        usage = e.usageJson?.let { GeminiJson.decodeFromString(UsageMetadata.serializer(), it) },
        modelId = e.modelId,
        kind = e.kind,
        compacted = e.compacted,
        createdAt = e.createdAt,
    )

    companion object {
        const val DEFAULT_TITLE = "New chat"

        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
        }
    }
}
