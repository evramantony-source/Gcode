package dev.gcode.app.workspace

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.agent.tools.GitAccess
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.workspace.LocalWorkspaceFs
import dev.gcode.agent.workspace.WorkspaceFs
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.git.Author
import dev.gcode.git.GitCredentials
import dev.gcode.git.IgnoreMatcher
import dev.gcode.git.JGitRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The workspace the agent works in: the open cloned repository, or else a linked device folder. */
data class ActiveWorkspace(
    val key: String,
    val fs: WorkspaceFs,
    val repo: LocalRepoEntity?,
    val git: GitAccess?,
    val guard: SecretGuard,
)

@Singleton
class WorkspaceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val workspaceGit: WorkspaceGit,
) {
    suspend fun current(): ActiveWorkspace? {
        val s = settings.current()
        val denylist = SecretGuard.DEFAULT_DENYLIST + s.extraDenylist
        workspaceGit.active()?.let { repo ->
            val dir = File(repo.path)
            if (File(dir, ".git").isDirectory) {
                val ignore = IgnoreMatcher(dir)
                return ActiveWorkspace(
                    key = "repo:${repo.fullName}",
                    fs = LocalWorkspaceFs(dir, repo.fullName),
                    repo = repo,
                    git = gitAccess(repo),
                    guard = SecretGuard(denylist) { path, isDir -> ignore.isIgnored(path, isDir) },
                )
            }
        }
        s.safFolderUri?.let { uri ->
            val fs = runCatching { SafWorkspaceFs(context, Uri.parse(uri)) }.getOrNull() ?: return null
            return ActiveWorkspace("saf:$uri", fs, null, null, SecretGuard(denylist))
        }
        return null
    }

    private fun gitAccess(repo: LocalRepoEntity) = object : GitAccess {
        override suspend fun <T> withRepo(block: suspend (JGitRepository) -> T): T = workspaceGit.withRepo(repo) { r, _ -> block(r) }
        override suspend fun credentials(): GitCredentials? = workspaceGit.credentials()
        override suspend fun author(): Author = workspaceGit.author(repo)
        override suspend fun defaultBranch(): String = repo.defaultBranch
    }

    /** Workspace by key, for rewind (the active workspace may have changed since the checkpoint). */
    suspend fun byKey(key: String): WorkspaceFs? = when {
        key.startsWith("repo:") -> workspaceGit.dirForName(key.removePrefix("repo:"))?.takeIf { it.isDirectory }?.let { LocalWorkspaceFs(it) }
        key.startsWith("saf:") -> runCatching { SafWorkspaceFs(context, Uri.parse(key.removePrefix("saf:"))) }.getOrNull()
        else -> null
    }
}
