package dev.gcode.agent.extend

/** A prompt template the user runs with `/name arguments` (ARCHITECTURE.md section 11). */
data class SlashCommand(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val template: String,
    val builtin: Boolean = false,
)

/** What the composer does with a line that starts with `/`. */
sealed interface CommandAction {
    /** Send this text to the agent. */
    data class Prompt(val text: String, val command: String) : CommandAction

    /** App actions handled by the chat screen. */
    data object Compact : CommandAction
    data object Clear : CommandAction
    data object Help : CommandAction

    data class Unknown(val name: String) : CommandAction
}

object SlashCommands {
    const val COMMANDS_DIR = ".gcode/commands"

    val INIT_PROMPT = """
        Analyse this repository and create GEMINI.md at its root, the project memory GCode loads into every chat.
        Include: what the project is; the module/directory layout; how it is built and tested (builds run on GitHub Actions, not on the phone; name the workflow if there is one); coding conventions you can see (language versions, style, libraries, patterns); and gotchas a new contributor should know.
        Read the build files, README and a few representative sources first. Keep it under 150 lines of concise Markdown.
        If GEMINI.md already exists, improve it and keep the user's own notes.
    """.trimIndent()

    /** Commands that are part of GCode. */
    val BUILTIN = listOf(
        SlashCommand("compact", "Summarise the conversation so far to free context", template = "", builtin = true),
        SlashCommand("clear", "Start a new chat", template = "", builtin = true),
        SlashCommand("init", "Create or update GEMINI.md for this repository", template = INIT_PROMPT, builtin = true),
        SlashCommand("help", "List the available commands", template = "", builtin = true),
    )

    /** "/name rest of line" → (name, arguments), or null if the text is not a command. */
    fun parse(input: String): Pair<String, String>? {
        val text = input.trimStart()
        if (!text.startsWith("/") || text.startsWith("//")) return null
        val match = Regex("^/([A-Za-z0-9][A-Za-z0-9_:.-]*)(?:\\s+([\\s\\S]*))?$").matchEntire(text.trimEnd()) ?: return null
        return match.groupValues[1] to match.groupValues[2].trim()
    }

    /**
     * Fills a template: `$ARGUMENTS` is the whole argument string, `$1`…`$9` are whitespace-separated
     * arguments. A template without placeholders gets the arguments appended.
     */
    fun expand(template: String, arguments: String): String {
        val positional = arguments.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hasPlaceholder = template.contains("\$ARGUMENTS") || Regex("\\$[1-9]").containsMatchIn(template)
        var out = template.replace("\$ARGUMENTS", arguments)
        out = Regex("\\$([1-9])").replace(out) { m -> positional.getOrNull(m.groupValues[1].toInt() - 1).orEmpty() }
        return if (!hasPlaceholder && arguments.isNotEmpty()) "${out.trimEnd()}\n\n$arguments" else out
    }

    /** Loads `.gcode/commands` Markdown files; a file in a sub-folder becomes `folder:name`. Keys are paths relative to the commands folder. */
    fun load(files: Map<String, String>): List<SlashCommand> = files.mapNotNull { (path, text) ->
        if (!path.endsWith(".md")) return@mapNotNull null
        val name = path.removeSuffix(".md").replace('/', ':')
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9_:.-]*$").matches(name)) return@mapNotNull null
        val fm = Frontmatter.parse(text)
        SlashCommand(
            name = name,
            description = fm.string("description") ?: fm.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty(),
            argumentHint = fm.string("argument-hint"),
            template = fm.body,
        )
    }.sortedBy { it.name }

    /** Project commands override built-ins of the same name, except the app actions. */
    fun resolve(input: String, project: List<SlashCommand>): CommandAction? {
        val (name, args) = parse(input) ?: return null
        when (name) {
            "compact" -> return CommandAction.Compact
            "clear" -> return CommandAction.Clear
            "help" -> return CommandAction.Help
        }
        val command = project.firstOrNull { it.name == name } ?: BUILTIN.firstOrNull { it.name == name && it.template.isNotEmpty() }
            ?: return CommandAction.Unknown(name)
        return CommandAction.Prompt(expand(command.template, args), name)
    }

    fun suggestions(prefix: String, project: List<SlashCommand>): List<SlashCommand> {
        val p = prefix.removePrefix("/")
        return (BUILTIN + project.filter { c -> BUILTIN.none { it.name == c.name } }).filter { it.name.startsWith(p, ignoreCase = true) }
    }
}
