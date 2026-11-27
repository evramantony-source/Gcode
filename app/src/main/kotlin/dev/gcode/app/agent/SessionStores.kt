package dev.gcode.app.agent

import dev.gcode.agent.tools.Checkpoint
import dev.gcode.agent.tools.CheckpointStore
import dev.gcode.agent.tools.TodoItem
import dev.gcode.agent.tools.TodoJson
import dev.gcode.agent.tools.TodoStore
import dev.gcode.core.data.db.CheckpointEntity
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.repo.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Checkpoints for one session in one workspace (ARCHITECTURE.md section 7.5): metadata in Room, bytes in
 * files/checkpoints/<session>/<id>. Pruned to the newest [keep] entries.
 */
class RoomCheckpointStore(
    private val sessionId: String,
    private val workspaceKey: String,
    private val db: GCodeDatabase,
    private val sessions: SessionRepository,
    private val baseDir: File,
    private val keep: Int = 100,
) : CheckpointStore {
    private val dir get() = File(baseDir, sessionId).apply { mkdirs() }

    override suspend fun record(path: String, before: ByteArray?) = withContext(Dispatchers.IO) {
        val id = db.checkpoints().insert(
            CheckpointEntity(
                sessionId = sessionId,
                messageSeq = sessions.lastSeq(sessionId),
                workspace = workspaceKey,
                path = path,
                existed = before != null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (before != null) File(dir, id.toString()).writeBytes(before)
        val stale = db.checkpoints().idsBeyond(sessionId, keep)
        if (stale.isNotEmpty()) delete(stale)
    }

    override suspend fun since(seq: Int): List<Checkpoint> = withContext(Dispatchers.IO) {
        db.checkpoints().since(sessionId, seq).filter { it.workspace == workspaceKey }.map { e ->
            Checkpoint(e.id, e.messageSeq, e.path, if (e.existed) File(dir, e.id.toString()).takeIf { it.isFile }?.readBytes() else null, e.createdAt)
        }
    }

    override suspend fun delete(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.checkpoints().delete(ids)
        ids.forEach { File(dir, it.toString()).delete() }
    }
}

class SessionTodoStore(private val sessionId: String, private val sessions: SessionRepository, private val onChange: (List<TodoItem>) -> Unit) : TodoStore {
    override suspend fun get(): List<TodoItem> = TodoJson.decode(sessions.get(sessionId)?.todoJson)
    override suspend fun set(items: List<TodoItem>) {
        sessions.setTodos(sessionId, TodoJson.encode(items))
        onChange(items)
    }
}
