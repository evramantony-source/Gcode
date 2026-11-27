package dev.gcode.app.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.theme.CodeTextStyle
import dev.gcode.git.ChangeKind

@Composable
fun CodeScreen(onOpenRepos: () -> Unit, viewModel: ChangesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GCodeScreen(title = state.repo?.fullName ?: stringResource(R.string.nav_code), modifier = Modifier.testTag("screen_code")) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val repo = state.repo
            if (repo == null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.code_no_repo), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenRepos) { Text(stringResource(R.string.code_open_repos)) }
                }
                return@Column
            }
            ChangesPanel(state, viewModel)
        }
    }
    state.diff?.let { diff ->
        AlertDialog(
            onDismissRequest = viewModel::closeDiff,
            title = { Text(state.diffTitle.orEmpty()) },
            text = { DiffText(diff) },
            confirmButton = { TextButton(onClick = viewModel::closeDiff) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun ChangesPanel(state: ChangesUiState, viewModel: ChangesViewModel) {
    var message by rememberSaveable { mutableStateOf("") }
    val status = state.status
    Column {
        Text(
            listOfNotNull(
                status?.branch?.let { stringResource(R.string.branch_label, it) },
                status?.upstream?.let { "↑${status.ahead} ↓${status.behind}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(16.dp, 8.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::fetch, enabled = state.busy == null) { Text(stringResource(R.string.git_fetch)) }
            OutlinedButton(onClick = viewModel::pull, enabled = state.busy == null) { Text(stringResource(R.string.git_pull)) }
            OutlinedButton(onClick = viewModel::push, enabled = state.busy == null, modifier = Modifier.testTag("git_push")) { Text(stringResource(R.string.git_push)) }
        }
        if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp).clickable(onClick = viewModel::dismiss)) }
        state.message?.let { Text(it, modifier = Modifier.padding(16.dp, 4.dp).clickable(onClick = viewModel::dismiss)) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (status == null) return@Column
        if (status.isClean) {
            Text(stringResource(R.string.git_clean), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        LazyColumn(Modifier.weight(1f, fill = false).testTag("changes_list")) {
            if (status.conflicting.isNotEmpty()) item { Header(stringResource(R.string.git_conflicts)) }
            items(status.conflicting, key = { "c_$it" }) { path -> ListItem(supportingContent = { Text(stringResource(R.string.git_conflict)) }) { Text(path) } }
            if (status.staged.isNotEmpty()) item { Header(stringResource(R.string.git_staged)) }
            items(status.staged, key = { "s_${it.path}" }) { change ->
                FileRow(change.path, kindLabel(change.kind), checked = true, onToggle = { viewModel.unstage(listOf(change.path)) }, onClick = { viewModel.showDiff(change.path, staged = true) })
            }
            val unstaged = status.unstaged.map { it.path to it.kind } + status.untracked.map { it to null }
            if (unstaged.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth()) {
                    Header(stringResource(R.string.git_unstaged), Modifier.weight(1f))
                    TextButton(onClick = viewModel::stageAll) { Text(stringResource(R.string.git_stage_all)) }
                }
            }
            items(unstaged, key = { "u_${it.first}" }) { (path, kind) ->
                FileRow(path, kind?.let { kindLabel(it) } ?: stringResource(R.string.git_untracked), checked = false, onToggle = { viewModel.stage(listOf(path)) }, onClick = { viewModel.showDiff(path, staged = false) })
            }
        }
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text(stringResource(R.string.git_commit_message)) },
            modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp).testTag("commit_message"),
            maxLines = 4,
        )
        Button(
            onClick = { viewModel.commit(message); message = "" },
            enabled = message.isNotBlank() && status.staged.isNotEmpty() && state.busy == null,
            modifier = Modifier.padding(horizontal = 12.dp).testTag("git_commit"),
        ) { Text(stringResource(R.string.git_commit)) }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))

@Composable
private fun FileRow(path: String, kind: String, checked: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        supportingContent = { Text(kind) },
    ) { Text(path) }
}

@Composable
private fun kindLabel(kind: ChangeKind) = stringResource(
    when (kind) {
        ChangeKind.ADDED -> R.string.git_added
        ChangeKind.MODIFIED -> R.string.git_modified
        ChangeKind.DELETED -> R.string.git_deleted
        ChangeKind.RENAMED -> R.string.git_renamed
    },
)

/** Unified diff with added/removed lines coloured; always left-to-right. */
@Composable
fun DiffText(diff: String, modifier: Modifier = Modifier) {
    val added = Color(0xFF2E7D32)
    val removed = Color(0xFFC62828)
    val hunk = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        diff.lines().forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> null
                line.startsWith("+") -> added
                line.startsWith("-") -> removed
                line.startsWith("@@") -> hunk
                else -> null
            }
            if (color != null) withStyle(SpanStyle(color = color)) { append(line) } else append(line)
            append('\n')
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, style = CodeTextStyle, modifier = modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
    }
}
