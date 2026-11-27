package dev.gcode.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.models.ModelPickerSheet
import dev.gcode.core.data.settings.ThemeMode

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val s = state.settings
    GCodeScreen(title = stringResource(R.string.more_settings), onBack = onBack, modifier = Modifier.testTag("screen_settings")) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_gemini))
            ListItem(
                modifier = Modifier.clickable(onClick = viewModel::openKeyDialog),
                supportingContent = { Text(stringResource(if (state.hasGeminiKey) R.string.key_stored else R.string.key_missing)) },
            ) { Text(stringResource(R.string.gemini_api_key)) }
            ListItem(
                modifier = Modifier.clickable { showPicker = true }.testTag("settings_model"),
                supportingContent = { Text(s.preferredModel + (s.thinkingLevel?.let { " · $it" } ?: "")) },
            ) { Text(stringResource(R.string.default_model)) }
            SwitchItem(stringResource(R.string.show_thoughts), stringResource(R.string.show_thoughts_body), s.showThoughts, viewModel::setShowThoughts)
            SwitchItem(stringResource(R.string.auto_switch), stringResource(R.string.auto_switch_body), s.autoSwitchModels, viewModel::setAutoSwitch)
            ListItem(supportingContent = {
                Column {
                    Text(stringResource(R.string.compaction_body, s.compactionThresholdPercent))
                    Slider(
                        value = s.compactionThresholdPercent.toFloat(),
                        onValueChange = { viewModel.setCompactionThreshold(it.toInt()) },
                        valueRange = 50f..95f,
                        steps = 8,
                    )
                }
            }) { Text(stringResource(R.string.compaction_title)) }
            ListItem(
                modifier = Modifier.clickable(onClick = viewModel::refreshModels),
                supportingContent = { Text(state.message ?: stringResource(R.string.models_count, state.catalog.chatModels.size)) },
            ) { Text(stringResource(if (state.refreshing) R.string.refreshing else R.string.refresh_models)) }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeMode.entries.forEach { mode ->
                ListItem(
                    modifier = Modifier.selectable(selected = s.theme == mode, onClick = { viewModel.setTheme(mode) }),
                    leadingContent = { RadioButton(selected = s.theme == mode, onClick = { viewModel.setTheme(mode) }) },
                ) { Text(stringResource(themeLabel(mode))) }
            }
            SwitchItem(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_body), s.dynamicColor, viewModel::setDynamicColor)
        }
    }
    if (showPicker) {
        ModelPickerSheet(
            catalog = state.catalog,
            selected = s.preferredModel,
            thinkingLevel = s.thinkingLevel,
            showThoughts = s.showThoughts,
            refreshing = state.refreshing,
            error = state.message,
            onSelect = viewModel::setModel,
            onThinkingLevel = viewModel::setThinkingLevel,
            onShowThoughts = viewModel::setShowThoughts,
            onRefresh = viewModel::refreshModels,
            onDismiss = { showPicker = false },
        )
    }
    state.keyDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::closeKeyDialog,
            title = { Text(stringResource(R.string.gemini_api_key)) },
            text = {
                OutlinedTextField(
                    value = dialog.key,
                    onValueChange = viewModel::onKeyChange,
                    singleLine = true,
                    enabled = !dialog.verifying,
                    isError = dialog.error != null,
                    supportingText = { Text(dialog.error ?: stringResource(R.string.onboarding_restrict_tip)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveKey, enabled = dialog.key.isNotBlank() && !dialog.verifying) {
                    Text(stringResource(if (dialog.verifying) R.string.verifying else R.string.onboarding_verify))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::closeKeyDialog) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
}

@Composable
fun SwitchItem(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    ) { Text(title) }
}
