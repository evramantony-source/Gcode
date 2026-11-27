package dev.gcode.git

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.ByteArrayOutputStream
import java.io.File

/** Unified diffs of in-memory text (used for approval sheets and tool cards). */
object TextDiff {
    fun unified(path: String, before: String?, after: String?, context: Int = 3): String {
        val a = RawText((before ?: "").toByteArray(Charsets.UTF_8))
        val b = RawText((after ?: "").toByteArray(Charsets.UTF_8))
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(RawTextComparator.DEFAULT, a, b)
        if (edits.isEmpty()) return ""
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { f ->
            f.setContext(context)
            f.format(edits, a, b)
        }
        val header = "--- ${if (before == null) "/dev/null" else "a/$path"}\n+++ ${if (after == null) "/dev/null" else "b/$path"}\n"
        return header + out.toString(Charsets.UTF_8.name())
    }

    /** Lines added and removed, for summaries. */
    fun stats(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { l ->
            if (l.startsWith("+") && !l.startsWith("+++")) added++
            if (l.startsWith("-") && !l.startsWith("---")) removed++
        }
        return added to removed
    }
}

/** .gitignore rules for a working tree (root `.gitignore`, nested ones and `.git/info/exclude`). */
class IgnoreMatcher(private val root: File) {
    private val cache = mutableMapOf<String, IgnoreNode?>()

    private fun node(dir: String): IgnoreNode? = synchronized(cache) {
        cache.getOrPut(dir) {
            val file = if (dir == INFO_EXCLUDE) File(root, ".git/info/exclude") else File(if (dir.isEmpty()) root else File(root, dir), ".gitignore")
            if (!file.isFile) null else IgnoreNode().apply { file.inputStream().use { parse(it) } }
        }
    }

    /** True if [path] (root-relative, '/'-separated) is ignored. Evaluated deepest rule set first. */
    fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        // Any ignored parent directory ignores everything below it.
        for (i in 1 until segments.size) {
            if (check(segments.take(i).joinToString("/"), true) == true) return true
        }
        return check(path, isDirectory) == true
    }

    private fun check(path: String, isDirectory: Boolean): Boolean? {
        val segments = path.split('/')
        for (depth in segments.size - 1 downTo 0) {
            val dir = segments.take(depth).joinToString("/")
            val rel = segments.drop(depth).joinToString("/")
            when (node(dir)?.isIgnored(rel, isDirectory)) {
                IgnoreNode.MatchResult.IGNORED -> return true
                IgnoreNode.MatchResult.NOT_IGNORED -> return false
                else -> Unit
            }
        }
        return when (node(INFO_EXCLUDE)?.isIgnored(path, isDirectory)) {
            IgnoreNode.MatchResult.IGNORED -> true
            IgnoreNode.MatchResult.NOT_IGNORED -> false
            else -> null
        }
    }

    fun invalidate() = synchronized(cache) { cache.clear() }

    private companion object {
        const val INFO_EXCLUDE = "\u0000exclude"
    }
}
