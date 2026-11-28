package dev.gcode.app.ui.collab

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import dev.gcode.app.R
import dev.gcode.app.ui.code.DiffViewerDialog
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhPull

@Composable
fun PullRequestsScreen(onBack: () -> Unit, onOpenRepos: () -> Unit, viewModel: PullRequestsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    if (detail != null) {
        BackHandler { viewModel.closeDetail() }
        PullDetail(state, detail, viewModel)
    } else {
        GCodeScreen(
            title = stringResource(R.string.more_pull_requests),
            modifier = Modifier.testTag("screen_pull_requests"),
            onBack = onBack,
            actions = {
                if (state.target.ref != null && state.target.signedIn) {
                    IconButton(onClick = { viewModel.showCreate(true) }, modifier = Modifier.testTag("pr_new")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.pr_new)) }
                    IconButton(onClick = { viewModel.refresh() }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) }
                }
            },
        ) { padding ->
            if (state.target.ref == null || !state.target.signedIn) {
                CollabEmpty(state.target, Modifier.padding(padding), onOpenRepos)
                return@GCodeScreen
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { StateFilter(state.filter, viewModel::setFilter) }
                if (state.items.isEmpty() && !state.loading) item { Text(stringResource(R.string.pr_none), Modifier.padding(16.dp)) }
                items(state.items, key = { it.number }) { pr ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.open(pr.number) }.testTag("pr_${pr.number}"),
                        leadingContent = { Icon(painterResource(R.drawable.ic_pull_request), null, tint = prColor(pr)) },
                        headlineContent = { Text("#${pr.number} ${pr.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${pr.head.ref} → ${pr.base.ref} · ${pr.user?.login.orEmpty()} · ${formatIso(pr.updatedAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(prStateLabel(pr), style = MaterialTheme.typography.labelMedium) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (state.showCreate) CreatePullDialog(state, viewModel)
    state.confirmMerge?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMerge,
            icon = { if (c.intoDefault) Icon(painterResource(R.drawable.ic_warning), null) },
            title = { Text(stringResource(R.string.pr_merge_title, c.pr.number)) },
            text = {
                Text(
                    stringResource(R.string.pr_merge_body, c.pr.head.ref, c.pr.base.ref, c.method) +
                        if (c.intoDefault) "\n\n" + stringResource(R.string.pr_merge_default_warning, c.pr.base.ref) else "",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmMerge, modifier = Modifier.testTag("confirm_merge")) { Text(stringResource(R.string.pr_merge)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissMerge) { Text(stringResource(R.string.cancel)) } },
        )
    }
    MessageDialog(state.error, state.message, viewModel::dismiss)
}

@Composable
fun StateFilter(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("open" to R.string.filter_open, "closed" to R.string.filter_closed, "all" to R.string.filter_all).forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("filter_$value"))
        }
    }
}

