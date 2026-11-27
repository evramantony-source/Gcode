package dev.gcode.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.github.AgentHost
import dev.gcode.agent.tools.github.BuildFixGuard
import dev.gcode.agent.tools.github.DownloadedApk
import dev.gcode.agent.tools.github.GitHubToolDeps
import dev.gcode.agent.tools.github.GitHubTools
import dev.gcode.agent.tools.github.StartedRun
import dev.gcode.agent.tools.web.WebFetchTool
import dev.gcode.agent.tools.web.WebSearchTool
import dev.gcode.app.builds.ApkInstaller
import dev.gcode.app.builds.BuildService
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.app.github.GitHubAuthState
import dev.gcode.app.workspace.ActiveWorkspace
import dev.gcode.app.workspace.RepoManager
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.core.model.RepoRef
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.select.ModelSelector
import dev.gcode.gemini.transport.GeminiTransport
import dev.gcode.github.GitHubClient
import dev.gcode.github.actions.WorkflowTemplate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub, Actions and web tools for the agent (ARCHITECTURE.md sections 6.4–6.6). */
@Singleton
class AppExtraTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val github: GitHubClient,
    private val okHttp: OkHttpClient,
    private val transport: GeminiTransport,
    private val account: GitHubAccountManager,
    private val repos: RepoManager,
    private val builds: BuildService,
    private val db: GCodeDatabase,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val selector: ModelSelector,
    private val health: ModelHealth,
) : ExtraToolsProvider {

    override suspend fun tools(sessionId: String, workspace: TurnWorkspace): List<Tool> {
        val web = listOf(WebFetchTool(okHttp), WebSearchTool(transport) { searchModel() })
        if (account.state.value !is GitHubAuthState.SignedIn) return web
        val host = Host(workspace)
        // One guard per turn: a new user message allows a new round of fix attempts.
        val guard = BuildFixGuard { ref -> host.buildFixLimit(ref) }
        val deps = GitHubToolDeps(github, github, github, github, github, host, workspace.value?.guard ?: SecretGuard())
        return web + GitHubTools.create(deps, guard)
    }

    override suspend fun memory(workspace: ActiveWorkspace?): String? = null

    override suspend fun promptExtra(workspace: ActiveWorkspace?): String? =
        if (account.state.value is GitHubAuthState.SignedIn) GitHubTools.GUIDANCE else WEB_ONLY_GUIDANCE

    override suspend fun beforeModelCall(sessionId: String) = Unit

    /** The first usable model of the user's chain; Google Search grounding works on all current Gemini models. */
    private suspend fun searchModel(): String {
        val preferred = settings.current().preferredModel
        val now = System.currentTimeMillis()
        return selector.chain(models.current(), preferred).firstOrNull { health.isUsable(it, now) } ?: preferred
    }

    private inner class Host(private val turn: TurnWorkspace) : AgentHost {
        private fun entity() = turn.value?.repo ?: throw IllegalStateException("Open a cloned GitHub repository first")

        override suspend fun activeRepo(): RepoRef? = turn.value?.repo?.let { RepoRef.parse(it.fullName) }

        override suspend fun currentBranch(): String? = turn.value?.git?.let { git -> runCatching { git.withRepo { it.currentBranch() } }.getOrNull() }

        override suspend fun cloneRepo(fullName: String, singleBranch: Boolean, progress: suspend (String) -> Unit): String {
            val ref = RepoRef.parse(fullName) ?: error("Invalid repository $fullName")
            val repo = github.repo(ref)
            if (repos.isCloned(ref)) {
                repos.open(repo.fullName)
            } else {
                repos.clone(repo, singleBranch)
                val state = repos.clones.map { it[repo.fullName] }.filterNotNull().first { s ->
                    s.progress?.let { p -> progress(if (p.total > 0) "${p.task} ${p.done * 100 / p.total}%" else p.task) }
                    s.done || s.error != null
                }
                repos.clearCloneState(repo.fullName)
                state.error?.let { error(it) }
            }
            turn.refresh()
            return "Cloned ${repo.fullName} and opened it as the workspace (default branch ${repo.defaultBranch})."
        }

        override suspend fun setUpBuildWorkflow(): String {
            val repo = entity()
            if (builds.hasGCodeWorkflow(repo)) return "${WorkflowTemplate.PATH} already exists."
            builds.setUpBuilds(repo)
            turn.refresh()
            return "Committed and pushed ${WorkflowTemplate.PATH}. The push starts its first run."
        }

        override suspend fun startBuild(workflow: String?, inputs: Map<String, String>): StartedRun {
            val repo = entity()
            val ref = RepoRef.parse(repo.fullName) ?: error("Invalid repository")
            val workflows = builds.workflows(ref)
            val wanted = workflow ?: repo.buildWorkflow ?: WorkflowTemplate.FILE_NAME
            val chosen = workflows.firstOrNull { it.fileName == wanted || it.name == wanted || it.path == wanted }
                ?: workflows.singleOrNull().takeIf { workflow == null }
                ?: error(
                    if (workflows.isEmpty()) "${repo.fullName} has no GitHub Actions workflows. Use gh_setup_build_workflow for an Android app."
                    else "No workflow '$wanted'. Available: ${workflows.joinToString { it.fileName }}",
                )
            val run = builds.build(repo, chosen, commitMessage = null, inputs = inputs)
            return StartedRun(run.id, chosen.fileName, run.htmlUrl)
        }

        override suspend fun downloadApk(ref: RepoRef, runId: Long, artifact: String?): DownloadedApk {
            val artifacts = builds.artifacts(ref, runId)
            val chosen = (if (artifact != null) artifacts.firstOrNull { it.name == artifact } else null)
                ?: artifacts.filter { !it.expired }.let { live -> live.firstOrNull { it.name.contains("apk", ignoreCase = true) } ?: live.firstOrNull() }
                ?: error(if (artifacts.isEmpty()) "Run $runId has no artifacts (yet)." else "All artifacts of run $runId have expired.")
            val file = builds.downloadApk(ref, runId, chosen) { _, _ -> }
            return DownloadedApk(file.absolutePath, ApkInstaller.describe(context, file), file.length())
        }

        override suspend fun installApk(path: String): String {
            val file = File(path).canonicalFile
            val allowed = File(context.filesDir, "builds").canonicalFile
            require(file.path.startsWith(allowed.path + File.separator) && file.isFile) { "Only APKs downloaded with gh_download_apk can be installed." }
            if (!ApkInstaller.canInstall(context)) {
                return "GCode may not install apps yet. Ask the user to allow \"Install unknown apps\" for GCode in Android settings " +
                    "(the Builds screen has a shortcut), then try again."
            }
            ApkInstaller.install(context, file)
            val result = withTimeoutOrNull(INSTALL_WAIT_MS) { ApkInstaller.results.filterNotNull().first() }
            return result?.message ?: "Android is asking the user to confirm the installation."
        }

        override suspend fun buildFixLimit(ref: RepoRef): Int = db.localRepos().get(ref.fullName)?.autoFixLimit ?: DEFAULT_FIX_LIMIT
    }

    companion object {
        const val DEFAULT_FIX_LIMIT = 3
        private const val INSTALL_WAIT_MS = 120_000L
        private val WEB_ONLY_GUIDANCE = """
            # Web and GitHub
            - Check current library versions, APIs and error messages with web_search or web_fetch instead of guessing.
            - GitHub tools (repositories, pull requests, builds) need the user to sign in to GitHub in the Repos tab.
        """.trimIndent()
    }
}
