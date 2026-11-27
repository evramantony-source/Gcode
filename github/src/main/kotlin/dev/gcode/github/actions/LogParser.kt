package dev.gcode.github.actions

import dev.gcode.github.model.GhJob
import java.time.Instant

/** One step of a job log. */
data class StepLog(
    val number: Int,
    val name: String,
    val conclusion: String?,
    val lines: List<String>,
    /** Indexes into [lines] that look like errors. */
    val errorLines: List<Int>,
) {
    val failed: Boolean get() = conclusion == "failure"
}

/**
 * Splits a job's plain-text log into its steps (ARCHITECTURE.md section 8.4). Each log line starts with an
 * ISO-8601 timestamp; lines are assigned to steps by the steps' start and end times from the jobs API.
 * GitHub's `##[group]` / `##[error]` markers are kept so the UI can style them.
 */
object LogParser {
    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) ?(.*)$""")
    private val ERROR = Regex("""##\[error]|^e: |FAILURE: |What went wrong|BUILD FAILED|error: |\bFAILED\b|Exception in thread|AAPT: error""")

    data class Line(val time: Instant?, val text: String)

    fun lines(log: String): List<Line> = log.lineSequence().map { raw ->
        val m = TIMESTAMP.matchEntire(raw.trimEnd('\r'))
        if (m != null) Line(runCatching { Instant.parse(m.groupValues[1]) }.getOrNull(), m.groupValues[2]) else Line(null, raw.trimEnd('\r'))
    }.toList()

    fun split(log: String, job: GhJob): List<StepLog> {
        val all = lines(log)
        val steps = job.steps.sortedBy { it.number }
        if (steps.isEmpty()) return listOf(stepLog(0, job.name, job.conclusion, all.map { it.text }))
        val buckets = steps.associate { it.number to mutableListOf<String>() }
        val ranges = steps.map { s -> Triple(s.number, s.startedAt?.let(::parse), s.completedAt?.let(::parse)) }
        var current = steps.first().number
        for (line in all) {
            val t = line.time
            if (t != null) {
                // The last step that started at or before this line.
                ranges.lastOrNull { (_, start, _) -> start != null && !t.isBefore(start.minusMillis(500)) }?.let { current = it.first }
            }
            buckets.getValue(current) += line.text
        }
        return steps.map { s -> stepLog(s.number, s.name, s.conclusion, buckets.getValue(s.number)) }
    }

    private fun stepLog(number: Int, name: String, conclusion: String?, lines: List<String>) =
        StepLog(number, name, conclusion, lines, lines.indices.filter { ERROR.containsMatchIn(lines[it]) })

    private fun parse(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

    /**
     * A compact failure report for the agent: each failed step's error lines with context plus its tail,
     * capped at [maxChars] so it fits a tool response.
     */
    fun failureSummary(steps: List<StepLog>, tailLines: Int = 120, contextLines: Int = 3, maxChars: Int = 24_000): String {
        val failed = steps.filter { it.failed }.ifEmpty { steps.filter { it.errorLines.isNotEmpty() } }
        if (failed.isEmpty()) return "No failed steps found in the log."
        val out = StringBuilder()
        for (step in failed) {
            out.appendLine("=== Step ${step.number}: ${step.name} (${step.conclusion ?: "unknown"}) ===")
            val keep = sortedSetOf<Int>()
            step.errorLines.forEach { i -> (maxOf(0, i - contextLines)..minOf(step.lines.lastIndex, i + contextLines)).forEach(keep::add) }
            (maxOf(0, step.lines.size - tailLines) until step.lines.size).forEach(keep::add)
            var last = -2
            for (i in keep) {
                if (i != last + 1) out.appendLine("…")
                out.appendLine(step.lines[i])
                last = i
            }
        }
        return if (out.length <= maxChars) out.toString() else "…(cut)\n" + out.takeLast(maxChars)
    }
}
