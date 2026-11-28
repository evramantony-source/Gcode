package dev.gcode.app.ui.collab

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen

@Composable
fun ReleasesScreen(onBack: () -> Unit, onOpenRepos: () -> Unit, viewModel: ReleasesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    GCodeScreen(
        title = stringResource(R.string.more_releases),
        modifier = Modifier.testTag("screen_releases"),
        onBack = onBack,
        actions = {
            if (state.target.ref != null && state.target.signedIn) {
                IconButton(onClick = { viewModel.showCreate(true) }, modifier = Modifier.testTag("release_new")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.release_new)) }
                IconButton(onClick = { viewModel.refresh() }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) }
            }
        },
    ) { padding ->
        if (state.target.ref == null || !state.target.signedIn) {
            CollabEmpty(state.target, Modifier.padding(padding), onOpenRepos)
            return@GCodeScreen
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || state.busy != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.busy?.let { item { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) } }
            item { Text(stringResource(R.string.release_tag_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            if (state.items.isEmpty() && !state.loading) item { Text(stringResource(R.string.release_none), Modifier.padding(16.dp)) }
            items(state.items, key = { it.id }) { release ->
                ListItem(
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, release.htmlUrl.toUri())) }.testTag("release_${release.tagName}"),
                    leadingContent = { Icon(painterResource(R.drawable.ic_release), null) },
                    headlineContent = { Text(release.name?.takeIf { it.isNotBlank() } ?: release.tagName) },
                    supportingContent = {
                        Column {
                            Text(
                                listOfNotNull(
                                    release.tagName,
                                    stringResource(R.string.release_draft).takeIf { release.draft },
                                    stringResource(R.string.release_prerelease).takeIf { release.prerelease },
                                    formatIso(release.publishedAt ?: release.createdAt),
                                ).joinToString(" · "),
                            )
                            release.assets.forEach { Text("📦 ${it.name} (${it.size / 1024} KiB)", style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { viewModel.chooseUpload(release) }, enabled = state.apks.isNotEmpty() && state.busy == null) { Text(stringResource(R.string.release_upload_apk)) }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (state.showCreate) CreateReleaseDialog(state, viewModel)
    state.uploadTo?.let { release ->
        ApkPickerDialog(state.apks, title = stringResource(R.string.release_upload_to, release.tagName), onDismiss = { viewModel.chooseUpload(null) }) { viewModel.uploadTo(release, it) }
    }
    MessageDialog(state.error, state.message, viewModel::dismiss)
}

@Composable
private fun CreateReleaseDialog(state: ReleasesUiState, viewModel: ReleasesViewModel) {
    var form by remember(state.defaultBranch) { mutableStateOf(ReleaseForm(target = state.defaultBranch.orEmpty())) }
    var pickApk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { viewModel.showCreate(false) },
        title = { Text(stringResource(R.string.release_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(form.tag, { form = form.copy(tag = it.trim()) }, label = { Text(stringResource(R.string.release_tag)) }, placeholder = { Text("v1.0.0") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("release_tag"))
                OutlinedTextField(form.target, { form = form.copy(target = it) }, label = { Text(stringResource(R.string.release_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(form.notes, { form = form.copy(notes = it) }, label = { Text(stringResource(R.string.release_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                CheckRow(stringResource(R.string.release_generate_notes), form.generateNotes) { form = form.copy(generateNotes = it) }
                CheckRow(stringResource(R.string.release_draft), form.draft) { form = form.copy(draft = it) }
                CheckRow(stringResource(R.string.release_prerelease), form.prerelease) { form = form.copy(prerelease = it) }
                TextButton(onClick = { pickApk = true }, enabled = state.apks.isNotEmpty()) {
                    Text(form.apk?.label ?: stringResource(if (state.apks.isEmpty()) R.string.release_no_apks else R.string.release_attach_apk))
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.create(form) }, enabled = form.tag.isNotBlank(), modifier = Modifier.testTag("release_create")) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = { viewModel.showCreate(false) }) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickApk) ApkPickerDialog(state.apks, stringResource(R.string.release_attach_apk), onDismiss = { pickApk = false }) { form = form.copy(apk = it); pickApk = false }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun ApkPickerDialog(apks: List<ApkChoice>, title: String, onDismiss: () -> Unit, onPick: (ApkChoice) -> Unit) {
    var selected by remember { mutableStateOf(apks.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                apks.forEach { apk ->
                    Row(Modifier.fillMaxWidth().clickable { selected = apk }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == apk, { selected = apk })
                        Text(apk.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let(onPick) }, enabled = selected != null) { Text(stringResource(R.string.release_upload_apk)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
