package dev.gcode.app.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.gcode.app.R
import dev.gcode.app.ui.theme.CodeTextStyle

/** Full-screen diff: unified, or side by side (default on wide screens). Always left-to-right. */
@Composable
fun DiffViewerDialog(title: String, diff: String, onDismiss: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var split by rememberSaveable { mutableStateOf(wide) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("diff_viewer")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dismiss)) }
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                    FilterChip(selected = split, onClick = { split = !split }, label = { Text(stringResource(R.string.diff_side_by_side)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_split), null) })
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    if (diff.isBlank()) {
                        Text(stringResource(R.string.approval_no_changes), modifier = Modifier.padding(16.dp))
                    } else if (split) {
                        SplitDiff(diff)
                    } else {
                        DiffText(diff, Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitDiff(diff: String) {
    val rows = remember(diff) { SideBySide.rows(diff) }
    val added = Color(0x332E7D32)
    val removed = Color(0x33C62828)
    val scroll = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll)) {
        items(rows) { row ->
            when (row.kind) {
                SplitRow.Kind.FILE -> Text(row.right.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                SplitRow.Kind.HUNK -> Text(row.left.orEmpty(), style = CodeTextStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(4.dp))
                else -> Row {
                    Cell(row.leftNumber, row.left, if (row.kind == SplitRow.Kind.REMOVED || row.kind == SplitRow.Kind.CHANGED) removed else null)
                    Cell(row.rightNumber, row.right, if (row.kind == SplitRow.Kind.ADDED || row.kind == SplitRow.Kind.CHANGED) added else null)
                }
            }
        }
    }
}

@Composable
private fun Cell(number: Int?, text: String?, background: Color?) {
    Row(Modifier.width(480.dp).let { if (background != null) it.background(background) else it }) {
        Text(number?.toString().orEmpty(), style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp).padding(end = 6.dp))
        Text(text.orEmpty(), style = CodeTextStyle, maxLines = 1)
    }
}
