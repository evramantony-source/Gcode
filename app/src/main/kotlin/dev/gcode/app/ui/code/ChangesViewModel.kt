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
import kotlinx.coroutines.flow.map
import android.net.Uri
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangesUiState(
    val repo: LocalRepoEntity? = null,
    val status: GitStatus? = null,
    val history: List<dev.gcode.git.CommitInfo> = emptyList(),
    val branches: List<dev.gcode.git.BranchInfo> = emptyList(),
    val merging: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val diffTitle: String? = null,
    val diff: String? = null,
)

/** Source control for the open repository: status, stage, commit, push, pull, fetch. */
@HiltViewModel
class ChangesViewModel @Inject constructor(private val git: WorkspaceGit, private val settings: dev.gcode.core.data.settings.SettingsRepository) : ViewModel() {
    private val local = MutableStateFlow(ChangesUiState())

    /** Display name of a linked device folder (SAF), when no repository is open. */
    val safFolder: StateFlow<String?> = settings.settings.map { s -> s.safFolderUri?.let { Uri.decode(it).substringAfterLast(':').substringAfterLast('/') .ifEmpty { it } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun linkFolder(uri: String) = viewModelScope.launch { settings.update { it.copy(safFolderUri = uri, activeRepo = null) } }
    fun unlinkFolder() = viewModelScope.launch { settings.update { it.copy(safFolderUri = null) } }

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

    fun loadHistory() = viewModelScope.launch {
        val log = runCatching { git.withRepo { repo, _ -> repo.log(200) } }.getOrElse { e -> local.update { it.copy(error = e.message) }; return@launch }
        local.update { it.copy(history = log) }
    }

    fun showCommit(sha: String, title: String) = viewModelScope.launch {
        val diff = runCatching { git.withRepo { repo, _ -> repo.commitDiff(sha) } }.getOrElse { it.message.orEmpty() }
        local.update { it.copy(diffTitle = "${sha.take(7)} $title", diff = diff) }
    }

    fun loadBranches() = viewModelScope.launch {
        val result = runCatching { git.withRepo { repo, _ -> repo.branches() to repo.isMerging() } }.getOrElse { e -> local.update { it.copy(error = e.message) }; return@launch }
        local.update { it.copy(branches = result.first, merging = result.second) }
    }

    fun createBranch(name: String) = run("branch") { repo, _ ->
        repo.createBranch(name.trim(), checkout = true)
        local.update { it.copy(message = "Created and switched to ${name.trim()}") }
        repo.status()
    }.also { loadBranchesAfter() }

    fun switchBranch(name: String) = run("checkout") { repo, _ ->
        repo.checkout(name)
        local.update { it.copy(message = "Switched to $name") }
        repo.status()
    }.also { loadBranchesAfter() }

    /** Always confirmed by the UI before it is called (branch deletion is in the always-confirm set). */
    fun deleteBranch(name: String, remote: Boolean) {
        if (remote) {
            local.update { it.copy(error = "Delete remote branches from the Pull requests / branches screen (GitHub).") }
            return
        }
        run("delete") { repo, _ ->
            repo.deleteBranch(name, force = true)
            local.update { it.copy(message = "Deleted $name") }
            repo.status()
        }.also { loadBranchesAfter() }
    }

    /** Merges into the current branch; the UI confirms first when that is the default branch. */
    fun merge(name: String) = run("merge") { repo, _ ->
        val outcome = repo.merge(name)
        local.update {
            when (outcome) {
                is MergeOutcome.Conflicts -> it.copy(error = "Merge conflicts in: ${outcome.paths.joinToString()}. Resolve them, then stage and commit, or abort.")
                is MergeOutcome.Failed -> it.copy(error = "Merge failed: ${outcome.reason}")
                MergeOutcome.AlreadyUpToDate -> it.copy(message = "Already up to date")
                is MergeOutcome.Merged -> it.copy(message = "Merged $name")
            }
        }
        repo.status()
    }.also { loadBranchesAfter() }

    fun abortMerge() = run("abort") { repo, _ ->
        repo.abortMerge()
        local.update { it.copy(message = "Merge aborted") }
        repo.status()
    }.also { loadBranchesAfter() }

    fun discard(path: String) = run("discard", report = false) { repo, _ -> repo.discard(listOf(path)); repo.status() }

    private fun loadBranchesAfter() = viewModelScope.launch {
        kotlinx.coroutines.delay(300)
        loadBranches()
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
