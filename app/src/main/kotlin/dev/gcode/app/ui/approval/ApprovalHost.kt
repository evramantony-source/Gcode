package dev.gcode.app.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.app.R
import dev.gcode.app.agent.PendingPrompt
import dev.gcode.app.agent.PromptBroker
import dev.gcode.app.ui.code.DiffText
import dev.gcode.app.ui.theme.CodeTextStyle
import javax.inject.Inject

@HiltViewModel
class ApprovalViewModel @Inject constructor(val broker: PromptBroker) : ViewModel()

/** Shows the agent's pending approval, whichever screen is open (ARCHITECTURE.md sections 7.2 and 7.3). */
@Composable
fun ApprovalHost(viewModel: ApprovalViewModel = hiltViewModel()) {
    val pending by viewModel.broker.pending.collectAsStateWithLifecycle()
    val prompt = pending ?: return
    when (val request = prompt.request) {
        is ApprovalRequest.FileChange -> DiffSheet(prompt, request)
        is ApprovalRequest.Action -> ActionDialog(prompt, request)
        is ApprovalRequest.SecretAccess -> SecretDialog(prompt, request)
        is ApprovalRequest.Plan -> PlanSheet(prompt, request)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffSheet(prompt: PendingPrompt, request: ApprovalRequest.FileChange) {
    var editing by remember(request) { mutableStateOf(false) }
    var draft by remember(request) { mutableStateOf(request.after.orEmpty()) }
    var rejecting by remember(request) { mutableStateOf(false) }
    var comment by remember(request) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = { prompt.answer(ApprovalResult.Rejected("Dismissed without a decision")) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("diff_sheet"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            Text(request.path, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editing) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = CodeTextStyle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp).testTag("diff_edit_field"),
                    )
                }
            } else {
                DiffText(request.diff.ifBlank { stringResource(R.string.approval_no_changes) }, Modifier.heightIn(max = 480.dp))
            }
            if (rejecting) {
                OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.approval_reject_comment)) }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (rejecting) {
                    TextButton(onClick = { prompt.answer(ApprovalResult.Rejected(comment.ifBlank { null })) }) { Text(stringResource(R.string.approval_reject)) }
                } else {
                    TextButton(onClick = { rejecting = true }, modifier = Modifier.testTag("diff_reject")) { Text(stringResource(R.string.approval_reject)) }
                }
                OutlinedButton(onClick = { editing = !editing }, modifier = Modifier.testTag("diff_edit")) {
                    Text(stringResource(if (editing) R.string.approval_show_diff else R.string.approval_edit))
                }
                Button(
                    onClick = { prompt.answer(if (editing && draft != request.after) ApprovalResult.ApprovedWithEdit(draft) else ApprovalResult.Approved) },
                    modifier = Modifier.testTag("diff_accept"),
                ) { Text(stringResource(R.string.approval_accept)) }
            }
        }
    }
}

@Composable
private fun ActionDialog(prompt: PendingPrompt, request: ApprovalRequest.Action) {
    var comment by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.answer(ApprovalResult.Rejected(null)) },
        modifier = Modifier.testTag(if (request.dangerous) "confirm_dangerous" else "confirm_action"),
        title = {
            Text(
                if (request.dangerous) stringResource(R.string.approval_confirm_dangerous, request.title) else request.title,
                color = if (request.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.dangerous) Text(stringResource(R.string.approval_dangerous_body), style = MaterialTheme.typography.bodyMedium)
                Text(request.detail, style = CodeTextStyle)
                OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.approval_reject_comment)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { prompt.answer(ApprovalResult.Approved) },
                colors = if (request.dangerous) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.testTag("action_approve"),
            ) { Text(stringResource(if (request.dangerous) R.string.approval_confirm else R.string.approval_allow)) }
        },
        dismissButton = { TextButton(onClick = { prompt.answer(ApprovalResult.Rejected(comment.ifBlank { null })) }) { Text(stringResource(R.string.approval_reject)) } },
    )
}

@Composable
private fun SecretDialog(prompt: PendingPrompt, request: ApprovalRequest.SecretAccess) {
    AlertDialog(
        onDismissRequest = { prompt.answer(ApprovalResult.Rejected(null)) },
        title = { Text(request.title, color = MaterialTheme.colorScheme.error) },
        text = { Text(stringResource(R.string.approval_secret_body, request.path, request.reason)) },
        confirmButton = { TextButton(onClick = { prompt.answer(ApprovalResult.Approved) }) { Text(stringResource(R.string.approval_allow_once)) } },
        dismissButton = { Button(onClick = { prompt.answer(ApprovalResult.Rejected("The user keeps this file private.")) }) { Text(stringResource(R.string.approval_keep_private)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(prompt: PendingPrompt, request: ApprovalRequest.Plan) {
    var mode by remember(request) { mutableStateOf(PermissionMode.ASK) }
    var feedback by remember(request) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = { prompt.answer(ApprovalResult.Rejected("Dismissed")) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("plan_sheet"),
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium)
            Markdown(content = request.markdown, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.plan_mode_choice), style = MaterialTheme.typography.titleSmall)
            listOf(PermissionMode.ASK, PermissionMode.AUTO_ACCEPT_EDITS, PermissionMode.FULL_AUTO).forEach { m ->
                Row(Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { mode = m }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = { mode = m })
                    Text(stringResource(modeLabel(m)))
                }
            }
            OutlinedTextField(feedback, { feedback = it }, label = { Text(stringResource(R.string.plan_feedback)) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { prompt.answer(ApprovalResult.Rejected(feedback.ifBlank { null })) }) { Text(stringResource(R.string.plan_revise)) }
                Button(onClick = { prompt.answer(ApprovalResult.PlanApproved(mode.id)) }, modifier = Modifier.testTag("plan_approve")) { Text(stringResource(R.string.plan_approve)) }
            }
        }
    }
}

fun modeLabel(mode: PermissionMode) = when (mode) {
    PermissionMode.ASK -> R.string.mode_ask
    PermissionMode.AUTO_ACCEPT_EDITS -> R.string.mode_auto_accept
    PermissionMode.PLAN -> R.string.mode_plan
    PermissionMode.FULL_AUTO -> R.string.mode_full_auto
}
