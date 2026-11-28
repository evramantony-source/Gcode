package dev.gcode.git

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Local Git operations over JGit (ARCHITECTURE.md section 8.3). All calls block; run them on an IO dispatcher.
 * JGit 5.13 is used because its Java 8 API surface runs on every Android version from API 26 (see DECISIONS.md).
 */
class JGitRepository private constructor(private val git: Git) : AutoCloseable {
    val repository: Repository get() = git.repository
    val directory: File get() = repository.workTree

    override fun close() = git.close()

    // ---------------------------------------------------------------- status, diff

    fun currentBranch(): String? = repository.branch

    fun headSha(): String? = repository.resolve(Constants.HEAD)?.name

    fun remoteUrl(remote: String = "origin"): String? = repository.config.getString("remote", remote, "url")

    /** Adds (or updates) a remote with the standard fetch refspec. */
    fun addRemote(name: String, url: String) {
        val config = repository.config
        config.setString("remote", name, "url", url)
        config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
        config.save()
    }

    fun status(): GitStatus {
        val s = git.status().call()
        val staged = s.added.map { FileChange(it, ChangeKind.ADDED) } + s.changed.map { FileChange(it, ChangeKind.MODIFIED) } +
            s.removed.map { FileChange(it, ChangeKind.DELETED) }
        val unstaged = s.modified.map { FileChange(it, ChangeKind.MODIFIED) } + s.missing.map { FileChange(it, ChangeKind.DELETED) }
        val branch = repository.branch
        val tracking = branch?.let { runCatching { BranchTrackingStatus.of(repository, it) }.getOrNull() }
        return GitStatus(
            branch = branch,
            headSha = headSha(),
            staged = staged.sortedBy { it.path },
            unstaged = unstaged.sortedBy { it.path },
            untracked = s.untracked.sorted(),
            conflicting = s.conflicting.sorted(),
            ahead = tracking?.aheadCount ?: 0,
            behind = tracking?.behindCount ?: 0,
            upstream = tracking?.remoteTrackingBranch?.let(Repository::shortenRefName),
        )
    }

    /** Unified diff of the working tree against the index, or of the index against HEAD when [staged]. */
    fun diff(staged: Boolean = false, paths: List<String> = emptyList(), contextLines: Int = 3): String {
        val out = ByteArrayOutputStream()
        git.diff().setCached(staged).setContextLines(contextLines).setOutputStream(out)
            .apply { if (paths.isNotEmpty()) setPathFilter(PathFilterGroup.createFromStrings(paths)) }
            .call()
        return out.toString(Charsets.UTF_8.name())
    }

