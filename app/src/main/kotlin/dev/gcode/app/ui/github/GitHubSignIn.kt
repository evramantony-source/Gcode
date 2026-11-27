package dev.gcode.app.ui.github

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.R
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.app.github.GitHubAuthState
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GitHubSignInViewModel @Inject constructor(val account: GitHubAccountManager) : ViewModel()

/** Device flow (primary) with a personal-access-token fallback (decision D2). */
@Composable
fun GitHubSignInCard(state: GitHubAuthState, account: GitHubAccountManager, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showPat by rememberSaveable { mutableStateOf(false) }
    var pat by rememberSaveable { mutableStateOf("") }
    Card(modifier.fillMaxWidth().testTag("github_sign_in")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.github_title), style = MaterialTheme.typography.titleMedium)
            when (state) {
                is GitHubAuthState.SignedIn -> {
                    Text(stringResource(R.string.github_signed_in_as, state.name?.let { "$it (${state.login})" } ?: state.login))
                    if (state.missingScopes.isNotEmpty()) {
                        Text(stringResource(R.string.github_missing_scopes, state.missingScopes.joinToString()), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.tokenType == GitHubAccountManager.TOKEN_FINE_GRAINED) {
                        Text(stringResource(R.string.github_fine_grained_note), style = MaterialTheme.typography.bodySmall)
                        GitHubAccountManager.FINE_GRAINED_PERMISSIONS.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = account::signOut) { Text(stringResource(R.string.github_sign_out)) }
                }
                is GitHubAuthState.AwaitingUser -> {
                    Text(stringResource(R.string.github_enter_code))
                    Text(state.userCode, fontFamily = FontFamily.Monospace, fontSize = 28.sp, modifier = Modifier.testTag("github_user_code"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", state.userCode))) } }) {
                            Text(stringResource(R.string.copy))
                        }
                        Button(onClick = { uri.openUri(state.verificationUri) }) { Text(stringResource(R.string.github_open_device_page)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.github_waiting, state.secondsLeft / 60, state.secondsLeft % 60), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = account::cancel) { Text(stringResource(R.string.cancel)) }
                }
                GitHubAuthState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.verifying))
                }
                else -> {
                    if (state is GitHubAuthState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.github_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { account.startDeviceFlow() }, modifier = Modifier.testTag("github_device_flow")) { Text(stringResource(R.string.github_sign_in)) }
                    TextButton(onClick = { showPat = !showPat }) { Text(stringResource(R.string.github_use_pat)) }
                    if (showPat) {
                        OutlinedTextField(
                            value = pat,
                            onValueChange = { pat = it },
                            label = { Text(stringResource(R.string.github_pat_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.github_pat_permissions), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { account.signInWithToken(pat) }, enabled = pat.isNotBlank()) { Text(stringResource(R.string.onboarding_verify)) }
                    }
                }
            }
        }
    }
}
