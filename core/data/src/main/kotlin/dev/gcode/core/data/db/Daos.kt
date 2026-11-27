package dev.gcode.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE sessions SET updatedAt = :now, lastPromptTokens = :promptTokens, contextLimit = :limit, modelId = :model WHERE id = :id")
    suspend fun touchUsage(id: String, now: Long, promptTokens: Int, limit: Int, model: String?)

    @Query("UPDATE sessions SET todoJson = :todoJson, updatedAt = :now WHERE id = :id")
    suspend fun setTodos(id: String, todoJson: String, now: Long)

    @Query("UPDATE sessions SET mode = :mode, updatedAt = :now WHERE id = :id")
    suspend fun setMode(id: String, mode: String, now: Long)

    @Query("UPDATE sessions SET repoFullName = :repo, branch = :branch, updatedAt = :now WHERE id = :id")
    suspend fun setRepo(id: String, repo: String?, branch: String?, now: Long)

    @Query("UPDATE sessions SET compactedUpTo = :seq WHERE id = :id")
    suspend fun setCompactedUpTo(id: String, seq: Int)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq")
    fun observe(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun list(sessionId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET compacted = 1 WHERE sessionId = :sessionId AND seq < :seq")
    suspend fun markCompactedBefore(sessionId: String, seq: Int)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND seq >= :seq")
    suspend fun deleteFrom(sessionId: String, seq: Int)

    @Query("UPDATE messages SET compacted = 0 WHERE sessionId = :sessionId")
    suspend fun clearCompacted(sessionId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY id")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY id")
    suspend fun all(): List<ModelEntity>

    @Query("DELETE FROM models")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<ModelEntity>)

    @Transaction
    suspend fun replaceAll(models: List<ModelEntity>) {
        clear()
        insertAll(models)
    }
}

@Dao
interface LocalRepoDao {
    @Query("SELECT * FROM local_repos ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<LocalRepoEntity>>

    @Query("SELECT * FROM local_repos WHERE fullName = :fullName")
    suspend fun get(fullName: String): LocalRepoEntity?

    @Query("SELECT * FROM local_repos WHERE fullName = :fullName")
    fun observe(fullName: String): Flow<LocalRepoEntity?>

    @Upsert
    suspend fun upsert(repo: LocalRepoEntity)

    @Query("DELETE FROM local_repos WHERE fullName = :fullName")
    suspend fun delete(fullName: String)

    @Query("UPDATE local_repos SET lastOpenedAt = :now WHERE fullName = :fullName")
    suspend fun touch(fullName: String, now: Long)
}

@Dao
interface RemoteRepoDao {
    @Query("SELECT * FROM remote_repos ORDER BY pushedAt DESC")
    fun observeAll(): Flow<List<RemoteRepoEntity>>

    @Query("DELETE FROM remote_repos")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RemoteRepoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: RemoteRepoEntity)

    @Query("DELETE FROM remote_repos WHERE fullName = :fullName")
    suspend fun delete(fullName: String)

    @Transaction
    suspend fun replaceAll(rows: List<RemoteRepoEntity>) {
        clear()
        insertAll(rows)
    }
}

@Dao
interface CheckpointDao {
    @Insert
    suspend fun insert(checkpoint: CheckpointEntity): Long

    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId AND messageSeq > :seq ORDER BY id DESC")
    suspend fun since(sessionId: String, seq: Int): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId ORDER BY id DESC")
    fun observe(sessionId: String): Flow<List<CheckpointEntity>>

    @Query("DELETE FROM checkpoints WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT id FROM checkpoints WHERE sessionId = :sessionId ORDER BY id DESC LIMIT -1 OFFSET :keep")
    suspend fun idsBeyond(sessionId: String, keep: Int): List<Long>
}
