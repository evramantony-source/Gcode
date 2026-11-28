package dev.gcode.app.ui.collab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gcode.app.R
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.app.github.GitHubAuthState
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.model.RepoRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/** The repository the PR, issue and release screens work on: the open clone, when signed in to GitHub. */
data class CollabTarget(val ref: RepoRef?, val entity: LocalRepoEntity?, val signedIn: Boolean)

@Singleton
class CollabTargets @Inject constructor(private val git: WorkspaceGit, private val account: GitHubAccountManager) {
    val target: Flow<CollabTarget> = combine(git.activeRepo, account.state.map { it is GitHubAuthState.SignedIn }) { repo, signedIn ->
        CollabTarget(repo?.let { RepoRef.parse(it.fullName) }, repo, signedIn)
    }.distinctUntilChanged { a, b -> a.ref == b.ref && a.signedIn == b.signedIn }
}

/** "Open a repository" / "Sign in" placeholder for the collaboration screens. */
@Composable
fun CollabEmpty(target: CollabTarget, modifier: Modifier = Modifier, onOpenRepos: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (!target.signedIn) R.string.builds_sign_in else R.string.collab_no_repo), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenRepos) { Text(stringResource(R.string.builds_open_repos)) }
    }
}

@Composable
fun MessageDialog(error: String?, message: String?, onDismiss: () -> Unit) {
    val text = error ?: message ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { if (error != null) Icon(painterResource(R.drawable.ic_error), null) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}

fun formatIso(iso: String?): String = iso?.let {
    runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
}.orEmpty()
