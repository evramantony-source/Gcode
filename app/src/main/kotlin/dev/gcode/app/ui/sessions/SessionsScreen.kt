package dev.gcode.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.R
import dev.gcode.app.agent.ActiveSession
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.core.data.db.SessionEntity
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class SessionsUiState(val query: String = "", val sessions: List<SessionEntity> = emptyList(), val activeId: String? = null, val running: Set<String> = emptySet())

/** Sessions (ARCHITECTURE.md section 12): search, rename, delete, resume. */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val active: ActiveSession,
    private val manager: AgentSessionManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<SessionsUiState> = combine(query, sessions.observeSessions(), active.id, manager.live) { q, list, id, live ->
        SessionsUiState(q, filter(list, q), id, live.filterValues { it.running }.keys)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun resume(id: String) = active.open(id)

    fun rename(id: String, title: String) = viewModelScope.launch { sessions.rename(id, title) }

    fun delete(id: String) = viewModelScope.launch {
        manager.stop(id)
        sessions.delete(id)
        if (active.id.value == id) {
            active.open((sessions.latest() ?: sessions.create(mode = settings.current().defaultMode)).id)
        }
    }

    fun newSession() = viewModelScope.launch { active.open(sessions.create(mode = settings.current().defaultMode).id) }

    companion object {
        /** Case-insensitive match on the title or repository; newest first is the database order. */
        fun filter(list: List<SessionEntity>, query: String): List<SessionEntity> {
            val q = query.trim()
            if (q.isEmpty()) return list
            return list.filter { it.title.contains(q, ignoreCase = true) || it.repoFullName?.contains(q, ignoreCase = true) == true }
        }
    }
}

@Composable
fun SessionsScreen(onBack: () -> Unit, onResume: () -> Unit, viewModel: SessionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    GCodeScreen(
        title = stringResource(R.string.more_sessions),
        modifier = Modifier.testTag("screen_sessions"),
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.newSession(); onResume() }, modifier = Modifier.testTag("sessions_new")) {
                Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.new_chat))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                state.query, viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                placeholder = { Text(stringResource(R.string.sessions_search)) },
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("sessions_search"),
            )
            if (state.sessions.isEmpty()) Text(stringResource(R.string.sessions_none), Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.sessions, key = { it.id }) { session ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clickable { viewModel.resume(session.id); onResume() }.testTag("session_${session.id}"),
                        headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.sessions_running).takeIf { session.id in state.running },
                                    stringResource(R.string.sessions_current).takeIf { session.id == state.activeId },
                                    session.repoFullName,
                                    session.modelId,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.updatedAt)),
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.sessions_rename)) }, onClick = { menu = false; renaming = session })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = session })
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    renaming?.let { session ->
        var title by rememberSaveable(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.sessions_rename)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.testTag("session_title")) },
            confirmButton = { TextButton(onClick = { viewModel.rename(session.id, title); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = { Text(stringResource(R.string.sessions_delete_body, session.title)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(session.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
