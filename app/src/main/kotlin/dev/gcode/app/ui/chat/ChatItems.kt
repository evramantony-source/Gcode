package dev.gcode.app.ui.chat

import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.repo.StoredMessage
import dev.gcode.gemini.model.Content
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the chat list shows. Tool calls are paired with their responses by call id. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val seq: Int) : ChatItem
    data class Model(override val key: String, val text: String, val thoughts: String, val model: String?) : ChatItem
    data class ToolCall(override val key: String, val name: String, val args: String, val ok: Boolean?, val result: String?) : ChatItem
    data class Compacted(override val key: String) : ChatItem
}

object ChatItems {
    fun build(messages: List<StoredMessage>, argsSummary: (JsonObject) -> String): List<ChatItem> {
        val responses = messages.filter { it.kind == MessageEntity.KIND_TOOL_RESULTS }
            .flatMap { m -> m.content.parts.mapNotNull { it.functionResponse } }
            .associateBy { it.id ?: it.name }
        val items = mutableListOf<ChatItem>()
        messages.forEach { m ->
            when {
                m.kind == MessageEntity.KIND_SUMMARY -> items += ChatItem.Compacted("c${m.seq}")
                m.kind == MessageEntity.KIND_TOOL_RESULTS -> Unit
                m.content.role == Content.ROLE_USER -> if (m.content.text.isNotBlank()) items += ChatItem.User("u${m.seq}", m.content.text, m.seq)
                else -> {
                    val text = m.content.text
                    val thoughts = m.content.thoughtText
                    if (text.isNotBlank() || thoughts.isNotBlank()) items += ChatItem.Model("m${m.seq}", text, thoughts, m.modelId)
                    m.content.functionCalls.forEachIndexed { i, call ->
                        val response = responses[call.id ?: call.name]?.response
                        val error = (response?.get("error") as? JsonPrimitive)?.content
                        items += ChatItem.ToolCall(
                            key = "t${m.seq}_$i",
                            name = call.name,
                            args = argsSummary(call.args),
                            ok = response?.let { error == null },
                            result = error ?: response?.let(::resultText),
                        )
                    }
                }
            }
        }
        return items
    }

    private fun resultText(response: JsonObject): String =
        (response["result"] as? JsonPrimitive)?.content ?: response.entries.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }.take(2_000)
}
