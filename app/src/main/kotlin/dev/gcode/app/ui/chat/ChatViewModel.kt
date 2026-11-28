package dev.gcode.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.tools.TodoItem
import dev.gcode.agent.tools.TodoJson
import dev.gcode.app.agent.ActiveSession
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.agent.LiveTurn
import dev.gcode.app.agent.RewindPreview
import dev.gcode.app.agent.RewindService
import dev.gcode.app.agent.WorkspaceExtensions
import dev.gcode.agent.extend.CommandAction
import dev.gcode.agent.extend.SlashCommand
import dev.gcode.agent.extend.SlashCommands
import dev.gcode.gemini.model.Part
import kotlinx.coroutines.flow.update
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.db.SessionEntity
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.settings.AppSettings
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.ContextUsage
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.select.ModelCatalog
import dev.gcode.gemini.select.ModelSelector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val session: SessionEntity? = null,
    val items: List<ChatItem> = emptyList(),
    val live: LiveTurn = LiveTurn(),
    val settings: AppSettings = AppSettings(),
    val catalog: ModelCatalog = ModelCatalog(emptyList()),
    val activeModel: String? = null,
    val fallbackReason: String? = null,
    val contextPercent: Int = 0,
    val refreshingModels: Boolean = false,
    val modelError: String? = null,
    val mode: PermissionMode = PermissionMode.ASK,
    val repo: LocalRepoEntity? = null,
    val todos: List<TodoItem> = emptyList(),
    val extras: ChatExtras = ChatExtras(),
)

/** Composer and dialog state that is not part of the saved session. */
data class ChatExtras(
    val commands: List<SlashCommand> = emptyList(),
    val showHelp: Boolean = false,
    val compacting: Boolean = false,
    val rewind: RewindPreview? = null,
    /** Text to put back into the composer (after a conversation rewind). */
    val prefill: String? = null,
    val message: String? = null,
)

