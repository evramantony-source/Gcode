package dev.gcode.app.ai

import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.select.ModelSelector
import dev.gcode.gemini.transport.GeminiTransport
import dev.gcode.git.ConflictHunk
import javax.inject.Inject
import javax.inject.Singleton

/** One-shot Gemini helpers outside the agent loop (conflict resolution, web search model choice). */
@Singleton
class AiAssistant @Inject constructor(
    private val transport: GeminiTransport,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val selector: ModelSelector,
    private val health: ModelHealth,
) {
    /** The first usable model of the user's chain (preferred → newest Flash → newest Flash-Lite). */
    suspend fun currentModel(): String {
        val preferred = settings.current().preferredModel
        val now = System.currentTimeMillis()
        return selector.chain(models.current(), preferred).firstOrNull { health.isUsable(it, now) } ?: preferred
    }

    /** Proposes a resolution for one conflict hunk; the user reviews it before it is used. */
    suspend fun resolveConflict(path: String, hunk: ConflictHunk, before: String, after: String): String {
        val request = GenerateContentRequest(
            contents = listOf(Content.user(conflictPrompt(path, hunk, before, after))),
            systemInstruction = Content(parts = listOf(dev.gcode.gemini.model.Part.text(CONFLICT_SYSTEM))),
        )
        val response = transport.generateContent(currentModel(), request)
        val text = response.candidates.firstOrNull()?.content?.text ?: error("Gemini returned no answer")
        return stripFences(text)
    }

    companion object {
        const val CONFLICT_SYSTEM =
            "You resolve Git merge conflicts. Reply with only the final text for the conflicting region: " +
                "no conflict markers, no explanation, no Markdown code fences."

        fun conflictPrompt(path: String, hunk: ConflictHunk, before: String, after: String) = buildString {
            appendLine("File: $path")
            appendLine("Text before the conflict:")
            appendLine("<before>").append(before).appendLine("</before>")
            appendLine("Our version (${hunk.oursLabel.ifEmpty { "current branch" }}):")
            appendLine("<ours>").append(hunk.ours).appendLine("</ours>")
            hunk.base?.let {
                appendLine("Common ancestor:")
                appendLine("<base>").append(it).appendLine("</base>")
            }
            appendLine("Their version (${hunk.theirsLabel.ifEmpty { "incoming branch" }}):")
            appendLine("<theirs>").append(hunk.theirs).appendLine("</theirs>")
            appendLine("Text after the conflict:")
            appendLine("<after>").append(after).appendLine("</after>")
            append(
                "Combine both versions so the intent of each change is kept. If they truly contradict, choose the one that " +
                    "keeps the code consistent with the surrounding text. Keep the file's indentation and line endings.",
            )
        }

        /** Removes a surrounding ``` fence if the model added one anyway. */
        fun stripFences(text: String): String {
            val trimmed = text.trim('\n')
            if (!trimmed.trimStart().startsWith("```")) return text
            val lines = trimmed.trimStart().lines()
            val body = lines.drop(1).let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
            return body.joinToString("\n") + "\n"
        }
    }
}
