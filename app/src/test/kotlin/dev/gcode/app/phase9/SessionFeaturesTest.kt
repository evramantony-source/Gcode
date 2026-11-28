package dev.gcode.app.phase9

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.compaction.Compaction
import dev.gcode.app.agent.ConversationCompactor
import dev.gcode.app.agent.RewindService
import dev.gcode.app.agent.WorkspaceExtensions
import dev.gcode.app.ui.chat.ChatItem
import dev.gcode.app.ui.chat.ChatItems
import dev.gcode.app.ui.chat.ImageAttachments
import dev.gcode.app.ui.mcp.McpViewModel
import dev.gcode.app.ui.sessions.SessionsViewModel
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.db.SessionEntity
import dev.gcode.core.data.repo.StoredMessage
import dev.gcode.core.data.settings.McpServerEntry
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.Part
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SessionFeaturesTest {
    private fun msg(seq: Int, content: Content, kind: String = MessageEntity.KIND_CHAT, compacted: Boolean = false) =
        StoredMessage(seq, content, null, null, kind, compacted, 0)

    private val conversation = listOf(
        msg(1, Content.user("Build the login screen"), compacted = true),
        msg(2, Content.model(Part.text("Done")), compacted = true),
        msg(3, Content.user("Now add tests")),
        msg(4, Content.model(Part.functionCall("git_push", buildJsonObject { put("branch", "feature") }, "c1"))),
        msg(5, Content(Content.ROLE_USER, listOf(Part.functionResponse("git_push", buildJsonObject { put("result", "ok") }, "c1"))), MessageEntity.KIND_TOOL_RESULTS),
        msg(6, Content.model(Part.functionCall("gh_create_pr", buildJsonObject { put("title", "Add tests") }, "c2"))),
        msg(7, Content.model(Part.functionCall("git_commit", buildJsonObject { put("message", "Tests") }, "c3"))),
        msg(8, Compaction.summaryMessage("The user wants a login screen; it is done."), MessageEntity.KIND_SUMMARY),
    )

    @Test
    fun modelSeesTheSummaryFirstThenKeptMessages() {
        val history = ConversationCompactor.historyOf(conversation)
        assertThat(history.first().text).startsWith(Compaction.SUMMARY_HEADER)
        assertThat(history.drop(1).first().text).isEqualTo("Now add tests")
        assertThat(history).hasSize(6)
        assertThat(ConversationCompactor.entries(conversation).map { it.seq }).containsExactly(3, 4, 5, 6, 7).inOrder()
        assertThat(ConversationCompactor.latestSummary(conversation)!!.seq).isEqualTo(8)
        // Without a summary nothing changes.
        assertThat(ConversationCompactor.historyOf(conversation.take(3).map { it.copy(compacted = false) })).hasSize(3)
    }

    @Test
    fun compactedMarkerSitsWhereTheKeptConversationStarts() {
        val items = ChatItems.build(conversation) { it.toString() }
        val marker = items.indexOfFirst { it is ChatItem.Compacted }
        val firstKept = items.indexOfFirst { it is ChatItem.User && it.text == "Now add tests" }
        assertThat(marker).isEqualTo(firstKept - 1)
        assertThat(items.count { it is ChatItem.Compacted }).isEqualTo(1)
        val withImage = ChatItems.build(listOf(msg(1, Content.user(Part.inlineData("image/jpeg", "AAAA"), Part.text("What is this?"))))) { "" }
        assertThat((withImage.single() as ChatItem.User).images).isEqualTo(1)
    }

    @Test
    fun rewindListsWhatCannotBeUndone() {
        val (remote, git) = RewindService.actionsAfter(conversation, 3)
        assertThat(remote).containsExactly("git_push (feature)", "gh_create_pr (Add tests)").inOrder()
        assertThat(git).containsExactly("git_commit (Tests)")
        assertThat(RewindService.actionsAfter(conversation, 7).first).isEmpty()
    }

    @Test
    fun memoryCombinesGlobalAndProjectInstructions() {
        assertThat(WorkspaceExtensions.combineMemory("Use British English.", "Kotlin, Compose.")).isEqualTo(
            "## Global instructions\nUse British English.\n\n## GEMINI.md (this repository)\nKotlin, Compose.",
        )
        assertThat(WorkspaceExtensions.combineMemory("", null)).isNull()
        assertThat(WorkspaceExtensions.combineMemory(null, "Only project")).isEqualTo("## GEMINI.md (this repository)\nOnly project")
    }

    @Test
    fun imagesAreScaledToAtMost1536Pixels() {
        assertThat(ImageAttachments.scaled(4000, 3000)).isEqualTo(1536 to 1152)
        assertThat(ImageAttachments.scaled(800, 600)).isEqualTo(800 to 600)
        assertThat(ImageAttachments.sampleSize(4000, 3000)).isEqualTo(2)
        assertThat(ImageAttachments.sampleSize(12000, 9000)).isEqualTo(4)
        assertThat(ImageAttachments.sampleSize(1000, 1000)).isEqualTo(1)
    }

    @Test
    fun sessionSearchMatchesTitleAndRepository() {
        fun s(id: String, title: String, repo: String? = null) = SessionEntity(id, title, 0, 0, null, repoFullName = repo)
        val list = listOf(s("1", "Fix login crash", "octo/app"), s("2", "Release notes"), s("3", "Refactor", "octo/site"))
        assertThat(SessionsViewModel.filter(list, "LOGIN").map { it.id }).containsExactly("1")
        assertThat(SessionsViewModel.filter(list, "site").map { it.id }).containsExactly("3")
        assertThat(SessionsViewModel.filter(list, "  ")).hasSize(3)
    }

    @Test
    fun mcpServersNeedHttpsAndRoundTrip() {
        assertThat(McpViewModel.validUrl("https://mcp.example.com/mcp")).isTrue()
        assertThat(McpViewModel.validUrl("http://mcp.example.com/mcp")).isFalse()
        assertThat(McpViewModel.validUrl("http://localhost:3000/mcp")).isTrue()
        assertThat(McpViewModel.validUrl("not a url")).isFalse()
        val servers = listOf(McpServerEntry("a", "Docs", "https://d.example/mcp", trusted = true), McpServerEntry("b", "Local", "http://localhost/mcp", headerName = "", enabled = false))
        assertThat(McpServerEntry.decode(McpServerEntry.encode(servers))).isEqualTo(servers)
        assertThat(McpServerEntry.decode("garbage")).isEmpty()
    }
}
