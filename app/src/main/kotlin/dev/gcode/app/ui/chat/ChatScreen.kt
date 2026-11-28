package dev.gcode.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.tools.TodoItem
import dev.gcode.app.ui.approval.modeLabel
import dev.gcode.app.ui.code.DiffText
import dev.gcode.app.ui.theme.CodeTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.models.ModelPickerSheet

@Composable
fun ChatScreen(onOpenSessions: () -> Unit = {}, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var confirmFullAuto by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Android 13+: the agent's foreground-service notification (with Stop) needs this permission to be visible.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var menu by remember { mutableStateOf(false) }
    val send: (String, List<Attachment>) -> Unit = { text, attachments ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.send(text, attachments)
    }
    GCodeScreen(
        title = state.session?.title ?: stringResource(R.string.nav_chat),
        modifier = Modifier.testTag("screen_chat"),
        actions = {
            IconButton(onClick = onOpenSessions, modifier = Modifier.testTag("open_sessions")) {
                Icon(painterResource(R.drawable.ic_history), stringResource(R.string.more_sessions))
            }
            IconButton(onClick = viewModel::newChat, modifier = Modifier.testTag("new_chat")) {
                Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.new_chat))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chat_menu")) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compact_now)) },
                        onClick = { menu = false; viewModel.compact() },
                        enabled = !state.live.running && state.items.isNotEmpty(),
                        modifier = Modifier.testTag("menu_compact"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.init_memory)) },
                        onClick = { menu = false; viewModel.initMemory() },
                        enabled = !state.live.running,
                        modifier = Modifier.testTag("menu_init"),
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.commands_title)) }, onClick = { menu = false; viewModel.refreshCommands(); viewModel.send("/help") })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            StatusBar(
                state,
                onModelClick = { showPicker = true },
                onMode = { mode -> if (mode == PermissionMode.FULL_AUTO) confirmFullAuto = true else viewModel.setMode(mode) },
            )
            if (state.extras.compacting) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("compacting"))
            MessageList(state, Modifier.weight(1f), onRewind = viewModel::previewRewind)
            TodoPanel(state.todos)
            LiveArea(state, onRetry = viewModel::retry, onDismiss = viewModel::dismissError)
            ChatComposer(
                running = state.live.running,
                commands = state.extras.commands,
                prefill = state.extras.prefill,
                onPrefillConsumed = viewModel::consumePrefill,
                onTypingCommand = { viewModel.refreshCommands() },
                onSend = send,
                onStop = viewModel::stop,
                onMessage = { viewModel.showMessage(it) },
            )
        }
    }
    if (confirmFullAuto) {
        AlertDialog(
            onDismissRequest = { confirmFullAuto = false },
            title = { Text(stringResource(R.string.full_auto_title), color = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.full_auto_body)) },
            confirmButton = {
                TextButton(onClick = { confirmFullAuto = false; viewModel.setMode(PermissionMode.FULL_AUTO) }, modifier = Modifier.testTag("full_auto_confirm")) {
                    Text(stringResource(R.string.full_auto_enable))
                }
            },
            dismissButton = { TextButton(onClick = { confirmFullAuto = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (state.extras.showHelp) CommandHelpDialog(state.extras.commands, viewModel::dismissHelp)
    state.extras.rewind?.let { preview -> RewindSheet(preview, onRewind = viewModel::rewind, onDismiss = viewModel::dismissRewind) }
    state.extras.message?.let { text ->
        AlertDialog(onDismissRequest = viewModel::dismissMessage, text = { Text(text) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.dismiss)) } })
    }
    if (showPicker) {
        ModelPickerSheet(
            catalog = state.catalog,
            selected = state.settings.preferredModel,
            thinkingLevel = state.settings.thinkingLevel,
            showThoughts = state.settings.showThoughts,
            refreshing = state.refreshingModels,
            error = state.modelError,
            onSelect = viewModel::selectModel,
            onThinkingLevel = viewModel::setThinkingLevel,
            onShowThoughts = viewModel::setShowThoughts,
            onRefresh = viewModel::refreshModels,
            onDismiss = { showPicker = false },
        )
    }
}

