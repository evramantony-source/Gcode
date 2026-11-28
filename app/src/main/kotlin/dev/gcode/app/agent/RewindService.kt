package dev.gcode.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.agent.tools.Rewinder
import dev.gcode.app.workspace.WorkspaceProvider
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.repo.StoredMessage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a rewind to a user message would change and what it cannot undo. */
data class RewindPreview(
    val seq: Int,
    val userText: String,
    /** Files the agent changed since that message (restored by a code rewind). */
    val files: List<String>,
    /** Actions already done on GitHub or other services; a rewind does not undo them. */
    val remoteActions: List<String>,
    /** Local Git history changes (commits, merges, branch switches) that stay as they are. */
    val gitActions: List<String>,
)

/**
 * Checkpoints and Rewind (ARCHITECTURE.md section 7.5): restore files changed after a user message, cut the
 * conversation back to before it, or both.
 */
@Singleton
class RewindService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val db: GCodeDatabase,
    private val workspaces: WorkspaceProvider,
) {
    suspend fun preview(sessionId: String, seq: Int): RewindPreview {
        val messages = sessions.messages(sessionId)
        val user = messages.firstOrNull { it.seq == seq }
        val workspace = workspaces.current()
        val files = workspace?.let { store(sessionId, it.key).since(seq - 1).map { cp -> cp.path }.distinct() }.orEmpty()
        val (remote, git) = actionsAfter(messages, seq)
        return RewindPreview(seq, user?.content?.text.orEmpty(), files, remote, git)
    }

    /** Returns the restored files. With [conversation] the message and everything after it are removed. */
    suspend fun rewind(sessionId: String, seq: Int, code: Boolean, conversation: Boolean): List<String> {
        val restored = if (code) {
            val workspace = workspaces.current() ?: error("No workspace is open, so files cannot be restored")
            Rewinder.restore(store(sessionId, workspace.key), workspace.fs, seq - 1)
        } else {
            emptyList()
        }
        if (conversation) sessions.truncateFrom(sessionId, seq)
        return restored
    }

    private fun store(sessionId: String, key: String) = RoomCheckpointStore(sessionId, key, db, sessions, File(context.filesDir, "checkpoints"))

    companion object {
        private val REMOTE_TOOLS = setOf(
            "git_push", "gh_create_repo", "gh_fork_repo", "gh_create_pr", "gh_merge_pr", "gh_comment", "gh_create_issue",
            "gh_setup_build_workflow", "gh_trigger_workflow", "gh_create_release", "gh_install_apk",
        )
        private val GIT_TOOLS = setOf("git_commit", "git_merge", "git_checkout", "git_branch", "git_pull")

        /** Tool calls made at or after [seq] that a rewind cannot undo, split into remote and local Git actions. */
        fun actionsAfter(messages: List<StoredMessage>, seq: Int): Pair<List<String>, List<String>> {
            val calls = messages.filter { it.seq >= seq }.flatMap { it.content.functionCalls }
            fun describe(name: String, args: kotlinx.serialization.json.JsonObject): String {
                val detail = listOf("title", "message", "name", "branch", "tag", "repo", "number", "workflow")
                    .firstNotNullOfOrNull { key -> (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                return if (detail != null) "$name ($detail)" else name
            }
            val remote = calls.filter { it.name in REMOTE_TOOLS || it.name.startsWith("mcp__") }.map { describe(it.name, it.args) }
            val git = calls.filter { it.name in GIT_TOOLS }.map { describe(it.name, it.args) }
            return remote to git
        }
    }
}
