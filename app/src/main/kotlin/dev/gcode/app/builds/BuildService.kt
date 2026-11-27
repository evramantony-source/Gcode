package dev.gcode.app.builds

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.app.workspace.WorkspaceGit
import dev.gcode.core.data.db.BuildEntity
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.model.RepoRef
import dev.gcode.github.actions.AndroidProjectDetector
import dev.gcode.github.actions.LogParser
import dev.gcode.github.actions.StepLog
import dev.gcode.github.actions.WorkflowTemplate
import dev.gcode.github.api.ActionsApi
import dev.gcode.github.http.GitHubApiException
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhWorkflow
import dev.gcode.github.model.GhWorkflowRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** The artifact passed its retention period (HTTP 410 or `expired`). Start a new build to get a fresh APK. */
class ArtifactExpiredException(name: String) : RuntimeException("The artifact $name has expired. Start a new build to get a fresh APK.")

/** Progress of a "Build" action, shown on the Builds screen. */
sealed interface BuildStep {
    data object Committing : BuildStep
    data object Pushing : BuildStep
    data object Starting : BuildStep
    data class Started(val runId: Long) : BuildStep
}

/**
 * Remote build pipeline (ARCHITECTURE.md section 8.4): workflow setup, start (push and/or dispatch),
 * status, logs, artifacts, APK extraction and history. Nothing is compiled on the device.
 */
