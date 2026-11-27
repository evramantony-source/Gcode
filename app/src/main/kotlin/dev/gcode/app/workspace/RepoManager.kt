package dev.gcode.app.workspace

import dev.gcode.app.di.ApplicationScope
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.db.RemoteRepoEntity
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.core.model.RepoRef
import dev.gcode.git.CallbackProgressMonitor
import dev.gcode.git.GitCredentials
import dev.gcode.git.GitProgress
import dev.gcode.git.JGitRepository
import dev.gcode.git.WorkspaceManager
import dev.gcode.github.api.ReposApi
import dev.gcode.github.http.GitHubJson
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.GhRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of a running clone. */
data class CloneState(val repo: String, val progress: GitProgress? = null, val error: String? = null, val done: Boolean = false)

/** Repositories: GitHub listings (cached), create, fork, clone, open, delete local copy (ARCHITECTURE.md section 12, screen 2). */
@Singleton
class RepoManager @Inject constructor(
    private val repos: ReposApi,
    private val db: GCodeDatabase,
    private val workspace: WorkspaceManager,
    private val account: GitHubAccountManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val remoteDao = db.remoteRepos()
    private val localDao = db.localRepos()
    private val _clones = MutableStateFlow<Map<String, CloneState>>(emptyMap())
    val clones: StateFlow<Map<String, CloneState>> = _clones.asStateFlow()
    private val cloneJobs = mutableMapOf<String, Job>()

    val remote: Flow<List<GhRepo>> = remoteDao.observeAll().map { rows -> rows.mapNotNull { decode(it.json) } }
    val local: Flow<List<LocalRepoEntity>> = localDao.observeAll()

    suspend fun refresh() {
        val list = repos.myRepos()
        val now = System.currentTimeMillis()
        remoteDao.replaceAll(list.map { RemoteRepoEntity(it.fullName, GitHubJson.encodeToString(GhRepo.serializer(), it), it.pushedAt, now) })
    }

    suspend fun search(query: String, onlyMine: Boolean): List<GhRepo> {
        val login = settings.current().githubLogin
        val q = if (onlyMine && login != null) "$query in:name user:$login fork:true" else query
        return repos.searchRepos(q).items
    }

    suspend fun orgs() = runCatching { (repos as? dev.gcode.github.api.UsersApi)?.orgs().orEmpty() }.getOrDefault(emptyList())

    suspend fun create(request: CreateRepoRequest, org: String?): GhRepo {
        val created = repos.createRepo(request, org)
        remoteDao.insert(RemoteRepoEntity(created.fullName, GitHubJson.encodeToString(GhRepo.serializer(), created), created.pushedAt, System.currentTimeMillis()))
        return created
    }

    suspend fun fork(ref: RepoRef, organization: String? = null): GhRepo {
        val fork = repos.fork(ref, organization)
        remoteDao.insert(RemoteRepoEntity(fork.fullName, GitHubJson.encodeToString(GhRepo.serializer(), fork), fork.pushedAt, System.currentTimeMillis()))
        return fork
    }

    suspend fun gitignoreTemplates() = repos.gitignoreTemplates()
    suspend fun licenses() = repos.licenses()

    fun isCloned(ref: RepoRef) = workspace.isCloned(ref)

    /** Clones in the background; progress is published in [clones]. */
    fun clone(repo: GhRepo, singleBranch: Boolean) {
        val ref = RepoRef.parse(repo.fullName) ?: return
        if (cloneJobs[repo.fullName]?.isActive == true) return
        _clones.update { it + (repo.fullName to CloneState(repo.fullName)) }
        cloneJobs[repo.fullName] = scope.launch {
            val job = coroutineContext[Job]
            try {
                val token = account.token()
                val url = repo.cloneUrl ?: "https://github.com/${repo.fullName}.git"
                withContext(Dispatchers.IO) {
                    JGitRepository.clone(
                        url, workspace.dirFor(ref), token?.let { GitCredentials(it) },
                        singleBranch = repo.defaultBranch.takeIf { singleBranch },
                        monitor = CallbackProgressMonitor(
                            onProgress = { p -> _clones.update { it + (repo.fullName to CloneState(repo.fullName, p)) } },
                            cancelled = { job?.isActive != true },
                        ),
                    ).close()
                }
                val now = System.currentTimeMillis()
                localDao.upsert(
                    LocalRepoEntity(
                        fullName = repo.fullName,
                        path = workspace.dirFor(ref).absolutePath,
                        defaultBranch = repo.defaultBranch,
                        remoteUrl = url,
                        clonedAt = now,
                        lastOpenedAt = now,
                    ),
                )
                open(repo.fullName)
                _clones.update { it + (repo.fullName to CloneState(repo.fullName, done = true)) }
            } catch (e: Exception) {
                val cancelled = job?.isActive != true
                withContext(kotlinx.coroutines.NonCancellable) { if (!workspace.isCloned(ref)) workspace.delete(ref) }
                _clones.update { it + (repo.fullName to CloneState(repo.fullName, error = if (cancelled) "Clone cancelled" else e.message ?: "Clone failed")) }
            } finally {
                cloneJobs.remove(repo.fullName)
            }
        }
    }

    fun cancelClone(fullName: String) {
        cloneJobs[fullName]?.cancel()
    }

    fun clearCloneState(fullName: String) = _clones.update { it - fullName }

    suspend fun open(fullName: String) {
        localDao.touch(fullName, System.currentTimeMillis())
        settings.update { it.copy(activeRepo = fullName) }
    }

    suspend fun deleteLocal(fullName: String) {
        val ref = RepoRef.parse(fullName) ?: return
        withContext(Dispatchers.IO) { workspace.delete(ref) }
        localDao.delete(fullName)
        if (settings.current().activeRepo == fullName) settings.update { it.copy(activeRepo = null) }
    }

    suspend fun updateRepoSettings(entity: LocalRepoEntity) = localDao.upsert(entity)

    private fun decode(json: String): GhRepo? = runCatching { GitHubJson.decodeFromString(GhRepo.serializer(), json) }.getOrNull()

    @Suppress("unused")
    private fun CoroutineScope.alive() = isActive
}
