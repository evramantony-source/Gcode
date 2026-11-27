package dev.gcode.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.di.GeminiTransportFactory
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GeminiKeyState(
    val key: String = "",
    val verifying: Boolean = false,
    val error: String? = null,
    val modelCount: Int? = null,
)

/** Verifies a Gemini key with models.list before storing it (ARCHITECTURE.md section 12). */
class GeminiKeyVerifier @Inject constructor(
    private val transports: GeminiTransportFactory,
    private val secrets: SecretStore,
    private val models: ModelRepository,
) {
    /** Returns the number of generateContent models, or throws with a user-readable message. */
    suspend fun verifyAndSave(rawKey: String): Int {
        val key = rawKey.trim()
        require(key.length >= 20 && key.none { it.isWhitespace() }) { "That does not look like a Gemini API key." }
        val list = transports.create { key }.listModels()
        val usable = list.count { it.supportsGenerateContent }
        if (usable == 0) error("The key works, but no generateContent models are available to it.")
        secrets.put(SecretKey.GEMINI_API_KEY, key)
        models.refreshIfStale(force = true)
        return usable
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val verifier: GeminiKeyVerifier,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GeminiKeyState())
    val state: StateFlow<GeminiKeyState> = _state.asStateFlow()

    fun onKeyChange(value: String) = _state.update { it.copy(key = value, error = null) }

    fun verify() {
        val key = _state.value.key
        _state.update { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            runCatching { verifier.verifyAndSave(key) }
                .onSuccess { count ->
                    _state.update { it.copy(verifying = false, modelCount = count) }
                    settings.update { it.copy(onboardingComplete = true) }
                }
                .onFailure { e -> _state.update { it.copy(verifying = false, error = AgentSessionManager.describe(e)) } }
        }
    }
}
