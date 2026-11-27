package dev.gcode.app.ui.chat

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.repo.StoredMessage
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.Part
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ChatItemsTest {
    private fun msg(seq: Int, content: Content, kind: String = MessageEntity.KIND_CHAT) = StoredMessage(seq, content, null, "m", kind, false, 0)

    @Test
    fun `tool calls are paired with their responses by id and tool results are hidden`() {
        val messages = listOf(
            msg(0, Content.user("fix it")),
            msg(1, Content.model(Part.text("Looking"), Part.functionCall("read_file", buildJsonObject { put("path", "a.kt") }, "c1"), Part.functionCall("grep", buildJsonObject { put("pattern", "x") }, "c2"))),
            msg(2, Content.user(
                Part.functionResponse("read_file", buildJsonObject { put("result", "Read a.kt") }, "c1"),
                Part.functionResponse("grep", buildJsonObject { put("error", "Invalid regular expression") }, "c2"),
            ), MessageEntity.KIND_TOOL_RESULTS),
            msg(3, Content.model(Part.text("Done"))),
        )
        val items = ChatItems.build(messages) { args -> args.keys.joinToString() }
        assertThat(items.map { it::class.simpleName }).containsExactly("User", "Model", "ToolCall", "ToolCall", "Model").inOrder()
        val read = items[2] as ChatItem.ToolCall
        assertThat(read.ok).isTrue()
        assertThat(read.result).isEqualTo("Read a.kt")
        val grep = items[3] as ChatItem.ToolCall
        assertThat(grep.ok).isFalse()
        assertThat(grep.result).contains("Invalid")
    }

    @Test
    fun `calls without a response yet show as pending`() {
        val items = ChatItems.build(listOf(msg(0, Content.model(Part.functionCall("git_push", buildJsonObject { }, "p1"))))) { "" }
        assertThat((items.single() as ChatItem.ToolCall).ok).isNull()
    }
}
