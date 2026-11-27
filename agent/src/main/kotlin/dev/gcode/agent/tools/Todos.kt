package dev.gcode.agent.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class TodoItem(val content: String, val status: String = PENDING) {
    companion object {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val DONE = "done"
        val STATUSES = listOf(PENDING, IN_PROGRESS, DONE)
    }
}

/** The session's live todo list (ARCHITECTURE.md section 6.3). */
interface TodoStore {
    suspend fun get(): List<TodoItem>
    suspend fun set(items: List<TodoItem>)
}

object TodoJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TodoItem.serializer())
    fun encode(items: List<TodoItem>): String = json.encodeToString(serializer, items)
    fun decode(text: String?): List<TodoItem> = runCatching { json.decodeFromString(serializer, text ?: "[]") }.getOrDefault(emptyList())
}

class MemoryTodoStore : TodoStore {
    @Volatile private var items: List<TodoItem> = emptyList()
    override suspend fun get() = items
    override suspend fun set(items: List<TodoItem>) {
        this.items = items
    }
}
