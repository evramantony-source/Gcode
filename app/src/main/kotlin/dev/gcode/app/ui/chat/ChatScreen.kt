package dev.gcode.app.ui.chat

import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.repo.StoredMessage
import dev.gcode.gemini.model.Content

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    GCodeScreen(
        title = state.session?.title ?: stringResource(R.string.nav_chat),
        modifier = Modifier.testTag("screen_chat"),
        actions = {
            IconButton(onClick = viewModel::newChat, modifier = Modifier.testTag("new_chat")) {
                Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.new_chat))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            StatusBar(state, onModelClick = { showPicker = true })
            MessageList(state, Modifier.weight(1f))
            LiveArea(state, onRetry = viewModel::retry, onDismiss = viewModel::dismissError)
            Composer(running = state.live.running, onSend = viewModel::send, onStop = viewModel::stop)
        }
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

/** Model · mode · repo · branch · context %. Mode, repo and branch appear once a workspace is open. */
@Composable
private fun StatusBar(state: ChatUiState, onModelClick: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
            Text(
                stringResource(R.string.context_percent, state.contextPercent),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("status_context"),
            )
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
private fun MessageList(state: ChatUiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    val count = state.messages.size + if (state.live.running) 1 else 0
    LaunchedEffect(count, state.live.streamingText.length) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    if (state.messages.isEmpty() && !state.live.running) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("message_list"), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.messages, key = { it.seq }) { message -> MessageItem(message) }
        if (state.live.running) {
            item(key = "live") { StreamingItem(state) }
        }
    }
}

@Composable
private fun MessageItem(message: StoredMessage) {
    when {
        message.kind == MessageEntity.KIND_SUMMARY -> CompactedMarker()
        message.content.role == Content.ROLE_USER -> UserBubble(message.content.text)
        else -> ModelMessage(text = message.content.text, thoughts = message.content.thoughtText, model = message.modelId)
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

@Composable
private fun UserBubble(text: String) {
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp)) {
            SelectionContainer { Text(text, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
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
    Column {
        ModelMessage(text = state.live.streamingText, thoughts = state.live.streamingThoughts, model = state.live.activeModel)
        if (state.live.streamingText.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
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

@Composable
private fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag("composer"),
            placeholder = { Text(stringResource(R.string.composer_hint)) },
            maxLines = 6,
        )
        if (running) {
            IconButton(onClick = onStop, modifier = Modifier.testTag("stop")) { Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.stop)) }
        } else {
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("send"),
            ) { Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send)) }
        }
    }
}