/** An image attached to the next message (already downscaled and base64-encoded). */
data class Attachment(val mimeType: String, val base64: String, val label: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val manager: AgentSessionManager,
    private val models: ModelRepository,
    private val settingsRepo: SettingsRepository,
    private val selector: ModelSelector,
    private val active: ActiveSession,
    private val workspaceGit: WorkspaceGit,
    private val extensions: WorkspaceExtensions,
    private val rewinder: RewindService,
) : ViewModel() {

    private val modelStatus = MutableStateFlow(false to (null as String?))
    private val extras = MutableStateFlow(ChatExtras())
    private val sessionFlow = active.id.filterNotNull().flatMapLatest { sessions.observeSession(it) }
    private val messagesFlow = active.id.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else sessions.observeMessages(id) }

    val state: StateFlow<ChatUiState> = combine(
        combine(sessionFlow, messagesFlow, workspaceGit.activeRepo) { s, m, r -> Triple(s, m, r) },
        manager.live,
        settingsRepo.settings,
        combine(models.catalog, modelStatus, extras) { c, m, e -> Triple(c, m, e) },
    ) { (session, messages, repo), live, settings, (catalog, status, extra) ->
        val (refreshing, modelError) = status
        val turn = session?.let { live[it.id] } ?: LiveTurn()
        val selection = selector.select(catalog, settings.preferredModel, System.currentTimeMillis())
        val activeModel = turn.activeModel ?: selection?.modelId ?: settings.preferredModel
        val limit = catalog.find(activeModel)?.inputTokenLimit ?: session?.contextLimit ?: 0
        ChatUiState(
            session = session,
            items = ChatItems.build(messages, AgentSessionManager::summarizeArgs),
            live = turn,
            settings = settings,
            catalog = catalog,
            activeModel = activeModel,
            fallbackReason = selection?.reason?.takeIf { selection.isFallback && activeModel == selection.modelId },
            contextPercent = ContextUsage(session?.lastPromptTokens ?: 0, limit).percent,
            refreshingModels = refreshing,
            modelError = modelError,
            mode = PermissionMode.fromId(session?.mode),
            repo = repo,
            todos = TodoJson.decode(session?.todoJson),
            extras = extra,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            if (active.id.value == null) active.open((sessions.latest() ?: newSession()).id)
            runCatching { models.refreshIfStale() }
        }
        // Project slash commands follow the open workspace.
        viewModelScope.launch { workspaceGit.activeRepo.collect { refreshCommands() } }
    }

    fun refreshCommands() = viewModelScope.launch {
        val commands = runCatching { extensions.commands() }.getOrDefault(emptyList())
        extras.update { it.copy(commands = commands) }
    }

    /** New sessions start in the open repository's default mode, else the global default (never Full auto). */
    private suspend fun newSession(): SessionEntity =
        sessions.create(mode = workspaceGit.active()?.defaultMode?.takeIf { it != PermissionMode.FULL_AUTO.id } ?: settingsRepo.current().defaultMode)

    /** Sends a message, or runs a slash command (built-in or from `.gcode/commands`). */
    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val id = active.id.value ?: return
        if (text.isBlank() && attachments.isEmpty()) return
        when (val action = SlashCommands.resolve(text, extras.value.commands)) {
            null -> manager.send(id, userContent(text.trim(), attachments))
            is CommandAction.Prompt -> manager.send(id, userContent(action.text, attachments))
            CommandAction.Compact -> compact()
            CommandAction.Clear -> newChat()
            CommandAction.Help -> extras.update { it.copy(showHelp = true) }
            is CommandAction.Unknown -> extras.update { it.copy(message = "Unknown command /${action.name}. Type / to see the available commands.") }
        }
    }

    /** /init: asks the agent to write or improve GEMINI.md. */
    fun initMemory() = send("/init")

    fun compact() {
        val id = active.id.value ?: return
        viewModelScope.launch {
            extras.update { it.copy(compacting = true) }
            val message = runCatching { if (manager.compactNow(id)) null else "Nothing to compact yet." }
                .getOrElse { e -> "Compaction failed: ${AgentSessionManager.describe(e)}" }
            extras.update { it.copy(compacting = false, message = message) }
        }
    }

    fun previewRewind(seq: Int) {
        val id = active.id.value ?: return
        if (manager.isRunning(id)) {
            extras.update { it.copy(message = "Stop the agent before rewinding.") }
            return
        }
        viewModelScope.launch {
            runCatching { rewinder.preview(id, seq) }
                .onSuccess { preview -> extras.update { it.copy(rewind = preview) } }
                .onFailure { e -> extras.update { it.copy(message = e.message) } }
        }
    }

    fun rewind(code: Boolean, conversation: Boolean) {
        val id = active.id.value ?: return
        val preview = extras.value.rewind ?: return
        extras.update { it.copy(rewind = null) }
        viewModelScope.launch {
            runCatching { rewinder.rewind(id, preview.seq, code, conversation) }
                .onSuccess { restored ->
                    val parts = listOfNotNull(
                        if (code) "Restored ${restored.size} file(s)." else null,
                        if (conversation) "The conversation was cut back to before your message." else null,
                    )
                    extras.update { it.copy(message = parts.joinToString(" "), prefill = if (conversation) preview.userText else null) }
                }
                .onFailure { e -> extras.update { it.copy(message = e.message) } }
        }
    }

    fun dismissRewind() = extras.update { it.copy(rewind = null) }
    fun dismissHelp() = extras.update { it.copy(showHelp = false) }
    fun dismissMessage() = extras.update { it.copy(message = null) }
    fun showMessage(text: String) = extras.update { it.copy(message = text) }
    fun consumePrefill() = extras.update { it.copy(prefill = null) }

    private fun userContent(text: String, attachments: List<Attachment>): Content {
        val parts = attachments.map { Part.inlineData(it.mimeType, it.base64) } + listOfNotNull(text.takeIf { it.isNotBlank() }?.let(Part::text))
        return Content.user(*parts.toTypedArray())
    }

    fun stop() = active.id.value?.let(manager::stop)
    fun retry() = active.id.value?.let(manager::retry)
    fun dismissError() = active.id.value?.let(manager::clearError)

    fun newChat() {
        viewModelScope.launch {
            val current = state.value
            if (current.session != null && current.items.isEmpty()) return@launch
            active.open(newSession().id)
        }
    }

    fun setMode(mode: PermissionMode) = viewModelScope.launch { active.id.value?.let { sessions.setMode(it, mode.id) } }

    fun selectModel(id: String) = viewModelScope.launch { settingsRepo.update { it.copy(preferredModel = id) } }
    fun setThinkingLevel(wire: String?) = viewModelScope.launch { settingsRepo.update { it.copy(thinkingLevel = wire) } }
    fun setShowThoughts(show: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(showThoughts = show) } }

    fun refreshModels() {
        modelStatus.value = true to null
        viewModelScope.launch {
            val error = runCatching { models.refreshIfStale(force = true) }.exceptionOrNull()?.let(AgentSessionManager::describe)
            modelStatus.value = false to error
        }
    }
}
