package dev.gcode.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gcode.app.R
import dev.gcode.gemini.select.ModelCatalog
import dev.gcode.gemini.select.ThinkingSupport

/** Model picker: every model that supports generateContent, grouped by family, plus thinking options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: ModelCatalog,
    selected: String,
    thinkingLevel: String?,
    showThoughts: Boolean,
    refreshing: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onThinkingLevel: (String?) -> Unit,
    onShowThoughts: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = catalog.chatModels.groupBy { ModelCatalog.family(it.id) }
    val options = ThinkingSupport.optionsFor(selected, catalog.find(selected)?.thinking ?: false)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("model_picker")) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.model_picker_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh_models)) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            if (options.levels.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.thinking_level), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = thinkingLevel == null, onClick = { onThinkingLevel(null) }, label = { Text(stringResource(R.string.thinking_default)) })
                        options.levels.forEach { level ->
                            FilterChip(selected = thinkingLevel == level.wire, onClick = { onThinkingLevel(level.wire) }, label = { Text(level.wire) })
                        }
                    }
                }
            }
            if (options.supported) {
                item {
                    ListItem(
                        trailingContent = { Switch(checked = showThoughts, onCheckedChange = onShowThoughts) },
                        supportingContent = { Text(stringResource(R.string.show_thoughts_body)) },
                    ) { Text(stringResource(R.string.show_thoughts)) }
                    HorizontalDivider()
                }
            }
            groups.forEach { (family, models) ->
                item(key = "h_$family") {
                    Text(family, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                }
                items(models, key = { it.id }) { model ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(model.id) }.testTag("model_${model.id}"),
                        leadingContent = { RadioButton(selected = model.id == selected, onClick = { onSelect(model.id) }) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    model.id.takeIf { it != model.displayName },
                                    formatTokens(model.inputTokenLimit)?.let { stringResource(R.string.context_tokens, it) },
                                    stringResource(R.string.thinking_badge).takeIf { model.thinking },
                                    stringResource(R.string.not_for_agent).takeIf { !ModelCatalog.isAgentCapable(model.id) },
                                ).joinToString(" · "),
                            )
                        },
                    ) { Text(model.displayName ?: model.id) }
                }
            }
            if (catalog.chatModels.isEmpty()) {
                item { AssistChip(onClick = onRefresh, label = { Text(stringResource(R.string.no_models)) }, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

fun formatTokens(tokens: Int): String? = when {
    tokens <= 0 -> null
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0).replace(".0M", "M")
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}