@Composable
private fun prColor(pr: GhPull): Color = when {
    pr.merged || pr.mergedAt != null -> Color(0xFF8250DF)
    pr.state == "open" -> if (pr.draft) MaterialTheme.colorScheme.outline else Color(0xFF2E7D32)
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun prStateLabel(pr: GhPull): String = stringResource(
    when {
        pr.merged || pr.mergedAt != null -> R.string.pr_state_merged
        pr.state == "open" && pr.draft -> R.string.pr_state_draft
        pr.state == "open" -> R.string.filter_open
        else -> R.string.filter_closed
    },
)

@Composable
private fun PullDetail(state: PullsUiState, detail: PrDetail, viewModel: PullRequestsViewModel) {
    val pr = detail.pr
    val context = LocalContext.current
    var diff by remember { mutableStateOf<Pair<String, String>?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var methodMenu by remember { mutableStateOf(false) }
    GCodeScreen(title = "#${pr.number}", modifier = Modifier.testTag("screen_pr_detail"), onBack = viewModel::closeDetail) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (detail.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pr.title, style = MaterialTheme.typography.titleLarge)
                    Text("${prStateLabel(pr)} · ${pr.head.ref} → ${pr.base.ref} · ${pr.user?.login.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    if (pr.state == "open" && !pr.merged) {
                        val mergeable = when (pr.mergeable) {
                            true -> stringResource(R.string.pr_mergeable)
                            false -> stringResource(R.string.pr_not_mergeable, pr.mergeableState.orEmpty())
                            null -> stringResource(R.string.pr_mergeable_unknown)
                        }
                        Text(mergeable, style = MaterialTheme.typography.bodySmall, color = if (pr.mergeable == false) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pr.state == "open" && !pr.merged) {
                            Box {
                                Button(onClick = { methodMenu = true }, enabled = pr.mergeable != false, modifier = Modifier.testTag("pr_merge")) { Text(stringResource(R.string.pr_merge)) }
                                DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                                    listOf("merge" to R.string.pr_method_merge, "squash" to R.string.pr_method_squash, "rebase" to R.string.pr_method_rebase).forEach { (method, label) ->
                                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { methodMenu = false; viewModel.requestMerge(method) })
                                    }
                                }
                            }
                            OutlinedButton(onClick = { viewModel.setOpen(false) }) { Text(stringResource(R.string.pr_close)) }
                        } else if (!pr.merged && pr.mergedAt == null) {
                            OutlinedButton(onClick = { viewModel.setOpen(true) }) { Text(stringResource(R.string.pr_reopen)) }
                        }
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, pr.htmlUrl.toUri())) }) { Text(stringResource(R.string.run_open_github)) }
                    }
                    pr.body?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Markdown(content = it, modifier = Modifier.fillMaxWidth()) } }
                }
            }
            item { SectionHeader(stringResource(R.string.pr_files, detail.files.size)) }
            items(detail.files, key = { "f" + it.filename }) { file ->
                ListItem(
                    modifier = Modifier.clickable(enabled = file.patch != null) { diff = file.filename to diffOf(file.filename, file.patch.orEmpty()) },
                    headlineContent = { Text(file.filename, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${file.status} · +${file.additions} −${file.deletions}") },
                )
            }
            item { SectionHeader(stringResource(R.string.comments, detail.comments.size)) }
            items(detail.comments, key = { "c${it.id}" }) { CommentItem(it) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.comment_add)) }, modifier = Modifier.weight(1f).testTag("comment_field"))
                    TextButton(onClick = { viewModel.comment(comment); comment = "" }, enabled = comment.isNotBlank()) { Text(stringResource(R.string.comment_send)) }
                }
            }
        }
    }
    diff?.let { (title, text) -> DiffViewerDialog(title, text) { diff = null } }
}

/** GitHub's per-file patch has hunks only; add the file headers the diff viewer expects. */
private fun diffOf(path: String, patch: String) = "--- a/$path\n+++ b/$path\n$patch"

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun CommentItem(comment: GhComment) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${comment.user?.login.orEmpty()} · ${formatIso(comment.createdAt)}", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Markdown(content = comment.body, modifier = Modifier.fillMaxWidth()) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CreatePullDialog(state: PullsUiState, viewModel: PullRequestsViewModel) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var head by rememberSaveable(state.currentBranch) { mutableStateOf(state.currentBranch.orEmpty()) }
    var base by rememberSaveable(state.defaultBranch) { mutableStateOf(state.defaultBranch.orEmpty()) }
    var draft by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { viewModel.showCreate(false) },
        title = { Text(stringResource(R.string.pr_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pr_title"))
                BranchPicker(stringResource(R.string.pr_head), head, state.branches) { head = it }
                BranchPicker(stringResource(R.string.pr_base), base, state.branches) { base = it }
                OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft, { draft = it })
                    Text(stringResource(R.string.pr_draft))
                }
                Text(stringResource(R.string.pr_push_first), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.create(title, body, head, base, draft) }, enabled = title.isNotBlank() && head.isNotBlank() && base.isNotBlank() && head != base, modifier = Modifier.testTag("pr_create")) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.showCreate(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun BranchPicker(label: String, value: String, branches: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_expand_more), label) } },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            branches.forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { open = false; onChange(b) }) }
        }
    }
}
