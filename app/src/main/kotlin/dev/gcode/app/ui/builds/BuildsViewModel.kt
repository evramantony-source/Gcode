package dev.gcode.app.ui.builds

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.app.builds.ApkInstaller
import dev.gcode.app.builds.BuildLink
import dev.gcode.app.builds.BuildLinks
import dev.gcode.app.builds.BuildService
import dev.gcode.app.builds.BuildStep
import dev.gcode.app.github.GitHubAccountManager
import dev.gcode.app.github.GitHubAuthState
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.core.data.db.BuildEntity
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.model.RepoRef
import dev.gcode.github.actions.AndroidProjectDetector
import dev.gcode.github.actions.StepLog
import dev.gcode.github.api.SecretsApi
import dev.gcode.github.crypto.DebugKeystore
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhWorkflow
import dev.gcode.github.model.GhWorkflowRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.inject.Inject

/** The run open on the Builds screen. */
data class RunDetail(
    val repo: String,
    val runId: Long,
    val run: GhWorkflowRun? = null,
    val jobs: List<GhJob> = emptyList(),
    val openJob: Long? = null,
    val logs: Map<Long, List<StepLog>> = emptyMap(),
    val loadingLogs: Set<Long> = emptySet(),
    val artifacts: List<GhArtifact> = emptyList(),
    /** Artifact being downloaded, and progress 0..1 (negative when the size is unknown). */
    val downloading: Pair<Long, Float>? = null,
    val apk: File? = null,
    val apkLabel: String? = null,
)

/** Actions that are always confirmed (ARCHITECTURE.md section 10). */
sealed interface BuildConfirm {
    data class Install(val apk: File, val label: String, val runNumber: Int, val repo: String) : BuildConfirm
    data class PutSecret(val name: String, val value: String) : BuildConfirm
    data object DebugKey : BuildConfirm
    data object SetUp : BuildConfirm
}

