package dev.gcode.app.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.github.api.IssuesApi
import dev.gcode.github.api.PullsApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhPullFile
import dev.gcode.github.model.MergePullRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrDetail(val pr: GhPull, val files: List<GhPullFile> = emptyList(), val comments: List<GhComment> = emptyList(), val loading: Boolean = true)

/** Merge confirmation; [intoDefault] uses the always-confirm wording (ARCHITECTURE.md section 7.2). */
data class PrMergeConfirm(val pr: GhPull, val method: String, val intoDefault: Boolean) {
    companion object {
        /** Unknown default branch counts as the default branch, so the stronger warning is shown. */
        fun of(pr: GhPull, method: String, defaultBranch: String?) = PrMergeConfirm(pr, method, defaultBranch == null || pr.base.ref == defaultBranch)
    }
}

data class PullsUiState(
    val target: CollabTarget = CollabTarget(null, null, false),
    val defaultBranch: String? = null,
    val filter: String = "open",
    val items: List<GhPull> = emptyList(),
    val loading: Boolean = false,
    val detail: PrDetail? = null,
    val branches: List<String> = emptyList(),
    val currentBranch: String? = null,
    val showCreate: Boolean = false,
    val confirmMerge: PrMergeConfirm? = null,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class PullRequestsViewModel @Inject constructor(
    targets: CollabTargets,
    private val pulls: PullsApi,
    private val issues: IssuesApi,
    private val repos: ReposApi,
    private val git: WorkspaceGit,
) : ViewModel() {
    private val _state = MutableStateFlow(PullsUiState())
    val state: StateFlow<PullsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            targets.target.collect { t ->
                _state.update { PullsUiState(target = t, filter = it.filter) }
                refresh()
            }
        }
    }

    private val ref get() = _state.value.target.ref?.takeIf { _state.value.target.signedIn }

    fun refresh() = launch {
        val ref = ref ?: return@launch
        _state.update { it.copy(loading = true) }
        try {
            val list = pulls.pulls(ref, state = _state.value.filter, perPage = 50)
            val defaultBranch = _state.value.defaultBranch ?: repos.repo(ref).defaultBranch
            _state.update { it.copy(items = list, defaultBranch = defaultBranch) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun open(number: Int) = launch {
        val ref = ref ?: return@launch
        val pr = pulls.pull(ref, number)
        _state.update { it.copy(detail = PrDetail(pr)) }
        val files = pulls.pullFiles(ref, number, maxPages = 5)
        val comments = issues.comments(ref, number)
        // mergeable is null until GitHub has computed it; read once more if needed.
        val fresh = if (pr.mergeable == null && pr.state == "open") {
            delay(2_000)
            pulls.pull(ref, number)
        } else {
            pr
        }
        _state.update { s -> s.detail?.takeIf { it.pr.number == number }?.let { s.copy(detail = PrDetail(fresh, files, comments, loading = false)) } ?: s }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    fun comment(body: String) = launch {
        val ref = ref ?: return@launch
        val pr = _state.value.detail?.pr ?: return@launch
        issues.comment(ref, pr.number, body)
        open(pr.number).join()
    }

    fun requestMerge(method: String) {
        val pr = _state.value.detail?.pr ?: return
        _state.update { it.copy(confirmMerge = PrMergeConfirm.of(pr, method, it.defaultBranch)) }
    }

    fun confirmMerge() = launch {
        val ref = ref ?: return@launch
        val confirm = _state.value.confirmMerge ?: return@launch
        _state.update { it.copy(confirmMerge = null) }
        val result = pulls.mergePull(ref, confirm.pr.number, MergePullRequest(sha = confirm.pr.head.sha, mergeMethod = confirm.method))
        _state.update { it.copy(message = result.message.ifBlank { if (result.merged) "Merged" else "Not merged" }) }
        open(confirm.pr.number).join()
        refresh()
    }

    fun dismissMerge() = _state.update { it.copy(confirmMerge = null) }

    fun setOpen(open: Boolean) = launch {
        val ref = ref ?: return@launch
        val pr = _state.value.detail?.pr ?: return@launch
        pulls.updatePullState(ref, pr.number, open)
        open(pr.number).join()
        refresh()
    }

    fun showCreate(show: Boolean) {
        _state.update { it.copy(showCreate = show) }
        if (show) launch {
            val ref = ref ?: return@launch
            val branches = repos.branches(ref).map { it.name }
            val current = runCatching { git.withRepo { r, _ -> r.currentBranch() } }.getOrNull()
            _state.update { it.copy(branches = branches, currentBranch = current, defaultBranch = it.defaultBranch ?: repos.repo(ref).defaultBranch) }
        }
    }

    fun create(title: String, body: String, head: String, base: String, draft: Boolean) = launch {
        val ref = ref ?: return@launch
        val pr = pulls.createPull(ref, CreatePullRequest(title, head = head, base = base, body = body.ifBlank { null }, draft = draft))
        _state.update { it.copy(showCreate = false) }
        refresh().join()
        open(pr.number)
    }

    fun dismiss() = _state.update { it.copy(message = null, error = null) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString(), loading = false) }
        }
    }
}
