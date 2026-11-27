package dev.gcode.app.ui.collab

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import dev.gcode.app.ui.components.GCodeScreen

@Composable
fun IssuesScreen(onBack: () -> Unit, onOpenRepos: () -> Unit, viewModel: IssuesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    if (detail != null) {
        BackHandler { viewModel.closeDetail() }
        IssueDetailPane(detail, viewModel)
    } else {
        GCodeScreen(
            title = stringResource(R.string.more_issues),
            modifier = Modifier.testTag("screen_issues"),
            onBack = onBack,
            actions = {
                if (state.target.ref != null && state.target.signedIn) {
                    IconButton(onClick = { viewModel.showCreate(true) }, modifier = Modifier.testTag("issue_new")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.issue_new)) }
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
                if (state.items.isEmpty() && !state.loading) item { Text(stringResource(R.string.issue_none), Modifier.padding(16.dp)) }
                items(state.items, key = { it.number }) { issue ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.open(issue.number) }.testTag("issue_${issue.number}"),
                        leadingContent = { Icon(painterResource(R.drawable.ic_issue), null, tint = if (issue.state == "open") Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline) },
                        headlineContent = { Text("#${issue.number} ${issue.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(issue.labels.joinToString { it.name }.ifEmpty { null }, issue.user?.login, formatIso(issue.updatedAt), if (issue.comments > 0) "💬 ${issue.comments}" else null).joinToString(" · "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (state.showCreate) CreateIssueDialog(viewModel)
    MessageDialog(state.error, state.message, viewModel::dismiss)
}

@Composable
private fun IssueDetailPane(detail: IssueDetail, viewModel: IssuesViewModel) {
    val issue = detail.issue
    val context = LocalContext.current
    var comment by rememberSaveable { mutableStateOf("") }
    GCodeScreen(title = "#${issue.number}", modifier = Modifier.testTag("screen_issue_detail"), onBack = viewModel::closeDetail) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (detail.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(issue.title, style = MaterialTheme.typography.titleLarge)
                    val state = if (issue.state == "open") stringResource(R.string.filter_open) else stringResource(R.string.filter_closed) + (issue.stateReason?.let { " ($it)" } ?: "")
                    Text("$state · ${issue.user?.login.orEmpty()} · ${formatIso(issue.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    if (issue.labels.isNotEmpty()) Text(issue.labels.joinToString { it.name }, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (issue.state == "open") {
                            OutlinedButton(onClick = { viewModel.setState("completed") }, modifier = Modifier.testTag("issue_close")) { Text(stringResource(R.string.issue_close_completed)) }
                            OutlinedButton(onClick = { viewModel.setState("not_planned") }) { Text(stringResource(R.string.issue_close_not_planned)) }
                        } else {
                            OutlinedButton(onClick = { viewModel.setState(null) }) { Text(stringResource(R.string.issue_reopen)) }
                        }
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, issue.htmlUrl.toUri())) }) { Text(stringResource(R.string.run_open_github)) }
                    }
                    issue.body?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Markdown(content = it, modifier = Modifier.fillMaxWidth()) } }
                }
            }
            item { SectionHeader(stringResource(R.string.comments, detail.comments.size)) }
            items(detail.comments, key = { it.id }) { CommentItem(it) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.comment_add)) }, modifier = Modifier.weight(1f).testTag("comment_field"))
                    TextButton(onClick = { viewModel.comment(comment); comment = "" }, enabled = comment.isNotBlank()) { Text(stringResource(R.string.comment_send)) }
                }
            }
        }
    }
}

@Composable
private fun CreateIssueDialog(viewModel: IssuesViewModel) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var labels by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { viewModel.showCreate(false) },
        title = { Text(stringResource(R.string.issue_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("issue_title"))
                OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.description)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(labels, { labels = it }, label = { Text(stringResource(R.string.issue_labels)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.create(title, body, labels) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("issue_create")) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = { viewModel.showCreate(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}
