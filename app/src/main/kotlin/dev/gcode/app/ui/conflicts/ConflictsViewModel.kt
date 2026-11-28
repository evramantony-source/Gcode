package dev.gcode.app.ui.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.ai.AiAssistant
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.app.workspace.WorkspaceProvider
import dev.gcode.git.ConflictSegment
import dev.gcode.git.Conflicts
import dev.gcode.git.Resolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ConflictsUiState(
    val merging: Boolean = false,
    val files: List<String> = emptyList(),
    val selected: String? = null,
    /** Null for the selected file when it has no text markers (binary, delete/modify): take a whole side. */
    val segments: List<ConflictSegment>? = null,
    val resolutions: Map<Int, Resolution> = emptyMap(),
    val aiBusy: Set<Int> = emptySet(),
    val aiProposals: Map<Int, String> = emptyMap(),
    /** Hunk waiting for the user's OK to send a protected file's lines to Gemini, and why it is protected. */
    val secretConfirm: Pair<Int, String>? = null,
    val mergeMessage: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val hunkCount: Int get() = segments?.let { Conflicts.hunks(it).size } ?: 0
    val allResolved: Boolean get() = segments != null && hunkCount > 0 && resolutions.size == hunkCount
}

/** Merge conflict resolver (ARCHITECTURE.md section 8.3): per hunk Ours / Theirs / Both / Edit / Ask AI. */
@HiltViewModel
class ConflictsViewModel @Inject constructor(
    private val git: WorkspaceGit,
    private val workspaces: WorkspaceProvider,
    private val ai: AiAssistant,
) : ViewModel() {
    private val _state = MutableStateFlow(ConflictsUiState())
    val state: StateFlow<ConflictsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() = launch {
        val (merging, files, message) = git.withRepo { r, _ -> Triple(r.isMerging(), r.conflicts(), r.mergeMessage()) }
        _state.update { s ->
            s.copy(
                merging = merging,
                files = files,
                mergeMessage = s.mergeMessage.ifEmpty { message.orEmpty() },
                selected = s.selected?.takeIf { it in files },
            )
        }
        val selected = _state.value.selected ?: files.firstOrNull()
        if (selected != null && selected != _state.value.selected) select(selected) else if (selected == null) _state.update { it.copy(segments = null) }
    }

    fun select(path: String) = launch {
        val dir = git.activeDir() ?: return@launch
        val text = withContext(Dispatchers.IO) { File(dir, path).takeIf { it.isFile && it.length() < MAX_TEXT }?.readText() }
        val segments = text?.takeIf { Conflicts.hasMarkers(it) }?.let(Conflicts::parse)
        _state.update { it.copy(selected = path, segments = segments, resolutions = emptyMap(), aiProposals = emptyMap(), aiBusy = emptySet()) }
    }

    fun choose(index: Int, resolution: Resolution) = _state.update { it.copy(resolutions = it.resolutions + (index to resolution), aiProposals = it.aiProposals - index) }

    fun clear(index: Int) = _state.update { it.copy(resolutions = it.resolutions - index) }

    /** Sends the hunk with nearby text to Gemini. Protected files (denylist, .gitignore) need explicit approval first. */
    fun askAi(index: Int, approved: Boolean = false) = launch {
        val s = _state.value
        val path = s.selected ?: return@launch
        if (!approved) {
            val reason = workspaces.current()?.guard?.reason(path)
            if (reason != null) {
                _state.update { it.copy(secretConfirm = index to reason) }
                return@launch
            }
        }
        val segments = s.segments ?: return@launch
        val position = segments.indexOfFirst { it is ConflictSegment.Hunk && it.hunk.index == index }
        val hunk = (segments[position] as ConflictSegment.Hunk).hunk
        val before = (segments.getOrNull(position - 1) as? ConflictSegment.Text)?.text?.lines()?.takeLast(CONTEXT_LINES)?.joinToString("\n").orEmpty()
        val after = (segments.getOrNull(position + 1) as? ConflictSegment.Text)?.text?.lines()?.take(CONTEXT_LINES)?.joinToString("\n").orEmpty()
        _state.update { it.copy(aiBusy = it.aiBusy + index, secretConfirm = null) }
        try {
            val proposal = ai.resolveConflict(path, hunk, before, after)
            _state.update { it.copy(aiProposals = it.aiProposals + (index to proposal)) }
        } finally {
            _state.update { it.copy(aiBusy = it.aiBusy - index) }
        }
    }

    fun dismissSecretConfirm() = _state.update { it.copy(secretConfirm = null) }

    fun acceptProposal(index: Int, text: String) = choose(index, Resolution.Custom(text))

    fun discardProposal(index: Int) = _state.update { it.copy(aiProposals = it.aiProposals - index) }

    /** Writes the resolved file and stages it. */
    fun saveFile() = launch {
        val s = _state.value
        val path = s.selected ?: return@launch
        val text = Conflicts.resolve(s.segments ?: return@launch, s.resolutions)
        git.withRepo { r, repo ->
            File(repo.path, path).writeText(text)
            r.add(listOf(path))
        }
        _state.update { it.copy(selected = null, segments = null, resolutions = emptyMap()) }
        load()
    }

    /** For files without text markers: keep our or their version (or the deletion). */
    fun takeSide(ours: Boolean) = launch {
        val path = _state.value.selected ?: return@launch
        git.withRepo { r, _ -> r.takeSide(path, ours) }
        _state.update { it.copy(selected = null, segments = null) }
        load()
    }

    fun setMergeMessage(text: String) = _state.update { it.copy(mergeMessage = text) }

    fun commitMerge(onDone: () -> Unit) = launch {
        val message = _state.value.mergeMessage.ifBlank { "Merge" }
        val author = git.author(git.active())
        git.withRepo { r, _ ->
            check(r.conflicts().isEmpty()) { "Resolve every file first" }
            r.commit(message, author)
        }
        onDone()
    }

    fun abortMerge(onDone: () -> Unit) = launch {
        git.withRepo { r, _ -> r.abortMerge() }
        onDone()
    }

    fun dismiss() = _state.update { it.copy(message = null, error = null) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        private const val CONTEXT_LINES = 30
        private const val MAX_TEXT = 2_000_000L
    }
}
