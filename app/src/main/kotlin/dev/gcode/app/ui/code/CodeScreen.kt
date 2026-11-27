package dev.gcode.app.ui.code

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
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

private enum class CodeTab(val label: Int) { FILES(R.string.code_tab_files), CHANGES(R.string.code_tab_changes), HISTORY(R.string.code_tab_history), BRANCHES(R.string.code_tab_branches) }

@Composable
fun CodeScreen(onOpenRepos: () -> Unit, viewModel: ChangesViewModel = hiltViewModel(), code: CodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val files by code.state.collectAsStateWithLifecycle()
    val folder by viewModel.safFolder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            viewModel.linkFolder(uri.toString())
        }
    }
    var tab by rememberSaveable { mutableStateOf(CodeTab.FILES) }
    val holder = remember { EditorHolder() }
    val dark = isSystemInDarkTheme()
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val hasGit = state.repo != null
    GCodeScreen(
        title = state.repo?.fullName ?: files.workspaceName ?: stringResource(R.string.nav_code),
        modifier = Modifier.testTag("screen_code"),
        actions = { if (files.workspaceKey != null) IconButton(onClick = { code.refreshTree(); viewModel.refresh() }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (files.workspaceKey == null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.code_no_repo), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenRepos) { Text(stringResource(R.string.code_open_repos)) }
                    OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.testTag("link_folder")) { Text(stringResource(R.string.folder_link)) }
                }
                return@Column
            }
            val tabs = if (hasGit) CodeTab.entries else listOf(CodeTab.FILES)
            if (tabs.size > 1) {
                PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
                    tabs.forEach { t ->
                        Tab(selected = tab == t, onClick = {
                            tab = t
                            when (t) {
                                CodeTab.HISTORY -> viewModel.loadHistory()
                                CodeTab.BRANCHES -> viewModel.loadBranches()
                                CodeTab.CHANGES -> viewModel.refresh()
                                else -> Unit
                            }
                        }, text = { Text(stringResource(t.label)) }, modifier = Modifier.testTag("code_tab_${t.name.lowercase()}"))
                    }
                }
            }
            if (folder != null && !hasGit) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.folder_linked_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::unlinkFolder) { Text(stringResource(R.string.folder_unlink)) }
                }
            }
            when (if (tab in tabs) tab else CodeTab.FILES) {
                CodeTab.FILES -> FilesTab(files, code, holder, dark, wide)
                CodeTab.CHANGES -> ChangesPanel(state, viewModel)
                CodeTab.HISTORY -> HistoryPanel(state, viewModel)
                CodeTab.BRANCHES -> BranchesPanel(state, viewModel)
            }
        }
    }
    state.diff?.let { diff -> DiffViewerDialog(state.diffTitle.orEmpty(), diff, viewModel::closeDiff) }
    files.message?.let { msg ->
        AlertDialog(onDismissRequest = code::dismissMessage, text = { Text(msg) }, confirmButton = { TextButton(onClick = code::dismissMessage) { Text(stringResource(R.string.dismiss)) } })
    }
}

@Composable
private fun FilesTab(files: CodeUiState, code: CodeViewModel, holder: EditorHolder, dark: Boolean, wide: Boolean) {
    val editor: @Composable (Modifier, (() -> Unit)?) -> Unit = { modifier, showFiles ->
        EditorPane(
            state = files, holder = holder, dark = dark,
            onSelect = code::select, onClose = code::close, onDirty = code::markDirty, onSave = code::save,
            onShowFiles = showFiles, modifier = modifier,
        )
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            FileTree(files, code::toggleDir, code::openFile, Modifier.width(300.dp).fillMaxHeight())
            VerticalDivider()
            editor(Modifier.weight(1f).fillMaxHeight(), null)
        }
    } else if (files.showEditor && files.tabs.isNotEmpty()) {
        BackHandler(onBack = code::showExplorer)
        editor(Modifier.fillMaxSize(), code::showExplorer)
    } else {
        FileTree(files, code::toggleDir, code::openFile, Modifier.fillMaxSize())
    }
}