    /** Diff introduced by a commit (against its first parent, or the empty tree for a root commit). */
    fun commitDiff(sha: String): String {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(repository.resolve(sha) ?: throw GitOperationException("Unknown commit $sha"))
            val newTree = treeParser(commit)
            val oldTree: AbstractTreeIterator = if (commit.parentCount > 0) treeParser(walk.parseCommit(commit.getParent(0))) else EmptyTreeIterator()
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { f ->
                f.setRepository(repository)
                f.setDiffComparator(RawTextComparator.DEFAULT)
                f.isDetectRenames = true
                f.format(oldTree, newTree)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun treeParser(commit: RevCommit): CanonicalTreeParser = repository.newObjectReader().use { reader ->
        CanonicalTreeParser().apply { reset(reader, commit.tree) }
    }

    // ---------------------------------------------------------------- staging, commits

    fun add(paths: List<String>) {
        if (paths.isEmpty()) return
        git.add().apply { paths.forEach { addFilepattern(it) } }.call()
        // Stage deletions too.
        git.add().setUpdate(true).apply { paths.forEach { addFilepattern(it) } }.call()
    }

    fun addAll() = add(listOf("."))

    fun unstage(paths: List<String>) {
        if (paths.isEmpty()) return
        if (headSha() == null) {
            git.rm().setCached(true).apply { paths.forEach { addFilepattern(it) } }.call()
        } else {
            git.reset().apply { paths.forEach { addPath(it) } }.call()
        }
    }

    /** Discards working-tree changes to tracked files (restores them from the index). */
    fun discard(paths: List<String>) {
        if (paths.isNotEmpty()) git.checkout().apply { paths.forEach { addPath(it) } }.call()
    }

    fun commit(message: String, author: Author, all: Boolean = false): CommitInfo {
        require(message.isNotBlank()) { "Commit message is empty" }
        val commit = git.commit().setMessage(message.trim()).setAll(all)
            .setAuthor(author.name, author.email).setCommitter(author.name, author.email).call()
        return commit.toInfo()
    }

    fun log(maxCount: Int = 50, path: String? = null, ref: String? = null): List<CommitInfo> {
        if (headSha() == null) return emptyList()
        return git.log().setMaxCount(maxCount)
            .apply {
                path?.let { addPath(it) }
                ref?.let { add(repository.resolve(it) ?: throw GitOperationException("Unknown ref $it")) }
            }
            .call().map { it.toInfo() }
    }

    // ---------------------------------------------------------------- branches

    fun branches(includeRemote: Boolean = true): List<BranchInfo> {
        val current = repository.fullBranch
        val mode = if (includeRemote) ListBranchCommand.ListMode.ALL else null
        return git.branchList().apply { mode?.let { setListMode(it) } }.call().map { ref ->
            val name = Repository.shortenRefName(ref.name)
            val remote = ref.name.startsWith(Constants.R_REMOTES)
            val upstream = if (!remote) runCatching { BranchTrackingStatus.of(repository, name)?.remoteTrackingBranch }.getOrNull() else null
            BranchInfo(name, remote, ref.name == current, ref.objectId.name, upstream?.let(Repository::shortenRefName))
        }.filterNot { it.name.endsWith("/HEAD") }
    }

    fun createBranch(name: String, startPoint: String? = null, checkout: Boolean = true) {
        git.branchCreate().setName(name).apply { startPoint?.let { setStartPoint(it) } }.call()
        if (checkout) checkout(name)
    }

    /** Switches branch. A remote branch (e.g. origin/feature) gets a local tracking branch. */
    fun checkout(name: String) {
        val local = repository.findRef(Constants.R_HEADS + name)
        if (local != null) {
            git.checkout().setName(name).call()
            return
        }
        val remoteRef = repository.findRef(Constants.R_REMOTES + name) ?: repository.findRef(Constants.R_REMOTES + "origin/" + name)
            ?: throw GitOperationException("No branch named $name")
        val localName = Repository.shortenRefName(remoteRef.name).substringAfter('/')
        git.checkout().setCreateBranch(true).setName(localName).setStartPoint(remoteRef.name)
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
    }

    fun deleteBranch(name: String, force: Boolean = false) {
        if (name == repository.branch) throw GitOperationException("Cannot delete the checked-out branch $name")
        git.branchDelete().setBranchNames(name).setForce(force).call()
    }

    fun merge(name: String, message: String? = null): MergeOutcome {
        // Including the ref (not just its id) gives the usual "Merge branch 'x'" message.
        val ref = repository.findRef(name)
        val id = repository.resolve(name) ?: throw GitOperationException("Unknown branch $name")
        val result = git.merge().apply { if (ref != null) include(ref) else include(id) }.setCommit(true).apply { message?.let { setMessage(it) } }.call()
        return when (result.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> MergeOutcome.AlreadyUpToDate
            MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED -> MergeOutcome.Merged(result.newHead?.name, true)
            MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_SQUASHED, MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
            MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED -> MergeOutcome.Merged(result.newHead?.name, false)
            MergeResult.MergeStatus.CONFLICTING -> MergeOutcome.Conflicts(result.conflicts?.keys?.sorted().orEmpty())
            MergeResult.MergeStatus.FAILED -> MergeOutcome.Failed("Merge failed", result.failingPaths?.keys?.sorted().orEmpty())
            else -> MergeOutcome.Failed(result.mergeStatus.toString())
        }
    }

    /** True while a merge is in progress (conflicts not yet committed). */
    fun isMerging(): Boolean = repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.MERGING ||
        repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED

    /** Paths that still have conflict stages in the index. */
    fun conflicts(): List<String> = git.status().call().conflicting.sorted()

    /**
     * Resolves a conflicted file by taking one side entirely (binary files, delete/modify conflicts).
     * If that side deleted the file, the file is removed. The result is staged.
     */
    fun takeSide(path: String, ours: Boolean) {
        val stage = if (ours) org.eclipse.jgit.dircache.DirCacheEntry.STAGE_2 else org.eclipse.jgit.dircache.DirCacheEntry.STAGE_3
        val cache = repository.readDirCache()
        val exists = (0 until cache.entryCount).any { cache.getEntry(it).let { e -> e.pathString == path && e.stage == stage } }
        if (exists) {
            git.checkout().addPath(path).setStage(if (ours) org.eclipse.jgit.api.CheckoutCommand.Stage.OURS else org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS).call()
            git.add().addFilepattern(path).call()
        } else {
            java.io.File(repository.workTree, path).delete()
            git.rm().addFilepattern(path).call()
        }
    }

    /** The prepared merge commit message (MERGE_MSG), if a merge is in progress. */
    fun mergeMessage(): String? = repository.readMergeCommitMsg()?.lines()?.filterNot { it.startsWith("#") }?.joinToString("\n")?.trim()?.ifEmpty { null }

    fun abortMerge() {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        repository.writeMergeHeads(null)
        repository.writeMergeCommitMsg(null)
    }

    // ---------------------------------------------------------------- remotes

    fun fetch(credentials: GitCredentials?, remote: String = "origin", monitor: ProgressMonitor? = null): List<String> {
        val result = git.fetch().setRemote(remote).setRemoveDeletedRefs(true).setCredentialsProvider(credentials?.provider())
            .apply { monitor?.let { setProgressMonitor(it) } }.call()
        return result.trackingRefUpdates.map { "${Repository.shortenRefName(it.localName)}: ${it.result}" }
    }

    fun pull(credentials: GitCredentials?, rebase: Boolean = false, monitor: ProgressMonitor? = null): MergeOutcome {
        val result = git.pull().setRebase(rebase).setCredentialsProvider(credentials?.provider())
            .apply { monitor?.let { setProgressMonitor(it) } }.call()
        result.mergeResult?.let { mr ->
            if (mr.mergeStatus == MergeResult.MergeStatus.CONFLICTING) return MergeOutcome.Conflicts(mr.conflicts?.keys?.sorted().orEmpty())
            if (!mr.mergeStatus.isSuccessful) return MergeOutcome.Failed(mr.mergeStatus.toString(), mr.failingPaths?.keys?.sorted().orEmpty())
            return if (mr.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) MergeOutcome.AlreadyUpToDate else MergeOutcome.Merged(mr.newHead?.name, mr.mergeStatus == MergeResult.MergeStatus.FAST_FORWARD)
        }
        result.rebaseResult?.let { rr ->
            if (!rr.status.isSuccessful) return MergeOutcome.Conflicts(rr.conflicts.orEmpty().sorted())
            return if (rr.status == org.eclipse.jgit.api.RebaseResult.Status.UP_TO_DATE) MergeOutcome.AlreadyUpToDate else MergeOutcome.Merged(headSha(), false)
        }
        return if (result.isSuccessful) MergeOutcome.AlreadyUpToDate else MergeOutcome.Failed("Pull failed")
    }

    /**
     * Pushes [branch] (default: current) to the same name on [remote]. With [force], uses
     * force-with-lease semantics: the push is rejected if the remote moved since the last fetch.
     */
    fun push(credentials: GitCredentials?, branch: String? = null, remote: String = "origin", force: Boolean = false, monitor: ProgressMonitor? = null): PushOutcome {
        val name = branch ?: repository.branch ?: throw GitOperationException("Not on a branch")
        val ref = Constants.R_HEADS + name
        val command = git.push().setRemote(remote).setCredentialsProvider(credentials?.provider()).setRefSpecs(RefSpec("$ref:$ref"))
            .apply { monitor?.let { setProgressMonitor(it) } }
        if (force) {
            val expected = repository.findRef("${Constants.R_REMOTES}$remote/$name")?.objectId?.name ?: ObjectId.zeroId().name
            command.setForce(true).setRefLeaseSpecs(RefLeaseSpec(ref, expected))
        }
        val updates = command.call().flatMap { it.remoteUpdates }
        val ok = updates.all { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }
        if (ok) ensureUpstream(name, remote)
        val lines = updates.map { u -> "${Repository.shortenRefName(u.remoteName)}: ${u.status}${u.message?.let { " ($it)" } ?: ""}" }
        val message = updates.firstOrNull { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }?.let {
            when (it.status) {
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> "Rejected: the remote has commits you do not have. Pull first."
                RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> "Rejected: the remote branch changed since your last fetch."
                else -> it.message ?: it.status.toString()
            }
        }
        return PushOutcome(ok, lines, message)
    }

    private fun ensureUpstream(branch: String, remote: String) {
        val config = repository.config
        if (config.getString("branch", branch, "remote") == null) {
            config.setString("branch", branch, "remote", remote)
            config.setString("branch", branch, "merge", Constants.R_HEADS + branch)
            config.save()
        }
    }

    private fun RevCommit.toInfo() = CommitInfo(
        sha = name,
        shortSha = name.take(7),
        message = fullMessage,
        authorName = authorIdent.name,
        authorEmail = authorIdent.emailAddress,
        timeEpochSeconds = commitTime.toLong(),
        parents = parents.map { it.name },
    )

    companion object {
        fun open(dir: File): JGitRepository = try {
            JGitRepository(Git.open(dir))
        } catch (e: Exception) {
            throw GitOperationException("Not a Git repository: ${dir.name}", e)
        }

        fun init(dir: File, initialBranch: String = "main"): JGitRepository {
            dir.mkdirs()
            val git = Git.init().setDirectory(dir).call()
            if (git.repository.branch != initialBranch) {
                git.repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + initialBranch)
            }
            return JGitRepository(git)
        }

        /**
         * Clones [url] into [dir]. With [singleBranch], only that branch is fetched (smaller downloads on a phone).
         * A failed clone leaves no partial directory behind.
         */
        fun clone(url: String, dir: File, credentials: GitCredentials?, singleBranch: String? = null, monitor: ProgressMonitor? = null): JGitRepository {
            if (dir.exists() && dir.list()?.isNotEmpty() == true) throw GitOperationException("${dir.name} already exists")
            return try {
                val git = Git.cloneRepository().setURI(url).setDirectory(dir).setCredentialsProvider(credentials?.provider())
                    .apply {
                        monitor?.let { setProgressMonitor(it) }
                        singleBranch?.let {
                            setBranch(Constants.R_HEADS + it)
                            setBranchesToClone(listOf(Constants.R_HEADS + it))
                            setCloneAllBranches(false)
                        }
                    }.call()
                JGitRepository(git)
            } catch (e: GitAPIException) {
                dir.deleteRecursively()
                throw GitOperationException(e.message ?: "Clone failed", e)
            } catch (e: org.eclipse.jgit.errors.TransportException) {
                dir.deleteRecursively()
                throw GitOperationException(e.message ?: "Clone failed", e)
            }
        }

        private fun GitCredentials.provider(): CredentialsProvider = UsernamePasswordCredentialsProvider(username, token)
    }
}

/** Adapts JGit progress to a callback and a cancel check. */
class CallbackProgressMonitor(
    private val onProgress: (GitProgress) -> Unit,
    private val cancelled: () -> Boolean = { false },
) : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0

    override fun start(totalTasks: Int) = Unit
    override fun beginTask(title: String, totalWork: Int) {
        task = title
        total = totalWork
        done = 0
        onProgress(GitProgress(task, done, total))
    }

    override fun update(completed: Int) {
        done += completed
        onProgress(GitProgress(task, done, total))
    }

    override fun endTask() = onProgress(GitProgress(task, total, total))
    override fun isCancelled(): Boolean = cancelled()
}