data class BuildsUiState(
    val repo: LocalRepoEntity? = null,
    val signedIn: Boolean = false,
    val workflows: List<GhWorkflow> = emptyList(),
    val selectedWorkflowId: Long? = null,
    val canSetUp: Boolean = false,
    val dirty: Boolean = false,
    val branch: String? = null,
    val supportsDispatch: Boolean = true,
    val dispatchInputs: List<String> = emptyList(),
    val history: List<BuildEntity> = emptyList(),
    val step: BuildStep? = null,
    val loading: Boolean = false,
    val detail: RunDetail? = null,
    val secretNames: List<String>? = null,
    val showSecrets: Boolean = false,
    val confirm: BuildConfirm? = null,
    val needsUnknownSources: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val selectedWorkflow: GhWorkflow? get() = workflows.firstOrNull { it.id == selectedWorkflowId } ?: workflows.firstOrNull()
    val busy: Boolean get() = step != null && step !is BuildStep.Started
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BuildsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val builds: BuildService,
    private val git: WorkspaceGit,
    private val secretsApi: SecretsApi,
    private val secretStore: SecretStore,
    account: GitHubAccountManager,
) : ViewModel() {
    private val _state = MutableStateFlow(BuildsUiState())
    val state: StateFlow<BuildsUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            account.state.map { it is GitHubAuthState.SignedIn }.distinctUntilChanged().collect { signedIn ->
                _state.update { it.copy(signedIn = signedIn) }
                if (signedIn) loadRepo()
            }
        }
        viewModelScope.launch {
            git.activeRepo.distinctUntilChanged { a, b -> a?.fullName == b?.fullName && a?.buildWorkflow == b?.buildWorkflow }.collect { repo ->
                _state.update { it.copy(repo = repo, workflows = emptyList(), secretNames = null) }
                loadRepo()
            }
        }
        viewModelScope.launch {
            git.activeRepo.map { it?.fullName }.distinctUntilChanged()
                .flatMapLatest { name -> if (name == null) emptyFlow() else builds.history(name) }
                .collect { history -> _state.update { it.copy(history = history) } }
        }
        viewModelScope.launch {
            BuildLinks.pending.collect { link -> if (link != null) openLink(link) }
        }
        viewModelScope.launch {
            ApkInstaller.results.collect { result ->
                if (result != null) _state.update { if (result.success) it.copy(message = context.getString(dev.gcode.app.R.string.install_done)) else it.copy(error = result.message) }
            }
        }
    }

    private fun openLink(link: BuildLink) {
        BuildLinks.consume(link)
        openRun(link.repo, link.runId)
    }

    /** Workflows, project type and working-tree state for the open repository. */
    fun loadRepo() = viewModelScope.launch {
        val repo = _state.value.repo ?: return@launch
        val ref = RepoRef.parse(repo.fullName) ?: return@launch
        _state.update { it.copy(loading = true) }
        try {
            val (dirty, branch) = git.withRepo(repo) { r, _ -> !r.status().isClean to r.currentBranch() }
            val android = builds.isAndroidProject(repo)
            _state.update { it.copy(dirty = dirty, branch = branch, canSetUp = android && !builds.hasGCodeWorkflow(repo)) }
            if (_state.value.signedIn) {
                val workflows = builds.workflows(ref)
                val preferred = workflows.firstOrNull { it.fileName == repo.buildWorkflow } ?: workflows.firstOrNull { it.fileName == "gcode-build.yml" }
                _state.update { s ->
                    val selected = s.selectedWorkflowId?.takeIf { id -> workflows.any { it.id == id } } ?: preferred?.id ?: workflows.firstOrNull()?.id
                    s.copy(workflows = workflows, selectedWorkflowId = selected)
                }
                updateInputs()
                builds.recentRuns(ref, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    fun selectWorkflow(id: Long) {
        _state.update { it.copy(selectedWorkflowId = id) }
        updateInputs()
    }

    private fun updateInputs() {
        val s = _state.value
        val repo = s.repo ?: return
        val workflow = s.selectedWorkflow ?: return
        val yaml = builds.localWorkflowYaml(repo, workflow)
        _state.update {
            it.copy(
                supportsDispatch = yaml == null || AndroidProjectDetector.supportsDispatch(yaml),
                dispatchInputs = yaml?.let(AndroidProjectDetector::dispatchInputs).orEmpty(),
            )
        }
    }

    fun requestSetUp() = _state.update { it.copy(confirm = BuildConfirm.SetUp) }

    private fun setUp() = launchCatching {
        val repo = _state.value.repo ?: return@launchCatching
        _state.update { it.copy(step = BuildStep.Pushing) }
        try {
            builds.setUpBuilds(repo)
        } finally {
            _state.update { it.copy(step = null) }
        }
        loadRepo().join()
        // Pushing the workflow file starts its first run (push trigger); follow it.
        val ref = RepoRef.parse(repo.fullName) ?: return@launchCatching
        val workflow = _state.value.selectedWorkflow ?: return@launchCatching
        builds.recentRuns(ref, workflow).firstOrNull()?.let { openRun(repo.fullName, it.id) }
    }

    /** Commit (if needed) → push → find or dispatch the run → follow it. */
    fun build(commitMessage: String?, inputs: Map<String, String>) = launchCatching {
        val s = _state.value
        val repo = s.repo ?: return@launchCatching
        val workflow = s.selectedWorkflow ?: return@launchCatching
        try {
            val run = builds.build(repo, workflow, commitMessage, inputs) { step -> _state.update { it.copy(step = step) } }
            _state.update { it.copy(dirty = false) }
            openRun(repo.fullName, run.id)
        } finally {
            _state.update { it.copy(step = null) }
        }
    }

    fun refreshHistory() = launchCatching {
        val repo = _state.value.repo ?: return@launchCatching
        val ref = RepoRef.parse(repo.fullName) ?: return@launchCatching
        builds.recentRuns(ref, null)
    }

    // ---- Run detail ---------------------------------------------------------------------------

    fun openRun(repo: String, runId: Long) {
        _state.update { it.copy(detail = RunDetail(repo, runId)) }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            launch {
                builds.observe(repo, runId).collectLatest { entity ->
                    updateDetail { d ->
                        if (d.runId != runId) d else d.copy(apk = entity?.apkPath?.let(::File)?.takeIf { it.isFile }, apkLabel = entity?.apkLabel)
                    }
                }
            }
            poll(repo, runId)
        }
    }

    /** Live status: every 5 s while the run is active (requests use ETags, so unchanged polls are cheap). */
    private suspend fun poll(repo: String, runId: Long) {
        val ref = RepoRef.parse(repo) ?: return
        var loadedArtifacts = false
        while (viewModelScope.isActive && _state.value.detail?.runId == runId) {
            try {
                val (run, jobs) = builds.refresh(ref, runId)
                _state.update { s -> s.detail?.takeIf { it.runId == runId }?.let { s.copy(detail = it.copy(run = run, jobs = jobs)) } ?: s }
                if (run.isCompleted || !loadedArtifacts) {
                    val artifacts = builds.artifacts(ref, runId)
                    loadedArtifacts = true
                    _state.update { s -> s.detail?.takeIf { it.runId == runId }?.let { s.copy(detail = it.copy(artifacts = artifacts)) } ?: s }
                }
                if (run.isCompleted) {
                    // Show the failing job's logs straight away.
                    jobs.firstOrNull { it.conclusion == "failure" }?.let { if (_state.value.detail?.openJob == null) toggleJob(it.id) }
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
                return
            }
            delay(POLL_MS)
        }
    }

    fun closeRun() {
        pollJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    fun toggleJob(jobId: Long) {
        val d = _state.value.detail ?: return
        if (d.openJob == jobId) {
            _state.update { it.copy(detail = d.copy(openJob = null)) }
            return
        }
        _state.update { it.copy(detail = d.copy(openJob = jobId)) }
        val job = d.jobs.firstOrNull { it.id == jobId } ?: return
        if (d.logs.containsKey(jobId) || job.status != "completed") return
        launchCatching {
            val ref = RepoRef.parse(d.repo) ?: return@launchCatching
            updateDetail { it.copy(loadingLogs = it.loadingLogs + jobId) }
            try {
                val logs = builds.logs(ref, job)
                updateDetail { it.copy(logs = it.logs + (jobId to logs)) }
            } finally {
                updateDetail { it.copy(loadingLogs = it.loadingLogs - jobId) }
            }
        }
    }

    fun cancelRun() = launchCatching {
        val d = _state.value.detail ?: return@launchCatching
        RepoRef.parse(d.repo)?.let { builds.cancel(it, d.runId) }
    }

    fun rerunFailed() = launchCatching {
        val d = _state.value.detail ?: return@launchCatching
        val ref = RepoRef.parse(d.repo) ?: return@launchCatching
        builds.rerunFailed(ref, d.runId)
        _state.update { s -> s.copy(detail = d.copy(logs = emptyMap(), openJob = null)) }
        openRun(d.repo, d.runId)
    }

    fun download(artifact: GhArtifact) = launchCatching {
        val d = _state.value.detail ?: return@launchCatching
        val ref = RepoRef.parse(d.repo) ?: return@launchCatching
        updateDetail { it.copy(downloading = artifact.id to 0f) }
        try {
            builds.downloadApk(ref, d.runId, artifact) { done, total ->
                val fraction = if (total > 0) done.toFloat() / total else -1f
                updateDetail { it.copy(downloading = artifact.id to fraction) }
            }
        } finally {
            updateDetail { it.copy(downloading = null) }
        }
    }

    fun requestInstall() {
        val d = _state.value.detail ?: return
        val apk = d.apk ?: return
        _state.update { it.copy(confirm = BuildConfirm.Install(apk, d.apkLabel ?: apk.name, d.run?.runNumber ?: 0, d.repo)) }
    }

    private fun install(apk: File) = launchCatching {
        if (!ApkInstaller.canInstall(context)) {
            _state.update { it.copy(needsUnknownSources = true) }
            return@launchCatching
        }
        ApkInstaller.install(context, apk)
    }

    fun dismissUnknownSources() = _state.update { it.copy(needsUnknownSources = false) }

    // ---- Secrets ------------------------------------------------------------------------------

    fun showSecrets(show: Boolean) {
        _state.update { it.copy(showSecrets = show) }
        if (show) loadSecrets()
    }

    private fun loadSecrets() = launchCatching {
        val ref = _state.value.repo?.let { RepoRef.parse(it.fullName) } ?: return@launchCatching
        val names = secretsApi.secretNames(ref).map { it.name }
        _state.update { it.copy(secretNames = names) }
    }

    fun requestPutSecret(name: String, value: String) {
        if (!SECRET_NAME.matches(name)) {
            _state.update { it.copy(error = context.getString(dev.gcode.app.R.string.secrets_invalid_name)) }
            return
        }
        _state.update { it.copy(confirm = BuildConfirm.PutSecret(name, value)) }
    }

    fun requestDebugKey() = _state.update { it.copy(confirm = BuildConfirm.DebugKey) }

    private fun putSecret(name: String, value: String) = launchCatching {
        val ref = _state.value.repo?.let { RepoRef.parse(it.fullName) } ?: return@launchCatching
        secretsApi.putSecret(ref, name, value)
        _state.update { it.copy(message = context.getString(dev.gcode.app.R.string.secrets_saved, name)) }
        loadSecrets()
    }

    /** One debug key per device, kept encrypted in the SecretStore and reused for every repository. */
    private fun saveDebugKey() = launchCatching {
        val encoded = secretStore.get(SecretKey.DEBUG_KEYSTORE) ?: withContext(Dispatchers.Default) {
            Base64.getEncoder().encodeToString(DebugKeystore.create())
        }.also { secretStore.put(SecretKey.DEBUG_KEYSTORE, it) }
        putSecret(DEBUG_KEYSTORE_SECRET, encoded).join()
    }

    // ---- Confirmation -------------------------------------------------------------------------

    fun confirm() {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        when (c) {
            is BuildConfirm.Install -> install(c.apk)
            is BuildConfirm.PutSecret -> putSecret(c.name, c.value)
            BuildConfirm.DebugKey -> saveDebugKey()
            BuildConfirm.SetUp -> setUp()
        }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }
    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    private fun updateDetail(transform: (RunDetail) -> RunDetail) = _state.update { s -> s.detail?.let { s.copy(detail = transform(it)) } ?: s }

    private fun launchCatching(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    companion object {
        const val POLL_MS = 5_000L
        const val DEBUG_KEYSTORE_SECRET = "DEBUG_KEYSTORE_BASE64"
        private val SECRET_NAME = Regex("^(?!GITHUB_)(?![0-9])[A-Za-z0-9_]+$")
    }
}
