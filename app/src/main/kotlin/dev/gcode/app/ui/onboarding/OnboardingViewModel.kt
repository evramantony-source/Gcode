package dev.gcode.app.ui.onboarding

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.di.GeminiTransportFactory
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.git.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { GEMINI, GITHUB, WORKSPACE }

data class GeminiKeyState(
    val key: String = "",
    val verifying: Boolean = false,
    val error: String? = null,
    val modelCount: Int? = null,
)

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.GEMINI,
    val gemini: GeminiKeyState = GeminiKeyState(),
    val workspacePath: String = "",
    val freeBytes: Long = 0,
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
    val account: GitHubAccountManager,
    private val workspace: WorkspaceManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState(workspacePath = workspace.root.absolutePath, freeBytes = freeBytes()))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun onKeyChange(value: String) = _state.update { it.copy(gemini = it.gemini.copy(key = value, error = null)) }

    fun verify() {
        val key = _state.value.gemini.key
        _state.update { it.copy(gemini = it.gemini.copy(verifying = true, error = null)) }
        viewModelScope.launch {
            runCatching { verifier.verifyAndSave(key) }
                .onSuccess { count -> _state.update { it.copy(step = OnboardingStep.GITHUB, gemini = it.gemini.copy(verifying = false, modelCount = count)) } }
                .onFailure { e -> _state.update { it.copy(gemini = it.gemini.copy(verifying = false, error = AgentSessionManager.describe(e))) } }
        }
    }

    fun toWorkspace() = _state.update { it.copy(step = OnboardingStep.WORKSPACE, freeBytes = freeBytes()) }

    fun finish() = viewModelScope.launch { settings.update { it.copy(onboardingComplete = true) } }

    private fun freeBytes(): Long = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0)
}
