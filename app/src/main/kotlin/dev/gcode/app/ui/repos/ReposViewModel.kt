package dev.gcode.app.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.app.github.GitHubAuthState
import dev.gcode.app.workspace.CloneState
import dev.gcode.app.workspace.RepoManager
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.core.model.RepoRef
import dev.gcode.github.http.GitHubApiException
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.GhLicense
import dev.gcode.github.model.GhOrg
import dev.gcode.github.model.GhRepo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepoFilter { ALL, MINE, ORGS, CLONED }

data class CreateRepoForm(
    val owner: String? = null,
    val name: String = "",
    val description: String = "",
    val private: Boolean = true,
    val readme: Boolean = true,
    val gitignore: String? = null,
    val license: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class ReposUiState(
    val auth: GitHubAuthState = GitHubAuthState.SignedOut,
    val login: String? = null,
    val repos: List<GhRepo> = emptyList(),
    val local: List<LocalRepoEntity> = emptyList(),
    val activeRepo: String? = null,
    val clones: Map<String, CloneState> = emptyMap(),
    val query: String = "",
    val searchResults: List<GhRepo>? = null,
    val filter: RepoFilter = RepoFilter.ALL,
    val loading: Boolean = false,
    val message: String? = null,
    val create: CreateRepoForm? = null,
    val orgs: List<GhOrg> = emptyList(),
    val gitignoreTemplates: List<String> = emptyList(),
    val licenses: List<GhLicense> = emptyList(),
) {
    val visible: List<GhRepo>
        get() {
            val base = searchResults ?: repos
            val cloned = local.map { it.fullName }.toSet()
            return when (filter) {
                RepoFilter.ALL -> base
                RepoFilter.MINE -> base.filter { it.owner.login == login }
                RepoFilter.ORGS -> base.filter { it.owner.login != login }
                RepoFilter.CLONED -> base.filter { it.fullName in cloned }
            }
        }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ReposViewModel @Inject constructor(
    private val manager: RepoManager,
    private val account: GitHubAccountManager,
    settings: SettingsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ReposUiState())
    private var searchJob: Job? = null

    val state: StateFlow<ReposUiState> = combine(local, account.state, manager.remote, manager.local, combine(manager.clones, settings.settings) { c, s -> c to s }) { l, auth, remote, cloned, (clones, s) ->
        l.copy(auth = auth, login = s.githubLogin, repos = remote, local = cloned, clones = clones, activeRepo = s.activeRepo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReposUiState())

    init {
        viewModelScope.launch {
            account.state.collect { if (it is GitHubAuthState.SignedIn) refresh() }
        }
    }

    fun refresh() {
        local.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val error = runCatching { manager.refresh() }.exceptionOrNull()
            local.update { it.copy(loading = false, message = error?.let(::describe)) }
        }
    }

    fun setFilter(filter: RepoFilter) = local.update { it.copy(filter = filter) }

    fun onQuery(query: String) {
        local.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            local.update { it.copy(searchResults = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            local.update { it.copy(loading = true) }
            val result = runCatching { manager.search(query.trim(), onlyMine = false) }
            local.update { it.copy(loading = false, searchResults = result.getOrNull() ?: it.searchResults, message = result.exceptionOrNull()?.let(::describe)) }
        }
    }

    fun clone(repo: GhRepo, singleBranch: Boolean) = manager.clone(repo, singleBranch)
    fun cancelClone(fullName: String) = manager.cancelClone(fullName)
    fun clearClone(fullName: String) = manager.clearCloneState(fullName)
    fun open(fullName: String) = viewModelScope.launch { manager.open(fullName) }
    fun deleteLocal(fullName: String) = viewModelScope.launch { manager.deleteLocal(fullName) }
    fun isCloned(repo: GhRepo) = RepoRef.parse(repo.fullName)?.let(manager::isCloned) == true

    fun fork(repo: GhRepo) {
        val ref = RepoRef.parse(repo.fullName) ?: return
        viewModelScope.launch {
            val result = runCatching { manager.fork(ref) }
            local.update { it.copy(message = result.fold({ f -> "Forked to ${f.fullName}. GitHub may need a few seconds before it can be cloned." }, ::describe)) }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun openCreate() {
        local.update { it.copy(create = CreateRepoForm()) }
        viewModelScope.launch {
            val orgs = manager.orgs()
            val templates = runCatching { manager.gitignoreTemplates() }.getOrDefault(emptyList())
            val licenses = runCatching { manager.licenses() }.getOrDefault(emptyList())
            local.update { it.copy(orgs = orgs, gitignoreTemplates = templates, licenses = licenses) }
        }
    }

    fun updateCreate(transform: (CreateRepoForm) -> CreateRepoForm) = local.update { s -> s.copy(create = s.create?.let(transform)) }
    fun closeCreate() = local.update { it.copy(create = null) }

    fun submitCreate() {
        val form = local.value.create ?: return
        if (!RepoRef.isValidName(form.name.trim())) {
            updateCreate { it.copy(error = "Use letters, digits, '.', '-' or '_' for the name.") }
            return
        }
        updateCreate { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = CreateRepoRequest(
                name = form.name.trim(),
                description = form.description.trim().ifEmpty { null },
                private = form.private,
                autoInit = form.readme || form.gitignore != null || form.license != null,
                gitignoreTemplate = form.gitignore,
                licenseTemplate = form.license,
            )
            runCatching { manager.create(request, form.owner?.takeIf { it != state.value.login }) }
                .onSuccess { repo -> local.update { it.copy(create = null, message = "Created ${repo.fullName}") } }
                .onFailure { e -> updateCreate { it.copy(busy = false, error = describe(e)) } }
        }
    }

    private fun describe(e: Throwable): String = when {
        e is GitHubApiException && e.isUnauthorized -> "GitHub rejected the token. Sign in again in Settings."
        e is GitHubApiException && e.details.isNotEmpty() -> "${e.message}"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }
}
