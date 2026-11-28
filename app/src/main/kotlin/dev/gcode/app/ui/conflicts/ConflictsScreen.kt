package dev.gcode.app.ui.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.git.ConflictHunk
import dev.gcode.git.ConflictSegment
import dev.gcode.git.Resolution

/** Resolve a merge conflict file by file, hunk by hunk. */
@Composable
fun ConflictsScreen(onBack: () -> Unit, viewModel: ConflictsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var confirmAbort by remember { mutableStateOf(false) }
    GCodeScreen(title = stringResource(R.string.conflicts_title), modifier = Modifier.testTag("screen_conflicts"), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !state.merging && state.files.isEmpty() -> Text(stringResource(R.string.conflicts_none), Modifier.padding(16.dp))
                state.files.isEmpty() -> MergeCommitPanel(state, viewModel, onBack) { confirmAbort = true }
                else -> {
                    FileChips(state, viewModel::select)
                    val segments = state.segments
                    if (segments == null) {
                        WholeFile(state.selected.orEmpty(), viewModel)
                    } else {
                        HunkList(segments, state, viewModel, onEdit = { index, text -> editing = index to text })
                    }
                }
            }
        }
    }
    editing?.let { (index, initial) ->
        var text by remember(index) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.conflict_edit_title, index + 1)) },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        text, { text = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("conflict_edit_text"),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.choose(index, Resolution.Custom(text)); editing = null }) { Text(stringResource(R.string.conflict_use_text)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.secretConfirm?.let { (index, reason) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSecretConfirm,
            icon = { Icon(painterResource(R.drawable.ic_warning), null) },
            title = { Text(stringResource(R.string.conflict_ai_secret_title)) },
            text = { Text(stringResource(R.string.conflict_ai_secret_body, state.selected.orEmpty(), reason)) },
            confirmButton = { TextButton(onClick = { viewModel.askAi(index, approved = true) }) { Text(stringResource(R.string.conflict_ai_send)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissSecretConfirm) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmAbort) {
        AlertDialog(
            onDismissRequest = { confirmAbort = false },
            title = { Text(stringResource(R.string.merge_abort)) },
            text = { Text(stringResource(R.string.conflicts_abort_body)) },
            confirmButton = { TextButton(onClick = { confirmAbort = false; viewModel.abortMerge(onBack) }) { Text(stringResource(R.string.merge_abort)) } },
            dismissButton = { TextButton(onClick = { confirmAbort = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    (state.error ?: state.message)?.let { text ->
        AlertDialog(onDismissRequest = viewModel::dismiss, text = { Text(text) }, confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.dismiss)) } })
    }
}

@Composable
private fun FileChips(state: ConflictsUiState, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.conflicts_files, state.files.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.files.forEach { path ->
            FilterChip(selected = path == state.selected, onClick = { onSelect(path) }, label = { Text(path.substringAfterLast('/')) }, modifier = Modifier.testTag("conflict_file_$path"))
        }
    }
}

@Composable
private fun WholeFile(path: String, viewModel: ConflictsViewModel) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.conflict_whole_file, path), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.takeSide(ours = true) }) { Text(stringResource(R.string.conflict_keep_ours)) }
            OutlinedButton(onClick = { viewModel.takeSide(ours = false) }) { Text(stringResource(R.string.conflict_keep_theirs)) }
        }
    }
}

@Composable
private fun HunkList(segments: List<ConflictSegment>, state: ConflictsUiState, viewModel: ConflictsViewModel, onEdit: (Int, String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conflicts_progress, state.resolutions.size, state.hunkCount), modifier = Modifier.weight(1f))
                Button(onClick = viewModel::saveFile, enabled = state.allResolved && !state.busy, modifier = Modifier.testTag("conflict_save")) {
                    Text(stringResource(R.string.conflict_mark_resolved))
                }
            }
        }
        items(segments.filterIsInstance<ConflictSegment.Hunk>(), key = { it.hunk.index }) { segment ->
            HunkCard(segment.hunk, state, viewModel, onEdit)
        }
    }
}

@Composable
private fun HunkCard(hunk: ConflictHunk, state: ConflictsUiState, viewModel: ConflictsViewModel, onEdit: (Int, String) -> Unit) {
    val resolution = state.resolutions[hunk.index]
    val proposal = state.aiProposals[hunk.index]
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("hunk_${hunk.index}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.conflict_hunk, hunk.index + 1), style = MaterialTheme.typography.titleSmall)
            CodeBlock(stringResource(R.string.conflict_ours, hunk.oursLabel), hunk.ours, OURS)
            hunk.base?.let { CodeBlock(stringResource(R.string.conflict_base), it, null) }
            CodeBlock(stringResource(R.string.conflict_theirs, hunk.theirsLabel), hunk.theirs, THEIRS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(resolution == Resolution.Ours, { viewModel.choose(hunk.index, Resolution.Ours) }, { Text(stringResource(R.string.conflict_take_ours)) }, Modifier.testTag("hunk_${hunk.index}_ours"))
                FilterChip(resolution == Resolution.Theirs, { viewModel.choose(hunk.index, Resolution.Theirs) }, { Text(stringResource(R.string.conflict_take_theirs)) }, Modifier.testTag("hunk_${hunk.index}_theirs"))
                FilterChip(resolution == Resolution.Both, { viewModel.choose(hunk.index, Resolution.Both) }, { Text(stringResource(R.string.conflict_take_both)) })
                FilterChip(resolution is Resolution.Custom, {
                    val start = (resolution as? Resolution.Custom)?.text ?: hunk.ours + hunk.theirs
                    onEdit(hunk.index, start)
                }, { Text(stringResource(R.string.conflict_edit)) })
                FilterChip(false, { viewModel.askAi(hunk.index) }, { Text(stringResource(R.string.conflict_ask_ai)) }, Modifier.testTag("hunk_${hunk.index}_ai"), enabled = hunk.index !in state.aiBusy)
            }
            if (hunk.index in state.aiBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.conflict_ai_working), style = MaterialTheme.typography.bodySmall)
                }
            }
            proposal?.let {
                CodeBlock(stringResource(R.string.conflict_ai_proposal), it, MaterialTheme.colorScheme.tertiaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.acceptProposal(hunk.index, it) }) { Text(stringResource(R.string.conflict_ai_accept)) }
                    TextButton(onClick = { onEdit(hunk.index, it) }) { Text(stringResource(R.string.conflict_edit)) }
                    TextButton(onClick = { viewModel.discardProposal(hunk.index) }) { Text(stringResource(R.string.conflict_ai_discard)) }
                }
            }
            if (resolution is Resolution.Custom) CodeBlock(stringResource(R.string.conflict_result), resolution.text, null)
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String, background: Color?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text.ifEmpty { " " }.trimEnd('\n'),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                softWrap = false,
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .then(if (background != null) Modifier.background(background, RoundedCornerShape(6.dp)) else Modifier)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun MergeCommitPanel(state: ConflictsUiState, viewModel: ConflictsViewModel, onBack: () -> Unit, onAbort: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.conflicts_all_resolved), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(state.mergeMessage, viewModel::setMergeMessage, label = { Text(stringResource(R.string.git_commit_message)) }, modifier = Modifier.fillMaxWidth().testTag("merge_message"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.commitMerge(onBack) }, enabled = !state.busy, modifier = Modifier.testTag("commit_merge")) { Text(stringResource(R.string.conflicts_commit)) }
            OutlinedButton(onClick = onAbort) { Text(stringResource(R.string.merge_abort)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val OURS = Color(0x332E7D32)
private val THEIRS = Color(0x331565C0)
