package dev.gcode.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.agent.ActiveSession
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.agent.LiveTurn
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.db.SessionEntity
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.repo.StoredMessage
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val session: SessionEntity? = null,
    val messages: List<StoredMessage> = emptyList(),
    val live: LiveTurn = LiveTurn(),
    val settings: AppSettings = AppSettings(),
    val catalog: ModelCatalog = ModelCatalog(emptyList()),
    val activeModel: String? = null,
    val fallbackReason: String? = null,
    val contextPercent: Int = 0,
    val refreshingModels: Boolean = false,
    val modelError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val manager: AgentSessionManager,
    private val models: ModelRepository,
    private val settingsRepo: SettingsRepository,
    private val selector: ModelSelector,
    private val active: ActiveSession,
) : ViewModel() {

    private val modelStatus = MutableStateFlow(false to (null as String?))

    private val sessionFlow = active.id.filterNotNull().flatMapLatest { sessions.observeSession(it) }
    private val messagesFlow = active.id.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else sessions.observeMessages(id) }

    val state: StateFlow<ChatUiState> = combine(
        combine(sessionFlow, messagesFlow) { s, m -> s to m },
        manager.live,
        settingsRepo.settings,
        models.catalog,
        modelStatus,
    ) { (session, messages), live, settings, catalog, (refreshing, modelError) ->
        val turn = session?.let { live[it.id] } ?: LiveTurn()
        val selection = selector.select(catalog, settings.preferredModel, System.currentTimeMillis())
        val activeModel = turn.activeModel ?: session?.modelId?.takeIf { turn.running } ?: selection?.modelId ?: settings.preferredModel
        val limit = catalog.find(activeModel)?.inputTokenLimit ?: session?.contextLimit ?: 0
        val used = session?.lastPromptTokens ?: 0
        ChatUiState(
            session = session,
            messages = messages.filter { it.kind != MessageEntity.KIND_TOOL_RESULTS || it.content.functionCalls.isNotEmpty() },
            live = turn,
            settings = settings,
            catalog = catalog,
            activeModel = activeModel,
            fallbackReason = selection?.reason?.takeIf { selection.isFallback && activeModel == selection.modelId },
            contextPercent = ContextUsage(used, limit).percent,
            refreshingModels = refreshing,
            modelError = modelError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            if (active.id.value == null) active.open((sessions.latest() ?: sessions.create()).id)
            runCatching { models.refreshIfStale() }
        }
    }

    fun send(text: String) {
        val id = active.id.value ?: return
        if (text.isBlank()) return
        manager.send(id, Content.user(text.trim()))
    }

    fun stop() = active.id.value?.let(manager::stop)

    fun retry() = active.id.value?.let(manager::retry)

    fun dismissError() = active.id.value?.let(manager::clearError)

    fun newChat() {
        viewModelScope.launch {
            val current = state.value
            // Reuse an empty session instead of piling up blank ones.
            if (current.session != null && current.messages.isEmpty()) return@launch
            active.open(sessions.create(modelId = current.activeModel).id)
        }
    }

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
