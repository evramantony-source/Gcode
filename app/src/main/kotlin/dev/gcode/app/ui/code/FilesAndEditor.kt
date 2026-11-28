package dev.gcode.app.ui.code

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gcode.agent.workspace.WsEntry
import dev.gcode.app.R
import dev.gcode.app.editor.EditorSupport
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/** Expandable file tree. Directories load lazily. */
@Composable
fun FileTree(state: CodeUiState, onToggle: (String) -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val rows = remember(state.children, state.expanded) { flatten(state, "", 0) }
    LazyColumn(modifier.testTag("file_tree")) {
        items(rows, key = { it.first.path }) { (entry, depth) ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { if (entry.isDirectory) onToggle(entry.path) else onOpen(entry.path) }
                    .padding(start = (12 + depth * 16).dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("file_${entry.path}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    !entry.isDirectory -> R.drawable.ic_file
                    entry.path in state.expanded -> R.drawable.ic_folder_open
                    else -> R.drawable.ic_folder
                }
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.name, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.files_empty), modifier = Modifier.padding(16.dp)) }
    }
}

private fun flatten(state: CodeUiState, dir: String, depth: Int): List<Pair<WsEntry, Int>> =
    state.children[dir].orEmpty().flatMap { e ->
        listOf(e to depth) + if (e.isDirectory && e.path in state.expanded) flatten(state, e.path, depth + 1) else emptyList()
    }

/** Holds one Sora editor per open tab, so undo history survives tab switches. */
class EditorHolder {
    val editors = mutableMapOf<String, CodeEditor>()
    val versions = mutableMapOf<String, Int>()
    fun release(path: String) {
        editors.remove(path)?.release()
        versions.remove(path)
    }
}

@Composable
fun EditorPane(
    state: CodeUiState,
    holder: EditorHolder,
    dark: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onDirty: (String, Boolean) -> Unit,
    onSave: (String, String) -> Unit,
    onShowFiles: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tab = state.tabs.firstOrNull { it.path == state.active }
    var searching by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            onShowFiles?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.files_show)) } }
            state.tabs.forEach { t ->
                InputChip(
                    selected = t.path == state.active,
                    onClick = { onSelect(t.path) },
                    label = { Text((if (t.dirty) "● " else "") + t.path.substringAfterLast('/')) },
                    trailingIcon = {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_tab), Modifier.size(16.dp).clickable {
                            holder.release(t.path)
                            onClose(t.path)
                        })
                    },
                    modifier = Modifier.padding(horizontal = 2.dp).testTag("tab_${t.path}"),
                )
            }
        }
        if (tab == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.editor_no_file)) }
            return@Column
        }
        val editor = holder.editors[tab.path]
        Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tab.path, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                key(tick) {
                    IconButton(onClick = { editor?.undo(); tick++ }, enabled = editor?.canUndo() == true) { Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo)) }
                    IconButton(onClick = { editor?.redo(); tick++ }, enabled = editor?.canRedo() == true) { Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo)) }
                }
                IconButton(onClick = { searching = !searching }) { Icon(painterResource(R.drawable.ic_search), stringResource(R.string.find)) }
                IconButton(onClick = { editor?.let { onSave(tab.path, it.text.toString()) } }, enabled = tab.dirty && !tab.readOnly, modifier = Modifier.testTag("save_file")) {
                    Icon(painterResource(R.drawable.ic_save), stringResource(R.string.save))
                }
            }
        }
        tab.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(8.dp, 2.dp)) }
        if (searching && editor != null) SearchBar(editor.searcher) { searching = false }
        HorizontalDivider()
        if (!state.editorReady) return@Column
        val context = LocalContext.current
        key(tab.path) {
            AndroidView(
                factory = {
                    val view = holder.editors.getOrPut(tab.path) {
                        CodeEditor(context).apply {
                            EditorSupport.configure(this, tab.path, dark)
                            setText(tab.text)
                            isEditable = !tab.readOnly
                            subscribeAlways(ContentChangeEvent::class.java) { event ->
                                if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                                    onDirty(tab.path, true)
                                    tick++
                                }
                            }
                        }.also { holder.versions[tab.path] = tab.version }
                    }
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                update = { view ->
                    // The file changed on disk and the tab was clean: show the new text.
                    if (holder.versions[tab.path] != tab.version) {
                        holder.versions[tab.path] = tab.version
                        view.setText(tab.text)
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("code_editor"),
            )
        }
        DisposableEffect(Unit) { onDispose { } }
    }
}

@Composable
private fun SearchBar(searcher: EditorSearcher, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var ignoreCase by rememberSaveable { mutableStateOf(true) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var matches by remember { mutableIntStateOf(0) }
    fun run() {
        if (query.isEmpty()) {
            searcher.stopSearch()
            matches = 0
        } else {
            runCatching { searcher.search(query, EditorSearcher.SearchOptions(ignoreCase, regex)) }
            matches = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        }
    }
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it; run() },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.find)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).testTag("find_field"),
            )
            IconButton(onClick = { runCatching { searcher.gotoPrevious() } }) { Icon(painterResource(R.drawable.ic_expand_less), stringResource(R.string.find_previous)) }
            IconButton(onClick = { runCatching { searcher.gotoNext() } }) { Icon(painterResource(R.drawable.ic_expand_more), stringResource(R.string.find_next)) }
            IconButton(onClick = { searcher.stopSearch(); onClose() }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dismiss)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !ignoreCase, onClick = { ignoreCase = !ignoreCase; run() }, label = { Text("Aa") })
            FilterChip(selected = regex, onClick = { regex = !regex; run() }, label = { Text(".*") })
            Text(pluralStringResource(R.plurals.find_matches, matches, matches), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(replacement, { replacement = it }, singleLine = true, placeholder = { Text(stringResource(R.string.replace)) }, modifier = Modifier.weight(1f))
            TextButton(onClick = { runCatching { searcher.replaceCurrentMatch(replacement) }; run() }, enabled = matches > 0) { Text(stringResource(R.string.replace)) }
            TextButton(onClick = { runCatching { searcher.replaceAll(replacement) }; run() }, enabled = matches > 0) { Text(stringResource(R.string.replace_all)) }
        }
    }
}
