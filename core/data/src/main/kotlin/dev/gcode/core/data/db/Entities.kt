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

/** A repository cloned into the app workspace, with its per-repo settings (ARCHITECTURE.md section 10). */
@Entity(tableName = "local_repos")
data class LocalRepoEntity(
    @PrimaryKey val fullName: String,
    val path: String,
    val defaultBranch: String,
    val remoteUrl: String,
    val buildWorkflow: String? = null,
    val autoFixLimit: Int = 3,
    val pullRebase: Boolean = false,
    val authorName: String? = null,
    val authorEmail: String? = null,
    val defaultMode: String? = null,
    val clonedAt: Long,
    val lastOpenedAt: Long,
)

/** Cached GitHub repository listings (the raw JSON of each repo). */
@Entity(tableName = "remote_repos")
data class RemoteRepoEntity(
    @PrimaryKey val fullName: String,
    val json: String,
    val pushedAt: String?,
    val fetchedAt: Long,
)

/** A file's state before an agent change; the bytes live in files/checkpoints/<session>/<id>. */
@Entity(
    tableName = "checkpoints",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val messageSeq: Int,
    /** Workspace key ("repo:owner/name" or "saf:<uri>") the path belongs to. */
    val workspace: String,
    val path: String,
    /** False when the file did not exist before the change. */
    val existed: Boolean,
    val createdAt: Long,
)
