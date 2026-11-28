package dev.gcode.app.ui.builds

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.builds.ApkInstaller
import dev.gcode.app.builds.BuildStep
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.core.data.db.BuildEntity
import dev.gcode.github.actions.StepLog
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhJob
import java.text.DateFormat
import java.util.Date

/** Builds tab (ARCHITECTURE.md section 12, screen 6): start, follow, read logs, install. */
@Composable
fun BuildsScreen(onOpenRepos: () -> Unit, viewModel: BuildsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showBuild by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    state.detail?.let { detail ->
        BackHandler { viewModel.closeRun() }
        RunDetailPane(detail, viewModel)
    } ?: GCodeScreen(
        title = stringResource(R.string.nav_builds),
        modifier = Modifier.testTag("screen_builds"),
        actions = {
            if (state.repo != null && state.signedIn) {
                IconButton(onClick = { viewModel.showSecrets(true) }, modifier = Modifier.testTag("builds_secrets")) {
                    Icon(painterResource(R.drawable.ic_key), contentDescription = stringResource(R.string.builds_secrets))
                }
                IconButton(onClick = { viewModel.loadRepo() }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.builds_refresh))
                }
            }
        },
    ) { padding ->
        when {
            state.repo == null -> CenterMessage(stringResource(R.string.builds_no_repo), Modifier.padding(padding)) {
                Button(onClick = onOpenRepos) { Text(stringResource(R.string.builds_open_repos)) }
            }
            !state.signedIn -> CenterMessage(stringResource(R.string.builds_sign_in), Modifier.padding(padding)) {
                Button(onClick = onOpenRepos) { Text(stringResource(R.string.builds_open_repos)) }
            }
            else -> BuildsList(
                state = state,
                padding = padding,
                onSelectWorkflow = viewModel::selectWorkflow,
                onSetUp = viewModel::requestSetUp,
                onBuild = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    showBuild = true
                },
                onOpen = { viewModel.openRun(it.repoFullName, it.runId) },
            )
        }
    }

    if (showBuild) {
        BuildDialog(state, onDismiss = { showBuild = false }) { message, inputs ->
            showBuild = false
            viewModel.build(message, inputs)
        }
    }
    if (state.showSecrets) SecretsSheet(state, viewModel)
    state.confirm?.let { BuildConfirmDialog(it, state, viewModel) }
    if (state.needsUnknownSources) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUnknownSources,
            title = { Text(stringResource(R.string.install_unknown_sources_title)) },
            text = { Text(stringResource(R.string.install_unknown_sources_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUnknownSources()
                    context.startActivity(ApkInstaller.unknownSourcesIntent(context))
                }) { Text(stringResource(R.string.install_open_settings)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissUnknownSources) { Text(stringResource(R.string.cancel)) } },
        )
    }
    (state.error ?: state.message)?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            icon = { if (state.error != null) Icon(painterResource(R.drawable.ic_error), null) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun CenterMessage(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
private fun BuildsList(
    state: BuildsUiState,
    padding: PaddingValues,
    onSelectWorkflow: (Long) -> Unit,
    onSetUp: () -> Unit,
    onBuild: () -> Unit,
    onOpen: (BuildEntity) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.canSetUp) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp).testTag("setup_builds_card")) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.builds_setup_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.builds_setup_body), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onSetUp, enabled = !state.busy, modifier = Modifier.testTag("setup_builds")) {
                            Text(stringResource(R.string.builds_setup_action))
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.workflows.isEmpty()) {
                    if (!state.loading) Text(stringResource(R.string.builds_no_workflows), style = MaterialTheme.typography.bodyMedium)
                } else {
                    WorkflowPicker(state, onSelectWorkflow)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBuild, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("build_button")) {
                        Icon(painterResource(R.drawable.ic_builds), null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (state.dispatchInputs.isEmpty()) R.string.builds_build else R.string.builds_run_workflow))
                    }
                }
                state.step?.let { step ->
                    val label = when (step) {
                        BuildStep.Committing -> R.string.builds_step_committing
                        BuildStep.Pushing -> R.string.builds_step_pushing
                        BuildStep.Starting -> R.string.builds_step_starting
                        is BuildStep.Started -> null
                    }
                    label?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.builds_history),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (state.history.isEmpty()) {
            item { Text(stringResource(R.string.builds_history_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.history, key = { it.runId }) { build ->
            ListItem(
                modifier = Modifier.clickable { onOpen(build) }.testTag("build_${build.runId}"),
                leadingContent = { StatusIcon(build.status, build.conclusion) },
                headlineContent = { Text("#${build.runNumber} · ${build.workflowName ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        listOfNotNull(build.branch, build.headSha.take(7), build.event, formatTime(build.createdAt)).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Text(statusLabel(build.status, build.conclusion), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun WorkflowPicker(state: BuildsUiState, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.builds_workflow), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("workflow_picker")) {
            Text(state.selectedWorkflow?.let { "${it.name} (${it.fileName})" } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_expand_more), null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.workflows.forEach { workflow ->
                DropdownMenuItem(text = { Text("${workflow.name} (${workflow.fileName})") }, onClick = { open = false; onSelect(workflow.id) })
            }
        }
    }
}

@Composable
private fun BuildDialog(state: BuildsUiState, onDismiss: () -> Unit, onConfirm: (String?, Map<String, String>) -> Unit) {
    val defaultMessage = stringResource(R.string.builds_commit_default)
    var message by rememberSaveable { mutableStateOf(defaultMessage) }
    val inputs = remember { mutableStateMapOf<String, String>() }
    val workflow = state.selectedWorkflow ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (state.dispatchInputs.isEmpty()) R.string.builds_build_title else R.string.builds_run_workflow)) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState(), enabled = false)) {
                Text(stringResource(R.string.builds_build_body, state.branch ?: "", workflow.name))
                if (state.dirty && state.dispatchInputs.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.builds_commit_message)) }, modifier = Modifier.fillMaxWidth().testTag("build_commit_message"))
                }
                if (state.dispatchInputs.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.builds_inputs), style = MaterialTheme.typography.labelLarge)
                    state.dispatchInputs.forEach { name ->
                        OutlinedTextField(inputs[name].orEmpty(), { inputs[name] = it }, label = { Text(name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(message.takeIf { state.dirty && it.isNotBlank() }, inputs.filterValues { it.isNotEmpty() }.toMap()) },
                enabled = !state.dirty || message.isNotBlank() || state.dispatchInputs.isNotEmpty(),
                modifier = Modifier.testTag("build_confirm"),
            ) { Text(stringResource(R.string.builds_build)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RunDetailPane(detail: RunDetail, viewModel: BuildsViewModel) {
    val context = LocalContext.current
    val run = detail.run
    GCodeScreen(
        title = stringResource(R.string.run_title, run?.runNumber ?: 0),
        modifier = Modifier.testTag("screen_run"),
        onBack = viewModel::closeRun,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (run == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            run?.let {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(it.status, it.conclusion)
                            Spacer(Modifier.size(8.dp))
                            Text(statusLabel(it.status, it.conclusion), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(listOfNotNull(it.name, it.displayTitle).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(detail.repo, it.headBranch, it.headSha.take(7), it.event).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            if (!it.isCompleted) OutlinedButton(onClick = viewModel::cancelRun) { Text(stringResource(R.string.run_cancel)) }
                            if (it.isCompleted && it.conclusion in setOf("failure", "cancelled", "timed_out")) {
                                OutlinedButton(onClick = viewModel::rerunFailed) { Text(stringResource(R.string.run_rerun_failed)) }
                            }
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, it.htmlUrl.toUri())) }) {
                                Text(stringResource(R.string.run_open_github))
                            }
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.run_artifacts)) }
            if (detail.artifacts.isEmpty()) item { Text(stringResource(R.string.run_no_artifacts), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(detail.artifacts, key = { "a${it.id}" }) { artifact -> ArtifactRow(artifact, detail, viewModel) }
            detail.apk?.let {
                item {
                    Button(onClick = viewModel::requestInstall, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("install_apk")) {
                        Text(stringResource(R.string.run_install_apk, detail.apkLabel ?: it.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.run_jobs)) }
            items(detail.jobs, key = { "j${it.id}" }) { job ->
                JobRow(job, open = detail.openJob == job.id, onClick = { viewModel.toggleJob(job.id) })
                if (detail.openJob == job.id) {
                    when {
                        job.id in detail.loadingLogs -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.run_logs_loading))
                        }
                        detail.logs[job.id] != null -> LogViewer(detail.logs.getValue(job.id))
                        else -> StepList(job)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ArtifactRow(artifact: GhArtifact, detail: RunDetail, viewModel: BuildsViewModel) {
    val progress = detail.downloading?.takeIf { it.first == artifact.id }?.second
    ListItem(
        headlineContent = { Text(artifact.name) },
        supportingContent = {
            Column {
                Text(if (artifact.expired) stringResource(R.string.run_artifact_expired) else "%.1f MB".format(artifact.sizeInBytes / 1_048_576.0))
                if (progress != null) {
                    if (progress >= 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            if (!artifact.expired) {
                TextButton(onClick = { viewModel.download(artifact) }, enabled = detail.downloading == null, modifier = Modifier.testTag("download_${artifact.id}")) {
                    Text(if (progress != null && progress >= 0) stringResource(R.string.run_downloading, (progress * 100).toInt()) else stringResource(R.string.run_download))
                }
            }
        },
    )
}

@Composable
private fun JobRow(job: GhJob, open: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("job_${job.id}"),
        leadingContent = { StatusIcon(job.status, job.conclusion) },
        headlineContent = { Text(job.name) },
        supportingContent = {
            val failed = job.steps.filter { it.conclusion == "failure" }
            if (failed.isNotEmpty()) Text(failed.joinToString { it.name }, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), stringResource(R.string.run_logs)) },
    )
}

@Composable
private fun StepList(job: GhJob) {
    Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
        job.steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                StatusIcon(step.status, step.conclusion, Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text(step.name, style = MaterialTheme.typography.bodySmall, color = if (step.conclusion == "failure") MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
    }
}

/** Step-by-step log: failed steps open and highlighted, error lines in red. Always left-to-right. */
@Composable
private fun LogViewer(steps: List<StepLog>) {
    val expanded = remember(steps) { mutableStateMapOf<Int, Boolean>().apply { steps.forEach { put(it.number, it.failed) } } }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("log_viewer")) {
            steps.forEach { step ->
                val isOpen = expanded[step.number] == true
                Row(
                    Modifier.fillMaxWidth().clickable { expanded[step.number] = !isOpen }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(if (isOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null, Modifier.size(18.dp))
                    StatusIcon("completed", step.conclusion, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        step.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (step.failed) FontWeight.Bold else FontWeight.Normal,
                        color = if (step.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
                if (isOpen) {
                    val errors = step.errorLines.toSet()
                    // Long logs: show the tail, where failures usually are.
                    val start = (step.lines.size - MAX_LOG_LINES).coerceAtLeast(0)
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, bottom = 8.dp)) {
                        if (start > 0) Text("… ${start} lines …", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        for (i in start until step.lines.size) {
                            Text(
                                step.lines[i],
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                softWrap = false,
                                color = if (i in errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretsSheet(state: BuildsUiState, viewModel: BuildsViewModel) {
    ModalBottomSheet(onDismissRequest = { viewModel.showSecrets(false) }) {
        SecretsContent(state, viewModel, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("secrets_sheet"))
    }
}

/** Repository secrets and the stable debug key; used by the Builds sheet and the Secrets screen. */
@Composable
fun SecretsContent(state: BuildsUiState, viewModel: BuildsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(stringResource(R.string.secrets_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.secrets_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.debug_key_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.debug_key_body), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::requestDebugKey, modifier = Modifier.testTag("debug_key")) { Text(stringResource(R.string.debug_key_action)) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.secrets_existing), style = MaterialTheme.typography.titleMedium)
        }
        val names = state.secretNames
        if (names == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        } else if (names.isEmpty()) {
            item { Text(stringResource(R.string.secrets_none), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(names) { secret -> Text(secret, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.secrets_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("secret_name"))
            OutlinedTextField(
                value, { value = it },
                label = { Text(stringResource(R.string.secrets_value)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.requestPutSecret(name, value); value = "" }, enabled = name.isNotBlank() && value.isNotEmpty()) {
                    Text(stringResource(R.string.secrets_save))
                }
                TextButton(onClick = {
                    val repo = state.repo?.fullName ?: return@TextButton
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/$repo/settings/secrets/actions".toUri()))
                }) { Text(stringResource(R.string.secrets_open_github)) }
            }
        }
    }
}

@Composable
fun BuildConfirmDialog(confirm: BuildConfirm, state: BuildsUiState, viewModel: BuildsViewModel) {
    val repo = state.repo?.fullName.orEmpty()
    val (title, body) = when (confirm) {
        is BuildConfirm.Install -> stringResource(R.string.install_confirm_title) to stringResource(R.string.install_confirm_body, confirm.label, confirm.runNumber, confirm.repo)
        is BuildConfirm.PutSecret -> stringResource(R.string.secrets_confirm_title, confirm.name) to stringResource(R.string.secrets_confirm_body, confirm.name, repo)
        BuildConfirm.DebugKey -> stringResource(R.string.debug_key_title) to stringResource(R.string.debug_key_confirm, repo)
        BuildConfirm.SetUp -> stringResource(R.string.builds_setup_title) to stringResource(R.string.builds_setup_body)
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissConfirm,
        icon = { Icon(painterResource(R.drawable.ic_warning), null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = viewModel::confirm, modifier = Modifier.testTag("confirm_action")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StatusIcon(status: String?, conclusion: String?, modifier: Modifier = Modifier) {
    val sized = modifier.size(20.dp)
    when {
        status != null && status != "completed" -> CircularProgressIndicator(sized, strokeWidth = 2.dp)
        conclusion == "success" -> Icon(painterResource(R.drawable.ic_check), stringResource(R.string.run_status_success), sized, tint = SUCCESS)
        conclusion == "failure" || conclusion == "timed_out" -> Icon(painterResource(R.drawable.ic_error), stringResource(R.string.run_status_failure), sized, tint = MaterialTheme.colorScheme.error)
        conclusion == "skipped" || conclusion == null -> Icon(painterResource(R.drawable.ic_info), null, sized, tint = MaterialTheme.colorScheme.outline)
        else -> Icon(painterResource(R.drawable.ic_warning), conclusion, sized, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun statusLabel(status: String?, conclusion: String?): String = when {
    status == "queued" || status == "waiting" || status == "pending" || status == "requested" -> stringResource(R.string.run_status_queued)
    status != null && status != "completed" -> stringResource(R.string.run_status_in_progress)
    conclusion == "success" -> stringResource(R.string.run_status_success)
    conclusion == "failure" || conclusion == "timed_out" -> stringResource(R.string.run_status_failure)
    conclusion == "cancelled" -> stringResource(R.string.run_status_cancelled)
    else -> stringResource(R.string.run_status_other, conclusion ?: status ?: "")
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private val SUCCESS = Color(0xFF2E7D32)
private const val MAX_LOG_LINES = 400
