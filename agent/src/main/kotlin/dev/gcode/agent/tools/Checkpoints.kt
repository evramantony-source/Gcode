package dev.gcode.agent.tools

/** One file's state before an agent change (ARCHITECTURE.md section 7.5). */
data class Checkpoint(
    val id: Long,
    val messageSeq: Int,
    val path: String,
    /** Previous contents, or null if the file did not exist. */
    val before: ByteArray?,
    val createdAt: Long,
) {
    override fun equals(other: Any?) = other is Checkpoint && other.id == id
    override fun hashCode() = id.hashCode()
}

/** Records file states before each change; the app stores them per session. */
interface CheckpointStore {
    /** The sequence number of the latest saved message, used to tie checkpoints to conversation points. */
    suspend fun record(path: String, before: ByteArray?)

    /** Checkpoints made after message [seq], newest first. */
    suspend fun since(seq: Int): List<Checkpoint>

    suspend fun delete(ids: List<Long>)
}

/** Restores files to their state at a conversation point by replaying checkpoints newest-first. */
object Rewinder {
    suspend fun restore(store: CheckpointStore, fs: dev.gcode.agent.workspace.WorkspaceFs, afterSeq: Int): List<String> {
        val checkpoints = store.since(afterSeq) // newest first
        val restored = linkedSetOf<String>()
        checkpoints.forEach { cp ->
            if (cp.before == null) {
                if (fs.exists(cp.path)) fs.delete(cp.path)
            } else {
                fs.writeBytes(cp.path, cp.before)
            }
            restored += cp.path
        }
        store.delete(checkpoints.map { it.id })
        return restored.toList()
    }
}

/** In-memory store (sub-agents and tests). */
class MemoryCheckpointStore(private val currentSeq: () -> Int = { 0 }) : CheckpointStore {
    private val items = mutableListOf<Checkpoint>()
    private var nextId = 1L

    private val lock = Any()

    override suspend fun record(path: String, before: ByteArray?) {
        synchronized(lock) { items += Checkpoint(nextId++, currentSeq(), path, before, System.currentTimeMillis()) }
    }

    override suspend fun since(seq: Int): List<Checkpoint> = synchronized(lock) { items.filter { it.messageSeq > seq }.sortedByDescending { it.id } }

    override suspend fun delete(ids: List<Long>) {
        synchronized(lock) { items.removeAll { it.id in ids } }
    }
}
