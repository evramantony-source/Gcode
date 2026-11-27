package dev.gcode.app.ui.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.agent.PermissionMode
import dev.gcode.app.R
import dev.gcode.app.workspace.RepoManager
import dev.gcode.core.data.db.LocalRepoEntity
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per-repository settings (ARCHITECTURE.md section 11): author, pull strategy, default mode, build workflow, fix limit. */
@HiltViewModel
class RepoSettingsViewModel @Inject constructor(private val repos: RepoManager) : ViewModel() {
    fun save(entity: LocalRepoEntity) = viewModelScope.launch { repos.updateRepoSettings(entity) }

    companion object {
        /** Full auto is never a default: it is an explicit opt-in per session. */
        val DEFAULT_MODES = listOf(null, PermissionMode.ASK, PermissionMode.AUTO_ACCEPT_EDITS, PermissionMode.PLAN)

        fun normalise(entity: LocalRepoEntity): LocalRepoEntity = entity.copy(
            authorName = entity.authorName?.trim()?.ifEmpty { null },
            authorEmail = entity.authorEmail?.trim()?.ifEmpty { null },
            buildWorkflow = entity.buildWorkflow?.trim()?.ifEmpty { null },
            autoFixLimit = entity.autoFixLimit.coerceIn(0, MAX_FIX_LIMIT),
            defaultMode = entity.defaultMode?.takeIf { id -> DEFAULT_MODES.any { it?.id == id } },
        )

        const val MAX_FIX_LIMIT = 10
    }
}

@Composable
fun RepoSettingsDialog(repo: LocalRepoEntity, onSave: (LocalRepoEntity) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(repo.fullName) { mutableStateOf(repo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repo_settings_title, repo.fullName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("repo_settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.authorName.orEmpty(), { draft = draft.copy(authorName = it) }, label = { Text(stringResource(R.string.repo_settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.authorEmail.orEmpty(), { draft = draft.copy(authorEmail = it) }, label = { Text(stringResource(R.string.repo_settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.repo_settings_author_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repo_settings_pull_rebase), modifier = Modifier.weight(1f))
                    Switch(draft.pullRebase, { draft = draft.copy(pullRebase = it) })
                }
                Text(stringResource(R.string.repo_settings_default_mode), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    RepoSettingsViewModel.DEFAULT_MODES.forEach { mode ->
                        FilterChip(
                            selected = draft.defaultMode == mode?.id,
                            onClick = { draft = draft.copy(defaultMode = mode?.id) },
                            label = { Text(stringResource(modeLabel(mode))) },
                        )
                    }
                }
                OutlinedTextField(draft.buildWorkflow.orEmpty(), { draft = draft.copy(buildWorkflow = it) }, label = { Text(stringResource(R.string.repo_settings_workflow)) }, placeholder = { Text("gcode-build.yml") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.repo_settings_fix_limit, draft.autoFixLimit), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = draft.autoFixLimit.toFloat(),
                    onValueChange = { draft = draft.copy(autoFixLimit = it.toInt()) },
                    valueRange = 0f..RepoSettingsViewModel.MAX_FIX_LIMIT.toFloat(),
                    steps = RepoSettingsViewModel.MAX_FIX_LIMIT - 1,
                    modifier = Modifier.testTag("fix_limit"),
                )
                Text(stringResource(R.string.repo_settings_fix_limit_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(RepoSettingsViewModel.normalise(draft)) }, modifier = Modifier.testTag("repo_settings_save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun modeLabel(mode: PermissionMode?): Int = when (mode) {
    null -> R.string.repo_settings_mode_global
    PermissionMode.ASK -> R.string.mode_ask
    PermissionMode.AUTO_ACCEPT_EDITS -> R.string.mode_auto_accept
    PermissionMode.PLAN -> R.string.mode_plan
    PermissionMode.FULL_AUTO -> R.string.mode_full_auto
}
