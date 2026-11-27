package dev.gcode.app.ui.code

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.agent.workspace.WorkspaceFs
import dev.gcode.agent.workspace.WsEntry
import dev.gcode.app.editor.EditorSupport
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.app.workspace.WorkspaceProvider
import dev.gcode.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** An open file. [text] is the content loaded from disk (the editor view holds the live text). */
data class EditorTab(
    val path: String,
    val text: String,
    val dirty: Boolean = false,
    val readOnly: Boolean = false,
    val note: String? = null,
    /** Incremented when the file is reloaded from disk, so the editor view resets its text. */
    val version: Int = 0,
)

data class CodeUiState(
    val workspaceKey: String? = null,
    val workspaceName: String? = null,
    val supportsGit: Boolean = false,
    val children: Map<String, List<WsEntry>> = emptyMap(),
    val expanded: Set<String> = setOf(""),
    val tabs: List<EditorTab> = emptyList(),
    val active: String? = null,
    val showEditor: Boolean = false,
    val message: String? = null,
    val editorReady: Boolean = false,
)

/** File explorer and editor tabs for the open workspace (ARCHITECTURE.md section 12, screen 4). */
@HiltViewModel
class CodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaces: WorkspaceProvider,
    settings: SettingsRepository,
    workspaceGit: WorkspaceGit,
) : ViewModel() {
    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()
    private var fs: WorkspaceFs? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { runCatching { EditorSupport.init(context) } }
            _state.update { it.copy(editorReady = true) }
        }
        viewModelScope.launch {
            combine(settings.settings.map { it.activeRepo to it.safFolderUri }, workspaceGit.activeRepo) { a, _ -> a }
                .distinctUntilChanged()
                .collect { reloadWorkspace() }
        }
    }

    private suspend fun reloadWorkspace() {
        val ws = workspaces.current()
        fs = ws?.fs
        _state.value = CodeUiState(
            workspaceKey = ws?.key,
            workspaceName = ws?.fs?.displayName,
            supportsGit = ws?.git != null,
            editorReady = _state.value.editorReady,
        )
        if (ws != null) loadDir("")
    }

    fun refreshTree() = viewModelScope.launch {
        val dirs = _state.value.expanded
        _state.update { it.copy(children = emptyMap()) }
        dirs.forEach { loadDir(it) }
    }

    private suspend fun loadDir(path: String) {
        val f = fs ?: return
        val entries = runCatching { f.list(path) }.getOrElse { e -> _state.update { it.copy(message = e.message) }; return }
        _state.update { it.copy(children = it.children + (path to entries)) }
    }

    fun toggleDir(path: String) = viewModelScope.launch {
        val s = _state.value
        if (path in s.expanded) {
            _state.update { it.copy(expanded = it.expanded - path) }
        } else {
            _state.update { it.copy(expanded = it.expanded + path) }
            if (path !in s.children) loadDir(path)
        }
    }

    fun openFile(path: String) = viewModelScope.launch {
        val existing = _state.value.tabs.firstOrNull { it.path == path }
        if (existing != null) {
            _state.update { it.copy(active = path, showEditor = true) }
            reloadIfChanged(path)
            return@launch
        }
        val f = fs ?: return@launch
        val size = runCatching { f.size(path) }.getOrDefault(0)
        if (size > MAX_EDIT_BYTES * 4) {
            _state.update { it.copy(message = "${path.substringAfterLast('/')} is too large to open (${size / 1024} KB).") }
            return@launch
        }
        val bytes = runCatching { f.readBytes(path) }.getOrElse { e -> _state.update { it.copy(message = e.message) }; return@launch }
        if (bytes.take(8_000).any { it == 0.toByte() }) {
            _state.update { it.copy(message = "${path.substringAfterLast('/')} is a binary file.") }
            return@launch
        }
        val readOnly = size > MAX_EDIT_BYTES
        val tab = EditorTab(path, bytes.toString(Charsets.UTF_8), readOnly = readOnly, note = if (readOnly) "Large file: opened read-only" else null)
        _state.update { it.copy(tabs = it.tabs + tab, active = path, showEditor = true) }
    }

    /** Reloads a clean tab when the file changed on disk (e.g. the agent edited it). */
    fun reloadIfChanged(path: String) = viewModelScope.launch {
        val tab = _state.value.tabs.firstOrNull { it.path == path } ?: return@launch
        if (tab.dirty) return@launch
        val disk = runCatching { fs?.readText(path) }.getOrNull() ?: return@launch
        if (disk != tab.text) updateTab(path) { it.copy(text = disk, version = it.version + 1) }
    }

    fun select(path: String) {
        _state.update { it.copy(active = path, showEditor = true) }
        reloadIfChanged(path)
    }

    fun showExplorer() = _state.update { it.copy(showEditor = false) }

    fun markDirty(path: String, dirty: Boolean) {
        if (_state.value.tabs.firstOrNull { it.path == path }?.dirty != dirty) updateTab(path) { it.copy(dirty = dirty) }
    }

    fun save(path: String, text: String) = viewModelScope.launch {
        val f = fs ?: return@launch
        runCatching { f.writeText(path, text) }
            .onSuccess { updateTab(path) { it.copy(text = text, dirty = false) }; _state.update { it.copy(message = "Saved ${path.substringAfterLast('/')}") } }
            .onFailure { e -> _state.update { it.copy(message = "Save failed: ${e.message}") } }
    }

    fun close(path: String) = _state.update { s ->
        val tabs = s.tabs.filterNot { it.path == path }
        s.copy(tabs = tabs, active = if (s.active == path) tabs.lastOrNull()?.path else s.active, showEditor = tabs.isNotEmpty() && s.showEditor)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun updateTab(path: String, f: (EditorTab) -> EditorTab) =
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.path == path) f(it) else it }) }

    private companion object {
        const val MAX_EDIT_BYTES = 1_500_000L
    }
}
