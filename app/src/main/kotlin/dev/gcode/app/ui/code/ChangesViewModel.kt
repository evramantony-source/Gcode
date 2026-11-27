package dev.gcode.app.ui.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.git.GitStatus
import dev.gcode.git.MergeOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangesUiState(
    val repo: LocalRepoEntity? = null,
    val status: GitStatus? = null,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val diffTitle: String? = null,
    val diff: String? = null,
)

/** Source control for the open repository: status, stage, commit, push, pull, fetch. */
@HiltViewModel
class ChangesViewModel @Inject constructor(private val git: WorkspaceGit) : ViewModel() {
    private val local = MutableStateFlow(ChangesUiState())

    val state: StateFlow<ChangesUiState> = combine(local, git.activeRepo) { l, repo -> l.copy(repo = repo) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChangesUiState())

    init {
        viewModelScope.launch { git.activeRepo.collect { refresh() } }
    }

    fun refresh() = run("status", report = false) { repo, _ -> repo.status() }

    fun stage(paths: List<String>) = run("stage", report = false) { repo, _ -> repo.add(paths); repo.status() }
    fun unstage(paths: List<String>) = run("unstage", report = false) { repo, _ -> repo.unstage(paths); repo.status() }
    fun stageAll() = run("stage", report = false) { repo, _ -> repo.addAll(); repo.status() }

    fun commit(message: String) = run("commit") { repo, entity ->
        val info = repo.commit(message, git.author(entity))
        local.update { it.copy(message = "Committed ${info.shortSha}: ${info.title}") }
        repo.status()
    }

    fun push() = run("push") { repo, _ ->
        val outcome = repo.push(git.credentials())
        local.update { it.copy(message = if (outcome.ok) "Pushed ${repo.currentBranch()}" else null, error = outcome.message?.takeIf { !outcome.ok }) }
        repo.status()
    }

    fun pull() = run("pull") { repo, entity ->
        val outcome = repo.pull(git.credentials(), rebase = entity.pullRebase)
        local.update {
            when (outcome) {
                is MergeOutcome.Conflicts -> it.copy(error = "Pull stopped with conflicts in: ${outcome.paths.joinToString()}")
                is MergeOutcome.Failed -> it.copy(error = "Pull failed: ${outcome.reason}")
                MergeOutcome.AlreadyUpToDate -> it.copy(message = "Already up to date")
                is MergeOutcome.Merged -> it.copy(message = if (outcome.fastForward) "Fast-forwarded" else "Merged remote changes")
            }
        }
        repo.status()
    }

    fun fetch() = run("fetch") { repo, _ ->
        val updates = repo.fetch(git.credentials())
        local.update { it.copy(message = if (updates.isEmpty()) "No new commits" else "Fetched: ${updates.size} refs updated") }
        repo.status()
    }

    fun showDiff(path: String, staged: Boolean) = viewModelScope.launch {
        val diff = runCatching { git.withRepo { repo, _ -> repo.diff(staged = staged, paths = listOf(path)) } }.getOrElse { it.message.orEmpty() }
        local.update { it.copy(diffTitle = path, diff = diff.ifBlank { "(new or binary file: no text diff)" }) }
    }

    fun closeDiff() = local.update { it.copy(diff = null, diffTitle = null) }
    fun dismiss() = local.update { it.copy(message = null, error = null) }

    private fun run(label: String, report: Boolean = true, block: suspend (dev.gcode.git.JGitRepository, LocalRepoEntity) -> GitStatus) {
        if (git.let { local.value.busy != null }) return
        local.update { it.copy(busy = label, error = if (report) null else it.error) }
        viewModelScope.launch {
            runCatching { git.withRepo(block) }
                .onSuccess { status -> local.update { it.copy(status = status, busy = null) } }
                .onFailure { e -> local.update { it.copy(busy = null, error = e.message ?: e::class.simpleName) } }
        }
    }
}
