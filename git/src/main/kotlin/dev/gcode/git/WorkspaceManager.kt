package dev.gcode.git

import dev.gcode.core.model.RepoRef
import java.io.File

/** Cloned repositories live at `<root>/<owner>/<name>` in app-private storage. */
class WorkspaceManager(val root: File) {
    fun dirFor(repo: RepoRef): File = File(File(root, repo.owner), repo.name)

    fun isCloned(repo: RepoRef): Boolean = File(dirFor(repo), ".git").isDirectory

    fun cloned(): List<RepoRef> = root.listFiles { f -> f.isDirectory }.orEmpty().flatMap { owner ->
        owner.listFiles { f -> File(f, ".git").isDirectory }.orEmpty().mapNotNull { RepoRef.parse("${owner.name}/${it.name}") }
    }.sortedBy { it.fullName }

    fun delete(repo: RepoRef): Boolean {
        val dir = dirFor(repo)
        val ok = dir.deleteRecursively()
        dir.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        return ok
    }

    fun sizeBytes(repo: RepoRef): Long = dirFor(repo).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
