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
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.models.ModelPickerSheet
import dev.gcode.app.ui.github.GitHubSignInCard
import dev.gcode.app.ui.github.GitHubSignInViewModel
import dev.gcode.core.data.settings.ThemeMode

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenMcp: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel(), signIn: GitHubSignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val github by signIn.account.state.collectAsStateWithLifecycle()
    var clientIdDraft by rememberSaveable(state.settings.githubClientIdOverride) { mutableStateOf(state.settings.githubClientIdOverride.orEmpty()) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val s = state.settings
    var memoryDraft by rememberSaveable(s.globalMemory) { mutableStateOf(s.globalMemory) }
    var denylistDraft by rememberSaveable(s.extraDenylist) { mutableStateOf(s.extraDenylist.joinToString("\n")) }
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
                supportingContent = { Text(state.message ?: pluralStringResource(R.plurals.models_count, state.catalog.chatModels.size, state.catalog.chatModels.size)) },
            ) { Text(stringResource(if (state.refreshing) R.string.refreshing else R.string.refresh_models)) }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_agent))
            ListItem(supportingContent = {
                Column {
                    Text(stringResource(R.string.global_memory_body))
                    OutlinedTextField(
                        value = memoryDraft,
                        onValueChange = { memoryDraft = it },
                        minLines = 3,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("global_memory"),
                    )
                    TextButton(onClick = { viewModel.setGlobalMemory(memoryDraft) }, enabled = memoryDraft.trim() != s.globalMemory) { Text(stringResource(R.string.save)) }
                }
            }) { Text(stringResource(R.string.global_memory)) }
            ListItem(supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ask" to R.string.mode_ask, "auto_accept_edits" to R.string.mode_auto_accept, "plan" to R.string.mode_plan).forEach { (id, label) ->
                        FilterChip(selected = s.defaultMode == id, onClick = { viewModel.setDefaultMode(id) }, label = { Text(stringResource(label)) })
                    }
                }
            }) { Text(stringResource(R.string.default_mode)) }
            ListItem(supportingContent = {
                Slider(value = s.maxSteps.toFloat(), onValueChange = { viewModel.setMaxSteps(it.toInt()) }, valueRange = 5f..200f)
            }) { Text(stringResource(R.string.max_steps, s.maxSteps)) }
            ListItem(supportingContent = {
                Column {
                    Text(stringResource(R.string.denylist_body))
                    OutlinedTextField(value = denylistDraft, onValueChange = { denylistDraft = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), placeholder = { Text("*.secret\nconfig/prod/*") })
                    TextButton(onClick = { viewModel.setExtraDenylist(denylistDraft) }) { Text(stringResource(R.string.save)) }
                }
            }) { Text(stringResource(R.string.denylist_title)) }
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenMcp).testTag("settings_mcp"),
                supportingContent = { Text(s.mcpServers.count { it.enabled }.let { n -> pluralStringResource(R.plurals.mcp_settings_body, n, n) }) },
            ) { Text(stringResource(R.string.mcp_title)) }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_github))
            GitHubSignInCard(github, signIn.account, Modifier.padding(horizontal = 16.dp))
            ListItem(supportingContent = {
                Column {
                    Text(stringResource(R.string.github_client_id_body))
                    OutlinedTextField(
                        value = clientIdDraft,
                        onValueChange = { clientIdDraft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.github_client_id_default)) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { viewModel.setClientIdOverride(clientIdDraft) }) { Text(stringResource(R.string.save)) } },
                    )
                }
            }) { Text(stringResource(R.string.github_client_id)) }
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
