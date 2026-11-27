package dev.gcode.app.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.AppSettings
import dev.gcode.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RootState {
    data object Loading : RootState
    data class Ready(val settings: AppSettings, val needsOnboarding: Boolean) : RootState
}

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository, secrets: SecretStore) : ViewModel() {
    val state: StateFlow<RootState> = combine(settings.settings, secrets.observePresent(SecretKey.GEMINI_API_KEY)) { s, hasKey ->
        RootState.Ready(s, needsOnboarding = !hasKey || !s.onboardingComplete)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)
}
