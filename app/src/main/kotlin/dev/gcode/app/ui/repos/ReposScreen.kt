package dev.gcode.app.ui.repos

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.github.GitHubAuthState
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.github.GitHubSignInCard
import dev.gcode.app.ui.github.GitHubSignInViewModel
import dev.gcode.github.model.GhRepo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(onOpened: () -> Unit, viewModel: ReposViewModel = hiltViewModel(), signIn: GitHubSignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<GhRepo?>(null) }
    GCodeScreen(
        title = stringResource(R.string.nav_repos),
        modifier = Modifier.testTag("screen_repos"),
        actions = {
            if (state.auth is GitHubAuthState.SignedIn) {
                IconButton(onClick = viewModel::refresh) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.auth !is GitHubAuthState.SignedIn) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { GitHubSignInCard(state.auth, signIn.account) }
                return@Column
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                placeholder = { Text(stringResource(R.string.repos_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("repo_search"),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RepoFilter.entries.forEach { f ->
                    FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(stringResource(filterLabel(f))) })
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).testTag("repo_list")) {
                items(state.visible, key = { it.fullName }) { repo ->
                    RepoRow(repo, state, onClick = { selected = repo }, onCancelClone = { viewModel.cancelClone(repo.fullName) })
                    HorizontalDivider()
                }
                if (state.visible.isEmpty() && !state.loading) {
                    item { Text(stringResource(R.string.repos_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            state.message?.let {
                Snackbar(action = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.dismiss)) } }, modifier = Modifier.padding(8.dp)) { Text(it) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                FloatingActionButton(onClick = viewModel::openCreate, modifier = Modifier.testTag("new_repo")) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.repo_new))
                }
            }
        }
    }
    selected?.let { repo ->
        RepoActionsSheet(
            repo = repo,
            cloned = viewModel.isCloned(repo),
            active = state.activeRepo == repo.fullName,
            onDismiss = { selected = null },
            onClone = { single -> viewModel.clone(repo, single); selected = null },
            onOpen = { viewModel.open(repo.fullName); selected = null; onOpened() },
            onFork = { viewModel.fork(repo); selected = null },
            onDeleteLocal = { viewModel.deleteLocal(repo.fullName); selected = null },
        )
    }
    state.create?.let { form -> CreateRepoDialog(form, state, viewModel) }
}

private fun filterLabel(f: RepoFilter) = when (f) {
    RepoFilter.ALL -> R.string.filter_all
    RepoFilter.MINE -> R.string.filter_mine
    RepoFilter.ORGS -> R.string.filter_orgs
    RepoFilter.CLONED -> R.string.filter_cloned
}

@Composable
private fun RepoRow(repo: GhRepo, state: ReposUiState, onClick: () -> Unit, onCancelClone: () -> Unit) {
    val clone = state.clones[repo.fullName]
    val cloned = state.local.any { it.fullName == repo.fullName }
    Column(Modifier.clickable(onClick = onClick).testTag("repo_${repo.fullName}")) {
        ListItem(
            supportingContent = {
                Text(listOfNotNull(repo.description, repo.language, stringResource(R.string.repo_private).takeIf { repo.private },
                    stringResource(R.string.repo_cloned).takeIf { cloned }, stringResource(R.string.repo_open).takeIf { state.activeRepo == repo.fullName }).joinToString(" · "),
                    maxLines = 2)
            },
        ) { Text(repo.fullName) }
        clone?.let { c ->
            when {
                c.error != null -> Text(c.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                !c.done -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.progress?.let { "${it.task} ${if (it.total > 0) "${it.done}/${it.total}" else ""}" } ?: stringResource(R.string.cloning), style = MaterialTheme.typography.bodySmall)
                        val p = c.progress
                        if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancelClone) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoActionsSheet(
    repo: GhRepo,
    cloned: Boolean,
    active: Boolean,
    onDismiss: () -> Unit,
    onClone: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onFork: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    val uri = LocalUriHandler.current
    var singleBranch by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(repo.fullName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            repo.description?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (cloned) {
                ListItem(modifier = Modifier.clickable(onClick = onOpen).testTag("repo_open"), supportingContent = { Text(stringResource(R.string.repo_open_body)) }) {
                    Text(stringResource(if (active) R.string.repo_is_open else R.string.repo_open_action))
                }
                ListItem(modifier = Modifier.clickable { confirmDelete = true }) { Text(stringResource(R.string.repo_delete_local)) }
            } else {
                ListItem(
                    modifier = Modifier.clickable { onClone(singleBranch) }.testTag("repo_clone"),
                    supportingContent = { Text(stringResource(R.string.repo_clone_body)) },
                ) { Text(stringResource(R.string.repo_clone)) }
                ListItem(
                    trailingContent = { Switch(checked = singleBranch, onCheckedChange = { singleBranch = it }) },
                    supportingContent = { Text(stringResource(R.string.repo_single_branch_body, repo.defaultBranch)) },
                ) { Text(stringResource(R.string.repo_single_branch)) }
            }
            ListItem(modifier = Modifier.clickable(onClick = onFork)) { Text(stringResource(R.string.repo_fork)) }
            ListItem(modifier = Modifier.clickable { uri.openUri(repo.htmlUrl) }) { Text(stringResource(R.string.repo_open_github)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.repo_delete_local)) },
            text = { Text(stringResource(R.string.repo_delete_local_body, repo.fullName)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteLocal() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CreateRepoDialog(form: CreateRepoForm, state: ReposUiState, viewModel: ReposViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeCreate,
        title = { Text(stringResource(R.string.repo_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = stringResource(R.string.repo_owner),
                    value = form.owner ?: state.login.orEmpty(),
                    options = listOfNotNull(state.login) + state.orgs.map { it.login },
                    onPick = { o -> viewModel.updateCreate { it.copy(owner = o) } },
                )
                OutlinedTextField(form.name, { v -> viewModel.updateCreate { it.copy(name = v, error = null) } }, label = { Text(stringResource(R.string.repo_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("repo_name"))
                OutlinedTextField(form.description, { v -> viewModel.updateCreate { it.copy(description = v) } }, label = { Text(stringResource(R.string.repo_description)) }, modifier = Modifier.fillMaxWidth())
                LabeledSwitch(stringResource(R.string.repo_private), form.private) { v -> viewModel.updateCreate { it.copy(private = v) } }
                LabeledSwitch(stringResource(R.string.repo_add_readme), form.readme) { v -> viewModel.updateCreate { it.copy(readme = v) } }
                Picker(stringResource(R.string.repo_gitignore), form.gitignore ?: stringResource(R.string.none), listOf("") + state.gitignoreTemplates) { v ->
                    viewModel.updateCreate { it.copy(gitignore = v.ifEmpty { null }) }
                }
                Picker(stringResource(R.string.repo_license), state.licenses.firstOrNull { it.key == form.license }?.name ?: stringResource(R.string.none), listOf("") + state.licenses.map { it.key }) { v ->
                    viewModel.updateCreate { it.copy(license = v.ifEmpty { null }) }
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::submitCreate, enabled = !form.busy && form.name.isNotBlank(), modifier = Modifier.testTag("repo_create")) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Picker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AssistChip(onClick = { open = true }, label = { Text(value.ifEmpty { "—" }) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.ifEmpty { "—" }) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}
