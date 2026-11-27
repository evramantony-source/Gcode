package dev.gcode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.gcode.app.ui.GCodeAppUi
import dev.gcode.app.ui.onboarding.OnboardingScreen
import dev.gcode.app.ui.root.RootState
import dev.gcode.app.ui.root.RootViewModel
import dev.gcode.app.ui.theme.GCodeTheme
import dev.gcode.core.data.settings.ThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by rootViewModel.state.collectAsStateWithLifecycle()
            val ready = state as? RootState.Ready
            val dark = when (ready?.settings?.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }
            GCodeTheme(darkTheme = dark, dynamicColor = ready?.settings?.dynamicColor ?: true) {
                when {
                    ready == null -> Surface(Modifier.fillMaxSize()) {}
                    ready.needsOnboarding -> OnboardingScreen()
                    else -> GCodeAppUi()
                }
            }
        }
    }
}
