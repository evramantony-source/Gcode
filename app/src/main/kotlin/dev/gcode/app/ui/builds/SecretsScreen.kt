package dev.gcode.app.ui.builds

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.R
import dev.gcode.app.ui.collab.CollabEmpty
import dev.gcode.app.ui.collab.CollabTarget
import dev.gcode.app.ui.collab.MessageDialog
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.core.model.RepoRef

/** Repository secrets (More › Secrets): names, set through a sealed box (always confirmed), stable debug key. */
@Composable
fun SecretsScreen(onBack: () -> Unit, onOpenRepos: () -> Unit, viewModel: BuildsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.repo?.fullName, state.signedIn) { if (state.repo != null && state.signedIn) viewModel.loadSecrets() }
    GCodeScreen(title = stringResource(R.string.secrets_title), modifier = Modifier.testTag("screen_secrets"), onBack = onBack) { padding ->
        if (state.repo == null || !state.signedIn) {
            CollabEmpty(CollabTarget(state.repo?.let { RepoRef.parse(it.fullName) }, state.repo, state.signedIn), Modifier.padding(padding), onOpenRepos)
        } else {
            SecretsContent(state, viewModel, Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp))
        }
    }
    state.confirm?.let { BuildConfirmDialog(it, state, viewModel) }
    MessageDialog(state.error, state.message, viewModel::dismissMessage)
}
