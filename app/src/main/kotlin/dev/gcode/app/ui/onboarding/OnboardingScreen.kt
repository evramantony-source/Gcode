package dev.gcode.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R

const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize().testTag("screen_onboarding")) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_code), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
            GeminiKeyStep(state, viewModel::onKeyChange, viewModel::verify)
        }
    }
}

@Composable
fun GeminiKeyStep(state: GeminiKeyState, onKeyChange: (String) -> Unit, onVerify: () -> Unit) {
    val uri = LocalUriHandler.current
    var visible by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.onboarding_gemini_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.onboarding_gemini_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uri.openUri(GEMINI_KEY_URL) }) { Text(stringResource(R.string.onboarding_get_key)) }
            OutlinedTextField(
                value = state.key,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth().testTag("gemini_key_field"),
                label = { Text(stringResource(R.string.onboarding_key_label)) },
                singleLine = true,
                enabled = !state.verifying,
                isError = state.error != null,
                supportingText = state.error?.let { { Text(it) } },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = stringResource(if (visible) R.string.hide_key else R.string.show_key),
                        )
                    }
                },
            )
            Text(stringResource(R.string.onboarding_restrict_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onVerify, enabled = state.key.isNotBlank() && !state.verifying, modifier = Modifier.testTag("verify_key")) {
                    Text(stringResource(R.string.onboarding_verify))
                }
                if (state.verifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
