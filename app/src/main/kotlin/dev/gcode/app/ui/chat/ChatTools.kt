package dev.gcode.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.gcode.agent.extend.SlashCommand
import dev.gcode.agent.extend.SlashCommands
import dev.gcode.app.R
import dev.gcode.app.agent.RewindPreview
import kotlinx.coroutines.launch

/**
 * Message composer (ARCHITECTURE.md section 12): text with slash-command suggestions, image attachments
 * (photo picker, downscaled) and voice input (the system speech recogniser, so no microphone permission).
 */
@Composable
fun ChatComposer(
    running: Boolean,
    commands: List<SlashCommand>,
    prefill: String?,
    onPrefillConsumed: () -> Unit,
    onTypingCommand: () -> Unit,
    onSend: (String, List<Attachment>) -> Unit,
    onStop: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Attachment>() }
    LaunchedEffect(prefill) {
        if (prefill != null) {
            text = prefill
            onPrefillConsumed()
        }
    }
    val unavailable = stringResource(R.string.voice_unavailable)
    val voicePrompt = stringResource(R.string.voice_prompt)
    val imageFailed = stringResource(R.string.attach_failed)
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        scope.launch {
            uris.take(MAX_IMAGES - attachments.size).forEach { uri ->
                val image = runCatching { ImageAttachments.load(context, uri) }.getOrNull()
                if (image != null) attachments += image else onMessage(imageFailed)
            }
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
            text = if (text.isBlank()) spoken else "${text.trimEnd()} $spoken"
        }
    }
    val suggestions = if (text.startsWith("/") && !text.contains(' ')) SlashCommands.suggestions(text, commands) else emptyList()
    LaunchedEffect(text.startsWith("/")) { if (text.startsWith("/")) onTypingCommand() }

    Column(Modifier.fillMaxWidth()) {
        if (suggestions.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                LazyColumn(Modifier.heightIn(max = 220.dp).testTag("command_suggestions")) {
                    items(suggestions, key = { it.name }) { command ->
                        ListItem(
                            modifier = Modifier.clickable { text = "/${command.name} " }.testTag("command_${command.name}"),
                            headlineContent = { Text("/${command.name}" + (command.argumentHint?.let { " $it" } ?: ""), fontFamily = FontFamily.Monospace) },
                            supportingContent = { Text(command.description) },
                        )
                    }
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEachIndexed { index, attachment ->
                    InputChip(
                        selected = false,
                        onClick = { attachments.removeAt(index) },
                        label = { Text(stringResource(R.string.attachment_image, attachment.label)) },
                        trailingIcon = { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.attachment_remove)) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = attachments.size < MAX_IMAGES, modifier = Modifier.testTag("attach_image")) {
                Icon(painterResource(R.drawable.ic_image), stringResource(R.string.attach_image))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).testTag("composer"),
                placeholder = { Text(stringResource(R.string.composer_hint)) },
                maxLines = 6,
            )
            if (running) {
                IconButton(onClick = onStop, modifier = Modifier.testTag("stop")) { Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.stop)) }
            } else if (text.isBlank() && attachments.isEmpty()) {
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                    try {
                        speech.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        onMessage(unavailable)
                    }
                }, modifier = Modifier.testTag("voice_input")) { Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.voice_input)) }
            } else {
                IconButton(
                    onClick = {
                        onSend(text, attachments.toList())
                        text = ""
                        attachments.clear()
                    },
                    modifier = Modifier.testTag("send"),
                ) { Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send)) }
            }
        }
    }
}

private const val MAX_IMAGES = 4

/** Rewind to before a user message: code, conversation or both, with what cannot be undone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewindSheet(preview: RewindPreview, onRewind: (code: Boolean, conversation: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()).testTag("rewind_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rewind_title), style = MaterialTheme.typography.titleLarge)
            Text("“${preview.userText.take(200)}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (preview.files.isEmpty()) stringResource(R.string.rewind_no_files) else pluralStringResource(R.plurals.rewind_files, preview.files.size, preview.files.size) + "\n" + preview.files.take(12).joinToString("\n") { "• $it" },
                style = MaterialTheme.typography.bodySmall,
            )
            if (preview.remoteActions.isNotEmpty()) {
                Text(
                    stringResource(R.string.rewind_remote_warning) + "\n" + preview.remoteActions.joinToString("\n") { "• $it" },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (preview.gitActions.isNotEmpty()) {
                Text(stringResource(R.string.rewind_git_warning) + "\n" + preview.gitActions.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onRewind(true, true) }, modifier = Modifier.fillMaxWidth().testTag("rewind_both")) { Text(stringResource(R.string.rewind_both)) }
            OutlinedButton(onClick = { onRewind(false, true) }, modifier = Modifier.fillMaxWidth().testTag("rewind_conversation")) { Text(stringResource(R.string.rewind_conversation)) }
            OutlinedButton(onClick = { onRewind(true, false) }, enabled = preview.files.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("rewind_code")) { Text(stringResource(R.string.rewind_code)) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
fun CommandHelpDialog(commands: List<SlashCommand>, onDismiss: () -> Unit) {
    val all = SlashCommands.BUILTIN + commands.filter { c -> SlashCommands.BUILTIN.none { it.name == c.name } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.commands_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEach { c ->
                    Text("/${c.name}" + (c.argumentHint?.let { " $it" } ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                    Text(c.description, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.commands_custom_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("help_dismiss")) { Text(stringResource(R.string.dismiss)) } },
    )
}
