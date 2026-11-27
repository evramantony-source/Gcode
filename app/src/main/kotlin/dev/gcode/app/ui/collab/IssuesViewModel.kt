package dev.gcode.app.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.github.api.IssuesApi
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhIssue
import dev.gcode.github.model.UpdateIssueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IssueDetail(val issue: GhIssue, val comments: List<GhComment> = emptyList(), val loading: Boolean = true)

data class IssuesUiState(
    val target: CollabTarget = CollabTarget(null, null, false),
    val filter: String = "open",
    val items: List<GhIssue> = emptyList(),
    val loading: Boolean = false,
    val detail: IssueDetail? = null,
    val showCreate: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class IssuesViewModel @Inject constructor(targets: CollabTargets, private val issues: IssuesApi) : ViewModel() {
    private val _state = MutableStateFlow(IssuesUiState())
    val state: StateFlow<IssuesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            targets.target.collect { t ->
                _state.update { IssuesUiState(target = t, filter = it.filter) }
                refresh()
            }
        }
    }

    private val ref get() = _state.value.target.ref?.takeIf { _state.value.target.signedIn }

    fun refresh() = launch {
        val ref = ref ?: return@launch
        _state.update { it.copy(loading = true) }
        try {
            val list = issues.issues(ref, state = _state.value.filter, perPage = 50)
            _state.update { it.copy(items = list) }
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
        val issue = issues.issue(ref, number)
        _state.update { it.copy(detail = IssueDetail(issue)) }
        val comments = issues.comments(ref, number)
        _state.update { s -> s.detail?.takeIf { it.issue.number == number }?.let { s.copy(detail = IssueDetail(issue, comments, loading = false)) } ?: s }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    fun comment(body: String) = launch {
        val ref = ref ?: return@launch
        val issue = _state.value.detail?.issue ?: return@launch
        issues.comment(ref, issue.number, body)
        open(issue.number).join()
    }

    /** Closes with a reason (completed / not_planned), or reopens when [reason] is null. */
    fun setState(reason: String?) = launch {
        val ref = ref ?: return@launch
        val issue = _state.value.detail?.issue ?: return@launch
        val request = if (reason == null) UpdateIssueRequest(state = "open", stateReason = "reopened") else UpdateIssueRequest(state = "closed", stateReason = reason)
        issues.updateIssue(ref, issue.number, request)
        open(issue.number).join()
        refresh()
    }

    fun showCreate(show: Boolean) = _state.update { it.copy(showCreate = show) }

    fun create(title: String, body: String, labels: String) = launch {
        val ref = ref ?: return@launch
        val labelList = labels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val issue = issues.createIssue(ref, CreateIssueRequest(title, body = body.ifBlank { null }, labels = labelList.ifEmpty { null }))
        _state.update { it.copy(showCreate = false) }
        refresh().join()
        open(issue.number)
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
