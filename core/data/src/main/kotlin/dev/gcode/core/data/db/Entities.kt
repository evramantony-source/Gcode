package dev.gcode.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val repoFullName: String? = null,
    val branch: String? = null,
    val mode: String = "ask",
    val todoJson: String = "[]",
    /** Messages with seq below this were summarised by compaction. */
    val compactedUpTo: Int = 0,
    val lastPromptTokens: Int = 0,
    val contextLimit: Int = 0,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "seq"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Int,
    /** "user" or "model". */
    val role: String,
    /** The Content's parts as raw JSON, exactly as sent or received (keeps thought signatures). */
    val partsJson: String,
    val usageJson: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    /** "chat", "summary" (compaction) or "tool_results". */
    val kind: String = KIND_CHAT,
    val compacted: Boolean = false,
) {
    companion object {
        const val KIND_CHAT = "chat"
        const val KIND_SUMMARY = "summary"
        const val KIND_TOOL_RESULTS = "tool_results"
    }
}

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String?,
    val description: String?,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
    /** Comma-separated supportedGenerationMethods. */
    val methods: String,
    val thinking: Boolean,
    val fetchedAt: Long,
)
