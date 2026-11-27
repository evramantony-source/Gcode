package dev.gcode.app.workspace

import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.core.model.RepoRef
import dev.gcode.git.Author
import dev.gcode.git.GitCredentials
import dev.gcode.git.JGitRepository
import dev.gcode.git.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Git operations on the open repository, with credentials and author filled in. Calls are serialised. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WorkspaceGit @Inject constructor(
    private val settings: SettingsRepository,
    private val db: GCodeDatabase,
    private val workspace: WorkspaceManager,
    private val account: GitHubAccountManager,
) {
    private val mutex = Mutex()

    val activeRepo: Flow<LocalRepoEntity?> = settings.settings.map { it.activeRepo }.distinctUntilChanged()
        .flatMapLatest { name -> if (name == null) flowOf(null) else db.localRepos().observe(name) }

    suspend fun active(): LocalRepoEntity? = settings.current().activeRepo?.let { db.localRepos().get(it) }

    suspend fun activeDir(): File? = active()?.let { File(it.path) }?.takeIf { File(it, ".git").isDirectory }

    suspend fun credentials(): GitCredentials? = account.token()?.let { GitCredentials(it) }

    /** Author for commits: per-repo override, else the GitHub profile with the noreply address. */
    suspend fun author(repo: LocalRepoEntity? = null): Author {
        val s = settings.current()
        val name = repo?.authorName ?: s.githubName ?: s.githubLogin ?: "GCode user"
        val email = repo?.authorEmail ?: if (s.githubUserId != null && s.githubLogin != null) "${s.githubUserId}+${s.githubLogin}@users.noreply.github.com" else "gcode@users.noreply.github.com"
        return Author(name, email)
    }

    /** Runs [block] on the open repository (IO dispatcher, one operation at a time). */
    suspend fun <T> withRepo(block: suspend (JGitRepository, LocalRepoEntity) -> T): T {
        val repo = active() ?: throw IllegalStateException("No repository is open. Open one from Repos.")
        return withRepo(repo, block)
    }

    suspend fun <T> withRepo(repo: LocalRepoEntity, block: suspend (JGitRepository, LocalRepoEntity) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { JGitRepository.open(File(repo.path)).use { block(it, repo) } }
    }

    fun refOf(repo: LocalRepoEntity): RepoRef? = RepoRef.parse(repo.fullName)

    fun dirFor(ref: RepoRef): File = workspace.dirFor(ref)
}
