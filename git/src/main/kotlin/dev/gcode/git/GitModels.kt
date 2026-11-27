package dev.gcode.git

/** Working tree status relative to HEAD and the index. */
data class GitStatus(
    val branch: String?,
    val headSha: String?,
    val staged: List<FileChange>,
    val unstaged: List<FileChange>,
    val untracked: List<String>,
    val conflicting: List<String>,
    val ahead: Int,
    val behind: Int,
    val upstream: String?,
) {
    val isClean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicting.isEmpty()
    val changedCount: Int get() = (staged.map { it.path } + unstaged.map { it.path } + untracked + conflicting).toSet().size
}

data class FileChange(val path: String, val kind: ChangeKind)

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val timeEpochSeconds: Long,
    val parents: List<String>,
) {
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()
}

data class BranchInfo(val name: String, val isRemote: Boolean, val isCurrent: Boolean, val sha: String, val upstream: String? = null)

data class Author(val name: String, val email: String)

/** HTTPS credentials: GitHub accepts any username with a token as the password. */
data class GitCredentials(val token: String, val username: String = GitRemotes.TOKEN_USERNAME)

sealed interface MergeOutcome {
    data class Merged(val newHead: String?, val fastForward: Boolean) : MergeOutcome
    data object AlreadyUpToDate : MergeOutcome
    data class Conflicts(val paths: List<String>) : MergeOutcome
    data class Failed(val reason: String, val paths: List<String> = emptyList()) : MergeOutcome
}

data class PushOutcome(val ok: Boolean, val updates: List<String>, val message: String?)

data class GitProgress(val task: String, val done: Int, val total: Int)

class GitOperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
