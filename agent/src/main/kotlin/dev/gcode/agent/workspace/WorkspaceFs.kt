package dev.gcode.agent.workspace

import java.io.File
import java.io.IOException

/** A file in the workspace. [path] is relative to the root, '/'-separated, without a leading slash. */
data class WsEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

class WorkspaceException(message: String) : IOException(message)

/**
 * The agent's view of the workspace (ARCHITECTURE.md sections 6.4 and 7.6): either a cloned repository in
 * app storage ([LocalWorkspaceFs], Git-capable) or a folder the user picked with the Storage Access
 * Framework (an Android implementation in :app, files only). Every path is sandboxed to the root.
 */
interface WorkspaceFs {
    val displayName: String
    val supportsGit: Boolean

    /** The root directory on disk, when there is one (local workspaces only). */
    val rootDir: File?

    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun size(path: String): Long
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeBytes(path: String, bytes: ByteArray)
    suspend fun delete(path: String): Boolean
    suspend fun move(from: String, to: String)
    suspend fun list(path: String): List<WsEntry>

    /** Depth-first walk of files and directories under [path], skipping `.git`. */
    suspend fun walk(path: String = "", maxEntries: Int = 20_000): List<WsEntry>

    suspend fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)
    suspend fun writeText(path: String, text: String) = writeBytes(path, text.toByteArray(Charsets.UTF_8))

    companion object {
        /**
         * Normalises a model-supplied path to a root-relative one. Leading '/' and './' are dropped;
         * '..' may not climb above the root.
         */
        fun normalize(raw: String): String {
            val parts = ArrayDeque<String>()
            raw.replace('\\', '/').split('/').forEach { seg ->
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) throw WorkspaceException("Path escapes the workspace: $raw") else parts.removeLast()
                    else -> parts.addLast(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}

/** A workspace on the local file system (a cloned repository). Symlinks pointing outside the root are rejected. */
class LocalWorkspaceFs(root: File, override val displayName: String = root.name) : WorkspaceFs {
    private val root: File = root.canonicalFile
    override val supportsGit: Boolean get() = File(root, ".git").isDirectory
    override val rootDir: File get() = root

    fun file(path: String): File {
        val rel = WorkspaceFs.normalize(path)
        val f = if (rel.isEmpty()) root else File(root, rel)
        val canonical = f.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw WorkspaceException("Path escapes the workspace: $path")
        }
        return f
    }

    private fun rel(f: File): String = f.canonicalFile.relativeTo(root).invariantSeparatorsPath

    override suspend fun exists(path: String) = file(path).exists()
    override suspend fun isDirectory(path: String) = file(path).isDirectory
    override suspend fun size(path: String) = file(path).length()

    override suspend fun readBytes(path: String): ByteArray {
        val f = file(path)
        if (!f.isFile) throw WorkspaceException(if (f.isDirectory) "$path is a directory" else "$path does not exist")
        return f.readBytes()
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray) {
        val f = file(path)
        if (WorkspaceFs.normalize(path).isEmpty()) throw WorkspaceException("Cannot write to the workspace root")
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    override suspend fun delete(path: String): Boolean {
        val f = file(path)
        if (f == root || f.canonicalFile == root) throw WorkspaceException("Cannot delete the workspace root")
        return f.exists() && f.deleteRecursively()
    }

    override suspend fun move(from: String, to: String) {
        val src = file(from)
        val dst = file(to)
        if (!src.exists()) throw WorkspaceException("$from does not exist")
        if (dst.exists()) throw WorkspaceException("$to already exists")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst)
            src.deleteRecursively()
        }
    }

    override suspend fun list(path: String): List<WsEntry> {
        val dir = file(path)
        if (!dir.isDirectory) throw WorkspaceException("$path is not a directory")
        return dir.listFiles().orEmpty().filter { it.name != ".git" }.map { it.toEntry() }
            .sortedWith(compareBy<WsEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun walk(path: String, maxEntries: Int): List<WsEntry> {
        val start = file(path)
        if (!start.exists()) throw WorkspaceException("$path does not exist")
        return start.walkTopDown().onEnter { it.name != ".git" }.filter { it != start || it.isFile }
            .take(maxEntries).map { it.toEntry() }.toList()
    }

    private fun File.toEntry() = WsEntry(rel(this), name, isDirectory, if (isFile) length() else 0, lastModified())
}