/** Model · mode · repo · branch · context % (ARCHITECTURE.md section 12). */
@Composable
private fun StatusBar(state: ChatUiState, onModelClick: () -> Unit, onMode: (PermissionMode) -> Unit) {
    var modeMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = onModelClick,
                modifier = Modifier.testTag("status_model"),
                label = { Text(state.activeModel ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (state.fallbackReason != null) {
                    { Icon(painterResource(R.drawable.ic_swap), stringResource(R.string.fallback_active), Modifier.size(18.dp)) }
                } else null,
            )
            Box {
                AssistChip(onClick = { modeMenu = true }, modifier = Modifier.testTag("status_mode"), label = { Text(stringResource(modeLabel(state.mode))) })
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    PermissionMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(stringResource(modeLabel(m))) }, onClick = { modeMenu = false; onMode(m) }, modifier = Modifier.testTag("mode_${m.id}"))
                    }
                }
            }
            val workspace = state.repo?.fullName ?: state.settings.safFolderUri?.let { stringResource(R.string.device_folder) }
            workspace?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            Text(stringResource(R.string.context_percent, state.contextPercent), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("status_context"))
        }
    }
    state.fallbackReason?.let {
        Text(
            stringResource(R.string.fallback_reason, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TodoPanel(todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("todo_panel")) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.todos_title, todos.count { it.status == TodoItem.DONE }, todos.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null)
        }
        if (expanded) {
            Column(Modifier.padding(12.dp, 0.dp, 12.dp, 8.dp)) {
                todos.forEach { t ->
                    val mark = when (t.status) { TodoItem.DONE -> "✓"; TodoItem.IN_PROGRESS -> "▶"; else -> "○" }
                    Text(
                        "$mark ${t.content}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.status == TodoItem.DONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState, modifier: Modifier, onRewind: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val count = state.items.size + if (state.live.running) 1 else 0
    LaunchedEffect(count, state.live.streamingText.length, state.live.tools.size) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    if (state.items.isEmpty() && !state.live.running) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("message_list"), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item.text, item.images, onLongPress = { onRewind(item.seq) })
                is ChatItem.Model -> ModelMessage(item.text, item.thoughts, item.model)
                is ChatItem.ToolCall -> ToolCard(item.name, item.args, running = false, ok = item.ok, result = item.result, display = null)
                is ChatItem.Compacted -> CompactedMarker()
            }
        }
        if (state.live.running) {
            item(key = "live") { StreamingItem(state) }
        }
    }
}

/** A collapsible tool-call card: name, arguments, status and result (ARCHITECTURE.md section 12). */
@Composable
fun ToolCard(name: String, args: String, running: Boolean, ok: Boolean?, result: String?, display: String?) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { expanded = !expanded }.testTag("tool_card_$name"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                ok == false -> Icon(painterResource(R.drawable.ic_error), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                else -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge)
                Text(args, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 20 else 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null)
        }
        if (expanded) {
            result?.let { Text(it, style = CodeTextStyle, modifier = Modifier.padding(12.dp, 0.dp, 12.dp, 8.dp)) }
            display?.let { DiffText(it, Modifier.padding(12.dp, 0.dp, 12.dp, 8.dp)) }
        }
    }
}

@Composable
private fun CompactedMarker() {
    Text(
        stringResource(R.string.conversation_compacted),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(text: String, images: Int, onLongPress: () -> Unit) {
    if (text.isBlank() && images == 0) return
    val rewindLabel = stringResource(R.string.rewind_title)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 560.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = rewindLabel)
                .testTag("user_message"),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (images > 0) Text(stringResource(R.string.attachments_count, images), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (text.isNotBlank()) Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun ModelMessage(text: String, thoughts: String, model: String?) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).animateContentSize()) {
        if (thoughts.isNotBlank()) ThoughtsCard(thoughts)
        if (text.isNotBlank()) {
            SelectionContainer { Markdown(content = text, modifier = Modifier.fillMaxWidth()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                model?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.copy), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ThoughtsCard(thoughts: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { expanded = !expanded },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_thinking), null, Modifier.size(18.dp))
            Text(stringResource(R.string.thoughts), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null)
        }
        if (expanded) Text(thoughts, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 0.dp, 12.dp, 12.dp))
    }
}

@Composable
private fun StreamingItem(state: ChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelMessage(text = state.live.streamingText, thoughts = state.live.streamingThoughts, model = state.live.activeModel)
        state.live.tools.forEach { t -> ToolCard(t.name, t.args, t.running, t.ok, t.summary, t.display) }
        state.live.status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.live.streamingText.isEmpty() && state.live.tools.none { it.running }) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
}

@Composable
private fun LiveArea(state: ChatUiState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    state.live.notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp, 6.dp),
        )
    }
    state.live.error?.let { error ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("chat_error"),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_error), null)
                Text(error, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
