package dev.gcode.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.ui.onboarding.GeminiKeyVerifier
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.AppSettings
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.core.data.settings.ThemeMode
import dev.gcode.gemini.select.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val catalog: ModelCatalog = ModelCatalog(emptyList()),
    val hasGeminiKey: Boolean = false,
    val keyDialog: KeyDialogState? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
)

data class KeyDialogState(val key: String = "", val verifying: Boolean = false, val error: String? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val models: ModelRepository,
    private val secrets: SecretStore,
    private val verifier: GeminiKeyVerifier,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(settingsRepo.settings, models.catalog, secrets.observePresent(SecretKey.GEMINI_API_KEY), local) { s, c, hasKey, l ->
        l.copy(settings = s, catalog = c, hasGeminiKey = hasKey)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun set(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }

    fun setTheme(mode: ThemeMode) = set { it.copy(theme = mode) }
    fun setDynamicColor(on: Boolean) = set { it.copy(dynamicColor = on) }
    fun setModel(id: String) = set { it.copy(preferredModel = id) }
    fun setThinkingLevel(wire: String?) = set { it.copy(thinkingLevel = wire) }
    fun setShowThoughts(on: Boolean) = set { it.copy(showThoughts = on) }
    fun setAutoSwitch(on: Boolean) = set { it.copy(autoSwitchModels = on) }
    fun setCompactionThreshold(percent: Int) = set { it.copy(compactionThresholdPercent = percent.coerceIn(50, 95)) }

    fun refreshModels() {
        local.value = local.value.copy(refreshing = true, message = null)
        viewModelScope.launch {
            val error = runCatching { models.refreshIfStale(force = true) }.exceptionOrNull()
            local.value = local.value.copy(refreshing = false, message = error?.let(AgentSessionManager::describe))
        }
    }

    fun openKeyDialog() { local.value = local.value.copy(keyDialog = KeyDialogState()) }
    fun closeKeyDialog() { local.value = local.value.copy(keyDialog = null) }
    fun onKeyChange(key: String) { local.value = local.value.copy(keyDialog = local.value.keyDialog?.copy(key = key, error = null)) }

    fun saveKey() {
        val dialog = local.value.keyDialog ?: return
        local.value = local.value.copy(keyDialog = dialog.copy(verifying = true, error = null))
        viewModelScope.launch {
            runCatching { verifier.verifyAndSave(dialog.key) }
                .onSuccess { local.value = local.value.copy(keyDialog = null) }
                .onFailure { e -> local.value = local.value.copy(keyDialog = dialog.copy(verifying = false, error = AgentSessionManager.describe(e))) }
        }
    }
}
