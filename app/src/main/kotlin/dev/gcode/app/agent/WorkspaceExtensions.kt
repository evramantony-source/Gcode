package dev.gcode.app.agent

import dev.gcode.agent.extend.AgentDefinition
import dev.gcode.agent.extend.SlashCommand
import dev.gcode.agent.extend.SlashCommands
import dev.gcode.agent.workspace.WorkspaceFs
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.app.workspace.ActiveWorkspace
import dev.gcode.app.workspace.WorkspaceProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project files that extend GCode (ARCHITECTURE.md section 11): GEMINI.md memory, `.gcode/commands/` slash
 * commands and `.gcode/agents/` sub-agents, plus the global instructions from Settings.
 */
@Singleton
class WorkspaceExtensions @Inject constructor(
    private val workspaces: WorkspaceProvider,
    private val settings: SettingsRepository,
) {
    /** Global instructions, then the repository's GEMINI.md, for the system prompt. */
    suspend fun memory(workspace: ActiveWorkspace?): String? {
        val global = settings.current().globalMemory.trim()
        val project = workspace?.fs?.let { readCapped(it, MEMORY_FILE) }?.trim()
        return combineMemory(global, project)
    }

    suspend fun commands(workspace: ActiveWorkspace? = null): List<SlashCommand> {
        val fs = (workspace ?: workspaces.current())?.fs ?: return emptyList()
        return SlashCommands.load(readDir(fs, SlashCommands.COMMANDS_DIR, recursive = true))
    }

    suspend fun agents(workspace: ActiveWorkspace?): List<AgentDefinition> {
        val fs = workspace?.fs ?: return emptyList()
        return AgentDefinition.load(readDir(fs, AgentDefinition.AGENTS_DIR, recursive = false))
    }

    private suspend fun readDir(fs: WorkspaceFs, dir: String, recursive: Boolean): Map<String, String> = runCatching {
        if (!fs.isDirectory(dir)) return emptyMap()
        val entries = if (recursive) fs.walk(dir, maxEntries = MAX_FILES) else fs.list(dir)
        entries.filter { !it.isDirectory && it.path.endsWith(".md") }.take(MAX_FILES).mapNotNull { e ->
            readCapped(fs, e.path)?.let { e.path.removePrefix("$dir/") to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private suspend fun readCapped(fs: WorkspaceFs, path: String): String? = runCatching {
        if (!fs.exists(path) || fs.isDirectory(path)) return null
        if (fs.size(path) > MAX_BYTES) fs.readText(path).take(MAX_BYTES.toInt()) + "\n…(truncated)" else fs.readText(path)
    }.getOrNull()

    companion object {
        const val MEMORY_FILE = "GEMINI.md"
        private const val MAX_FILES = 200
        private const val MAX_BYTES = 40_000L

        fun combineMemory(global: String?, project: String?): String? = buildString {
            if (!global.isNullOrBlank()) append("## Global instructions\n").append(global.trim()).append("\n\n")
            if (!project.isNullOrBlank()) append("## $MEMORY_FILE (this repository)\n").append(project.trim())
        }.trim().ifEmpty { null }
    }
}
