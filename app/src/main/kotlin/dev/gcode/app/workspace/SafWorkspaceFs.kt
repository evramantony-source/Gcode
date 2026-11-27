package dev.gcode.app.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.gcode.agent.workspace.WorkspaceException
import dev.gcode.agent.workspace.WorkspaceFs
import dev.gcode.agent.workspace.WsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A folder the user picked with the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE). Files only:
 * Git needs a real directory, so repositories are cloned into app storage instead.
 */
class SafWorkspaceFs(private val context: Context, val treeUri: Uri) : WorkspaceFs {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: throw WorkspaceException("Folder is no longer accessible")
    override val displayName: String = root.name ?: "Device folder"
    override val supportsGit: Boolean = false
    override val rootDir: File? = null

    private fun find(path: String): DocumentFile? {
        val rel = WorkspaceFs.normalize(path)
        if (rel.isEmpty()) return root
        var cur: DocumentFile = root
        for (seg in rel.split('/')) cur = cur.findFile(seg) ?: return null
        return cur
    }

    private fun require(path: String): DocumentFile = find(path) ?: throw WorkspaceException("$path does not exist")

    private fun ensureDir(segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: cur.createDirectory(seg) ?: throw WorkspaceException("Cannot create folder $seg")
        }
        return cur
    }

    override suspend fun exists(path: String) = withContext(Dispatchers.IO) { find(path) != null }
    override suspend fun isDirectory(path: String) = withContext(Dispatchers.IO) { find(path)?.isDirectory == true }
    override suspend fun size(path: String) = withContext(Dispatchers.IO) { find(path)?.length() ?: 0 }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val f = require(path)
        if (f.isDirectory) throw WorkspaceException("$path is a directory")
        context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: throw WorkspaceException("Cannot read $path")
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val rel = WorkspaceFs.normalize(path)
        if (rel.isEmpty()) throw WorkspaceException("Cannot write to the folder root")
        val parts = rel.split('/')
        val dir = ensureDir(parts.dropLast(1))
        val file = dir.findFile(parts.last()) ?: dir.createFile("application/octet-stream", parts.last()) ?: throw WorkspaceException("Cannot create $path")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: throw WorkspaceException("Cannot write $path")
        Unit
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (WorkspaceFs.normalize(path).isEmpty()) throw WorkspaceException("Cannot delete the folder root")
        find(path)?.delete() == true
    }

    override suspend fun move(from: String, to: String) {
        if (exists(to)) throw WorkspaceException("$to already exists")
        val entries = if (isDirectory(from)) walk(from).filter { !it.isDirectory } else listOf(WsEntry(WorkspaceFs.normalize(from), "", false, 0, 0))
        val base = WorkspaceFs.normalize(from)
        entries.forEach { e -> writeBytes(WorkspaceFs.normalize(to) + e.path.removePrefix(base), readBytes(e.path)) }
        delete(from)
    }

    override suspend fun list(path: String): List<WsEntry> = withContext(Dispatchers.IO) {
        val dir = require(path)
        if (!dir.isDirectory) throw WorkspaceException("$path is not a directory")
        val base = WorkspaceFs.normalize(path)
        dir.listFiles().filter { it.name != ".git" }.map { it.toEntry(base) }
            .sortedWith(compareBy<WsEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun walk(path: String, maxEntries: Int): List<WsEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<WsEntry>()
        fun visit(dir: DocumentFile, base: String) {
            for (child in dir.listFiles()) {
                if (out.size >= maxEntries) return
                if (child.name == ".git") continue
                val entry = child.toEntry(base)
                out += entry
                if (child.isDirectory) visit(child, entry.path)
            }
        }
        val start = require(path)
        if (start.isDirectory) visit(start, WorkspaceFs.normalize(path)) else out += start.toEntry(WorkspaceFs.normalize(path).substringBeforeLast('/', ""))
        out
    }

    private fun DocumentFile.toEntry(base: String): WsEntry {
        val n = name.orEmpty()
        val p = if (base.isEmpty()) n else "$base/$n"
        return WsEntry(p, n, isDirectory, if (isFile) length() else 0, lastModified())
    }
}