@Composable
private fun HistoryPanel(state: ChangesUiState, viewModel: ChangesViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("history_list")) {
        items(state.history, key = { it.sha }) { c ->
            ListItem(
                modifier = Modifier.clickable { viewModel.showCommit(c.sha, c.title) },
                supportingContent = { Text("${c.shortSha} · ${c.authorName} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(c.timeEpochSeconds * 1000))}") },
            ) { Text(c.title, maxLines = 2) }
            HorizontalDivider()
        }
        if (state.history.isEmpty()) item { Text(stringResource(R.string.history_empty), modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun BranchesPanel(state: ChangesUiState, viewModel: ChangesViewModel) {
    var newBranch by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val current = state.status?.branch
    Column(Modifier.fillMaxSize()) {
        if (state.merging) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.merge_in_progress), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::abortMerge) { Text(stringResource(R.string.merge_abort)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newBranch, { newBranch = it }, singleLine = true, label = { Text(stringResource(R.string.branch_new)) }, modifier = Modifier.weight(1f).testTag("new_branch_name"))
            TextButton(onClick = { viewModel.createBranch(newBranch); newBranch = "" }, enabled = newBranch.isNotBlank()) { Text(stringResource(R.string.create)) }
        }
        LazyColumn(Modifier.weight(1f).testTag("branch_list")) {
            items(state.branches, key = { it.name }) { b ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_branches), null) },
                    supportingContent = { Text(listOfNotNull(stringResource(R.string.branch_current).takeIf { b.isCurrent }, stringResource(R.string.branch_remote).takeIf { b.isRemote }, b.upstream, b.sha.take(7)).joinToString(" · ")) },
                    trailingContent = {
                        if (!b.isCurrent) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.branch_switch)) }, onClick = { menu = false; viewModel.switchBranch(b.name) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.branch_merge_into, current.orEmpty())) }, onClick = {
                                        menu = false
                                        // Merging into the default branch is always confirmed.
                                        if (current != null && current == state.repo?.defaultBranch) {
                                            confirm = "Merge ${b.name} into $current (the default branch)?" to { viewModel.merge(b.name) }
                                        } else {
                                            viewModel.merge(b.name)
                                        }
                                    })
                                    if (!b.isRemote) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                            menu = false
                                            confirm = "Delete branch ${b.name}? Unmerged commits on it are lost." to { viewModel.deleteBranch(b.name, b.isRemote) }
                                        })
                                    }
                                }
                            }
                        }
                    },
                ) { Text(b.name) }
            }
        }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.approval_confirm), color = MaterialTheme.colorScheme.error) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }, modifier = Modifier.testTag("confirm_branch_action")) { Text(stringResource(R.string.approval_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ChangesPanel(state: ChangesUiState, viewModel: ChangesViewModel) {
    var message by rememberSaveable { mutableStateOf("") }
    var discard by remember { mutableStateOf<String?>(null) }
    discard?.let { path ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.git_discard)) },
            text = { Text(stringResource(R.string.git_discard_body, path)) },
            confirmButton = { TextButton(onClick = { discard = null; viewModel.discard(path) }) { Text(stringResource(R.string.git_discard)) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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
                FileRow(
                    path, kind?.let { kindLabel(it) } ?: stringResource(R.string.git_untracked), checked = false,
                    onToggle = { viewModel.stage(listOf(path)) }, onClick = { viewModel.showDiff(path, staged = false) },
                    onDiscard = if (kind != null) { { discard = path } } else null,
                )
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
private fun FileRow(path: String, kind: String, checked: Boolean, onToggle: () -> Unit, onClick: () -> Unit, onDiscard: (() -> Unit)? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        supportingContent = { Text(kind) },
        trailingContent = onDiscard?.let { { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.git_discard)) } } },
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