@Singleton
class BuildService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: ActionsApi,
    private val git: WorkspaceGit,
    private val db: GCodeDatabase,
) {
    private val builds = db.builds()

    fun history(repo: String): Flow<List<BuildEntity>> = builds.observeForRepo(repo)
    fun observe(repo: String, runId: Long): Flow<BuildEntity?> = builds.observe(repo, runId)

    suspend fun workflows(repo: RepoRef): List<GhWorkflow> = actions.workflows(repo).filter { it.state == "active" }

    /** Reads the working tree to decide whether "Set up builds" applies. */
    suspend fun isAndroidProject(repo: LocalRepoEntity): Boolean = withContext(Dispatchers.IO) {
        val root = File(repo.path)
        val files = root.walkTopDown().maxDepth(3).onEnter { it.name != ".git" && it.name != "build" }
            .filter { it.isFile && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name == "libs.versions.toml") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        AndroidProjectDetector.isAndroidApp(files)
    }

    fun hasGCodeWorkflow(repo: LocalRepoEntity): Boolean = File(repo.path, WorkflowTemplate.PATH).isFile

    fun localWorkflowYaml(repo: LocalRepoEntity, workflow: GhWorkflow): String? =
        File(repo.path, workflow.path).takeIf { it.isFile }?.readText()

    /** Adds .github/workflows/gcode-build.yml, commits and pushes it. */
    suspend fun setUpBuilds(repo: LocalRepoEntity) {
        val file = File(repo.path, WorkflowTemplate.PATH)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(WorkflowTemplate.android())
        }
        val creds = git.credentials()
        val author = git.author(repo)
        git.withRepo(repo) { r, _ ->
            r.add(listOf(WorkflowTemplate.PATH))
            r.commit("Add GCode build workflow", author)
            val push = r.push(creds)
            if (!push.ok) error(push.message ?: "Push failed")
        }
        db.localRepos().upsert(repo.copy(buildWorkflow = WorkflowTemplate.FILE_NAME))
    }

    /**
     * Commits pending changes (when [commitMessage] is given), pushes, then finds the run the push started
     * or dispatches [workflow]. Records the run and starts background monitoring.
     */
    suspend fun build(repo: LocalRepoEntity, workflow: GhWorkflow, commitMessage: String?, onStep: (BuildStep) -> Unit): Long {
        val ref = RepoRef.parse(repo.fullName) ?: error("Invalid repository ${repo.fullName}")
        val creds = git.credentials()
        val author = git.author(repo)
        val (branch, sha) = git.withRepo(repo) { r, _ ->
            val status = r.status()
            if (!status.isClean && !commitMessage.isNullOrBlank()) {
                onStep(BuildStep.Committing)
                r.addAll()
                r.commit(commitMessage, author)
            }
            onStep(BuildStep.Pushing)
            val push = r.push(creds)
            if (!push.ok) error(push.message ?: "Push failed")
            (r.currentBranch() ?: repo.defaultBranch) to (r.headSha() ?: error("No commits to build"))
        }
        onStep(BuildStep.Starting)
        val yaml = localWorkflowYaml(repo, workflow).orEmpty()
        // A push-triggered run may already exist for this commit; don't start a duplicate.
        val runId = findRunForSha(ref, workflow, sha) ?: if (AndroidProjectDetector.supportsDispatch(yaml) || yaml.isEmpty()) {
            val dispatched = actions.dispatch(ref, workflow.fileName, branch)
            dispatched.workflowRunId.takeIf { it > 0 } ?: findRunForSha(ref, workflow, sha, attempts = 6) ?: error("The run did not appear. Check Actions on GitHub.")
        } else {
            findRunForSha(ref, workflow, sha, attempts = 10) ?: error("${workflow.name} does not run on push and has no workflow_dispatch trigger.")
        }
        record(ref, actions.run(ref, runId))
        onStep(BuildStep.Started(runId))
        return runId
    }

    /** Dispatches any workflow with inputs (non-Android projects too). */
    suspend fun dispatch(repo: LocalRepoEntity, workflow: GhWorkflow, branch: String, inputs: Map<String, String>): Long {
        val ref = RepoRef.parse(repo.fullName) ?: error("Invalid repository")
        val sha = git.withRepo(repo) { r, _ -> r.headSha() }
        val result = actions.dispatch(ref, workflow.fileName, branch, inputs)
        val runId = result.workflowRunId.takeIf { it > 0 } ?: sha?.let { findRunForSha(ref, workflow, it, attempts = 6) } ?: error("The run did not appear yet")
        record(ref, actions.run(ref, runId))
        return runId
    }

    private suspend fun findRunForSha(ref: RepoRef, workflow: GhWorkflow, sha: String, attempts: Int = 2): Long? {
        repeat(attempts) { i ->
            actions.runs(ref, workflow = workflow.id.toString(), headSha = sha, perPage = 5).firstOrNull()?.let { return it.id }
            if (i < attempts - 1) delay(3_000)
        }
        return null
    }

    suspend fun refresh(ref: RepoRef, runId: Long): Pair<GhWorkflowRun, List<GhJob>> {
        val run = actions.run(ref, runId)
        val jobs = actions.jobs(ref, runId)
        record(ref, run)
        return run to jobs
    }

    suspend fun recentRuns(ref: RepoRef, workflow: GhWorkflow?): List<GhWorkflowRun> =
        actions.runs(ref, workflow = workflow?.id?.toString(), perPage = 20).onEach { record(ref, it) }

    suspend fun record(ref: RepoRef, run: GhWorkflowRun) {
        val existing = builds.get(ref.fullName, run.id)
        val entity = BuildEntity(
            id = existing?.id ?: 0,
            repoFullName = ref.fullName,
            runId = run.id,
            runNumber = run.runNumber,
            workflowId = run.workflowId,
            workflowName = run.name,
            branch = run.headBranch,
            headSha = run.headSha,
            event = run.event,
            status = run.status,
            conclusion = run.conclusion,
            htmlUrl = run.htmlUrl,
            createdAt = run.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            artifactId = existing?.artifactId,
            apkPath = existing?.apkPath,
            apkLabel = existing?.apkLabel,
            notified = existing?.notified ?: false,
        )
        if (existing == null) builds.insert(entity) else builds.update(entity)
        if (!run.isCompleted) BuildMonitorWorker.schedule(context, ref.fullName, run.id)
    }

    suspend fun logs(ref: RepoRef, job: GhJob): List<StepLog> = LogParser.split(actions.jobLog(ref, job.id), job)

    suspend fun artifacts(ref: RepoRef, runId: Long): List<GhArtifact> = actions.artifacts(ref, runId)

    suspend fun cancel(ref: RepoRef, runId: Long) = actions.cancelRun(ref, runId)
    suspend fun rerunFailed(ref: RepoRef, runId: Long) {
        actions.rerunFailedJobs(ref, runId)
        BuildMonitorWorker.schedule(context, ref.fullName, runId)
    }

    /** Downloads an artifact zip and extracts the APK into app storage (ARCHITECTURE.md section 8.4). */
    suspend fun downloadApk(ref: RepoRef, runId: Long, artifact: GhArtifact, progress: (Long, Long) -> Unit): File {
        val dir = File(context.filesDir, "builds/${ref.owner}/${ref.name}/$runId").apply { mkdirs() }
        val zip = File(dir, "artifact-${artifact.id}.zip")
        if (artifact.expired) throw ArtifactExpiredException(artifact.name)
        try {
            actions.downloadArtifact(ref, artifact.id, zip, progress)
        } catch (e: GitHubApiException) {
            zip.delete()
            // 410 Gone: the artifact passed its retention period.
            if (e.status == 410) throw ArtifactExpiredException(artifact.name)
            throw e
        }
        val apk = withContext(Dispatchers.IO) { extractApk(zip, dir).also { zip.delete() } } ?: error("The artifact ${artifact.name} contains no .apk file")
        val label = ApkInstaller.describe(context, apk)
        builds.get(ref.fullName, runId)?.let { builds.update(it.copy(artifactId = artifact.id, apkPath = apk.absolutePath, apkLabel = label)) }
        return apk
    }

    companion object {
        /** Extracts the first .apk entry (zip-slip safe). */
        fun extractApk(zip: File, dir: File): File? {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".apk")) continue
                    val out = File(dir, File(entry.name).name)
                    if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                    out.outputStream().use { input.copyTo(it) }
                    return out
                }
            }
            return null
        }
    }
}
