package dev.gcode.app.github

import dev.gcode.app.BuildConfig
import dev.gcode.app.di.ApplicationScope
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.github.GitHubApi
import dev.gcode.github.api.UsersApi
import dev.gcode.github.auth.DeviceFlow
import dev.gcode.github.auth.DeviceFlowResult
import dev.gcode.github.http.GitHubApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Sign-in state for the GitHub account (ARCHITECTURE.md section 8.1). */
sealed interface GitHubAuthState {
    data object SignedOut : GitHubAuthState
    data class AwaitingUser(val userCode: String, val verificationUri: String, val secondsLeft: Long) : GitHubAuthState
    data object Verifying : GitHubAuthState
    data class SignedIn(val login: String, val name: String?, val tokenType: String, val missingScopes: Set<String>) : GitHubAuthState
    data class Error(val message: String) : GitHubAuthState
}

@Singleton
class GitHubAccountManager @Inject constructor(
    private val client: OkHttpClient,
    private val users: UsersApi,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<GitHubAuthState>(GitHubAuthState.SignedOut)
    val state: StateFlow<GitHubAuthState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch { restore() }
    }

    private suspend fun restore() {
        val s = settings.current()
        val login = s.githubLogin
        if (secrets.get(SecretKey.GITHUB_TOKEN) != null && login != null) {
            _state.value = GitHubAuthState.SignedIn(login, s.githubName, s.githubTokenType ?: TOKEN_OAUTH, emptySet())
        }
    }

    suspend fun clientId(): String = settings.current().githubClientIdOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.GITHUB_CLIENT_ID

    /** Starts the device flow, optionally asking for extra scopes (e.g. delete_repo). */
    fun startDeviceFlow(extraScopes: List<String> = emptyList()) {
        job?.cancel()
        job = scope.launch {
            runCatching {
                val flow = DeviceFlow(client, clientId())
                val code = flow.start(GitHubApi.SIGN_IN_SCOPES + extraScopes)
                _state.value = GitHubAuthState.AwaitingUser(code.userCode, code.verificationUri, code.expiresIn.toLong())
                when (val result = flow.awaitToken(code) { left -> _state.value = GitHubAuthState.AwaitingUser(code.userCode, code.verificationUri, left) }) {
                    is DeviceFlowResult.Success -> finishSignIn(result.token, TOKEN_OAUTH)
                    DeviceFlowResult.Denied -> _state.value = GitHubAuthState.Error("Sign-in was cancelled on GitHub.")
                    DeviceFlowResult.Expired -> _state.value = GitHubAuthState.Error("The code expired. Start again.")
                    is DeviceFlowResult.Failed -> _state.value = GitHubAuthState.Error(result.message)
                }
            }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _state.value = GitHubAuthState.Error(e.message ?: "Sign-in failed") }
        }
    }

    fun cancel() {
        job?.cancel()
        scope.launch { restore().also { if (_state.value !is GitHubAuthState.SignedIn) _state.value = GitHubAuthState.SignedOut } }
    }

    /** Personal access token fallback: fine-grained (`github_pat_`) or classic (`ghp_`). */
    fun signInWithToken(raw: String) {
        val token = raw.trim()
        job?.cancel()
        job = scope.launch {
            val type = when {
                token.startsWith("github_pat_") -> TOKEN_FINE_GRAINED
                token.startsWith("ghp_") -> TOKEN_CLASSIC
                token.startsWith("gho_") -> TOKEN_OAUTH
                else -> {
                    _state.value = GitHubAuthState.Error("That does not look like a GitHub token (github_pat_… or ghp_…).")
                    return@launch
                }
            }
            finishSignIn(token, type)
        }
    }

    private suspend fun finishSignIn(token: String, type: String) {
        _state.value = GitHubAuthState.Verifying
        val previous = secrets.get(SecretKey.GITHUB_TOKEN)
        secrets.put(SecretKey.GITHUB_TOKEN, token)
        try {
            val me = users.me()
            val scopes = users.tokenScopes()
            val missing = if (type == TOKEN_FINE_GRAINED || scopes == null) emptySet() else REQUIRED_SCOPES - scopes
            settings.update { it.copy(githubLogin = me.login, githubUserId = me.id, githubName = me.name, githubTokenType = type) }
            secrets.put(SecretKey.GITHUB_TOKEN_TYPE, type)
            _state.value = GitHubAuthState.SignedIn(me.login, me.name, type, missing)
        } catch (e: Exception) {
            if (previous != null) secrets.put(SecretKey.GITHUB_TOKEN, previous) else secrets.remove(SecretKey.GITHUB_TOKEN)
            _state.value = GitHubAuthState.Error(
                if (e is GitHubApiException && e.isUnauthorized) "GitHub rejected the token." else e.message ?: "Could not verify the token",
            )
        }
    }

    fun signOut() {
        job?.cancel()
        scope.launch {
            secrets.remove(SecretKey.GITHUB_TOKEN)
            secrets.remove(SecretKey.GITHUB_TOKEN_TYPE)
            settings.update { it.copy(githubLogin = null, githubUserId = null, githubName = null, githubTokenType = null) }
            _state.value = GitHubAuthState.SignedOut
        }
    }

    suspend fun token(): String? = secrets.get(SecretKey.GITHUB_TOKEN)

    companion object {
        const val TOKEN_OAUTH = "oauth"
        const val TOKEN_FINE_GRAINED = "pat_fine_grained"
        const val TOKEN_CLASSIC = "pat_classic"
        val REQUIRED_SCOPES = setOf("repo", "workflow", "read:org")

        /** Fine-grained PAT repository permissions GCode needs (shown as a checklist). */
        val FINE_GRAINED_PERMISSIONS = listOf(
            "Contents: read and write", "Workflows: read and write", "Actions: read and write",
            "Pull requests: read and write", "Issues: read and write", "Administration: read and write",
            "Secrets: read and write", "Metadata: read",
        )
    }
}
