package dev.gcode.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            dynamicColor = p[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            preferredModel = p[PREFERRED_MODEL] ?: defaults.preferredModel,
            thinkingLevel = p[THINKING_LEVEL],
            showThoughts = p[SHOW_THOUGHTS] ?: defaults.showThoughts,
            autoSwitchModels = p[AUTO_SWITCH] ?: defaults.autoSwitchModels,
            compactionThresholdPercent = (p[COMPACTION] ?: defaults.compactionThresholdPercent).coerceIn(50, 95),
            modelCacheHours = (p[MODEL_CACHE_HOURS] ?: defaults.modelCacheHours).coerceIn(1, 168),
            rejectedThinking = p[REJECTED_THINKING] ?: emptySet(),
            onboardingComplete = p[ONBOARDING_COMPLETE] ?: false,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[THEME] = s.theme.name
        p[DYNAMIC_COLOR] = s.dynamicColor
        p[PREFERRED_MODEL] = s.preferredModel
        if (s.thinkingLevel == null) p.remove(THINKING_LEVEL) else p[THINKING_LEVEL] = s.thinkingLevel
        p[SHOW_THOUGHTS] = s.showThoughts
        p[AUTO_SWITCH] = s.autoSwitchModels
        p[COMPACTION] = s.compactionThresholdPercent
        p[MODEL_CACHE_HOURS] = s.modelCacheHours
        p[REJECTED_THINKING] = s.rejectedThinking
        p[ONBOARDING_COMPLETE] = s.onboardingComplete
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PREFERRED_MODEL = stringPreferencesKey("preferred_model")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val SHOW_THOUGHTS = booleanPreferencesKey("show_thoughts")
        val AUTO_SWITCH = booleanPreferencesKey("auto_switch_models")
        val COMPACTION = intPreferencesKey("compaction_threshold")
        val MODEL_CACHE_HOURS = intPreferencesKey("model_cache_hours")
        val REJECTED_THINKING = stringSetPreferencesKey("rejected_thinking")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
