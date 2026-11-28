package dev.gcode.app.ui.mcp

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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.R
import dev.gcode.app.agent.McpManager
import dev.gcode.app.agent.McpServerStatus
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.McpServerEntry
import dev.gcode.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import javax.inject.Inject

data class McpUiState(val servers: List<McpServerEntry> = emptyList(), val status: Map<String, McpServerStatus> = emptyMap())

/** MCP servers (ARCHITECTURE.md section 11): add, edit, enable, trust, test, delete. */
@HiltViewModel
class McpViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val mcp: McpManager,
) : ViewModel() {
    val state: StateFlow<McpUiState> = combine(settings.settings.map { it.mcpServers }, mcp.status) { servers, status -> McpUiState(servers, status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), McpUiState())

    /** Saves a server; a blank [secret] keeps the stored one, unless the header is removed. */
    fun save(entry: McpServerEntry, secret: String) = viewModelScope.launch {
        settings.update { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != entry.id } + entry) }
        when {
            entry.headerName.isBlank() -> secrets.remove(SecretKey.mcpHeader(entry.id))
            secret.isNotBlank() -> secrets.put(SecretKey.mcpHeader(entry.id), secret.trim())
        }
        mcp.refresh(entry)
    }

    fun setEnabled(entry: McpServerEntry, enabled: Boolean) = viewModelScope.launch {
        settings.update { s -> s.copy(mcpServers = s.mcpServers.map { if (it.id == entry.id) it.copy(enabled = enabled) else it }) }
    }

    fun test(entry: McpServerEntry) = viewModelScope.launch { mcp.refresh(entry) }

    fun delete(entry: McpServerEntry) = viewModelScope.launch {
        settings.update { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != entry.id }) }
        mcp.forget(entry.id)
    }

    companion object {
        /** HTTPS is required, except for local development servers. */
        fun validUrl(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" || parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        }
    }
}

@Composable
fun McpScreen(onBack: () -> Unit, viewModel: McpViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServerEntry?>(null) }
    var deleting by remember { mutableStateOf<McpServerEntry?>(null) }
    GCodeScreen(
        title = stringResource(R.string.mcp_title),
        modifier = Modifier.testTag("screen_mcp"),
        onBack = onBack,
        actions = {
            IconButton(onClick = { editing = McpServerEntry(UUID.randomUUID().toString(), "", "") }, modifier = Modifier.testTag("mcp_add")) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.mcp_add))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text(stringResource(R.string.mcp_body), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            if (state.servers.isEmpty()) item { Text(stringResource(R.string.mcp_none), Modifier.padding(16.dp)) }
            items(state.servers, key = { it.id }) { server ->
                val status = state.status[server.id]
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("mcp_server_${server.name}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleMedium)
                                Text(server.url, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(server.enabled, { viewModel.setEnabled(server, it) })
                        }
                        when {
                            status == null -> Text(stringResource(R.string.mcp_not_checked), style = MaterialTheme.typography.bodySmall)
                            status.error != null -> Text(status.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else -> Text(
                                pluralStringResource(R.plurals.mcp_connected, status.tools.size, status.info?.name ?: server.name, status.info?.protocolVersion.orEmpty(), status.tools.size) +
                                    "\n" + status.tools.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (server.trusted) Text(stringResource(R.string.mcp_trusted_label), style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.test(server) }) { Text(stringResource(R.string.mcp_test)) }
                            TextButton(onClick = { editing = server }) { Text(stringResource(R.string.conflict_edit)) }
                            TextButton(onClick = { deleting = server }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry -> McpServerDialog(entry, isNew = state.servers.none { it.id == entry.id }, onSave = { e, secret -> viewModel.save(e, secret); editing = null }, onDismiss = { editing = null }) }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.mcp_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.mcp_delete_body)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(entry); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun McpServerDialog(entry: McpServerEntry, isNew: Boolean, onSave: (McpServerEntry, String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(entry.id) { mutableStateOf(entry) }
    var secret by remember(entry.id) { mutableStateOf("") }
    val urlOk = McpViewModel.validUrl(draft.url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.mcp_add else R.string.mcp_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(stringResource(R.string.secrets_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("mcp_name"))
                OutlinedTextField(
                    draft.url, { draft = draft.copy(url = it.trim()) },
                    label = { Text(stringResource(R.string.mcp_url)) },
                    placeholder = { Text("https://example.com/mcp") },
                    isError = draft.url.isNotEmpty() && !urlOk,
                    supportingText = { if (draft.url.isNotEmpty() && !urlOk) Text(stringResource(R.string.mcp_url_invalid)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("mcp_url"),
                )
                OutlinedTextField(draft.headerName, { draft = draft.copy(headerName = it.trim()) }, label = { Text(stringResource(R.string.mcp_header)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    secret, { secret = it },
                    label = { Text(stringResource(R.string.mcp_secret)) },
                    placeholder = { Text(if (isNew) "Bearer …" else stringResource(R.string.mcp_secret_keep)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.mcp_trusted))
                        Text(stringResource(R.string.mcp_trusted_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(draft.trusted, { draft = draft.copy(trusted = it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft.copy(name = draft.name.trim()), secret) }, enabled = draft.name.isNotBlank() && urlOk, modifier = Modifier.testTag("mcp_save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
