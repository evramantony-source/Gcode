package dev.gcode.app.editor

import android.content.Context
import android.graphics.Typeface
import android.view.View
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/** Sora Editor + TextMate setup (grammars and GCode themes in assets/textmate). */
object EditorSupport {
    @Volatile private var initialized = false
    private const val DARK = "gcode-dark"
    private const val LIGHT = "gcode-light"

    /** Loads grammars and themes once per process. Call off the main thread. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        listOf(DARK, LIGHT).forEach { name ->
            val path = "textmate/$name.json"
            val stream = FileProviderRegistry.getInstance().tryGetInputStream(path) ?: return@forEach
            val model = ThemeModel(IThemeSource.fromInputStream(stream, path, null), name).apply { isDark = name == DARK }
            ThemeRegistry.getInstance().loadTheme(model)
        }
        initialized = true
    }

    /** TextMate scope for a file name, or null for plain text. */
    fun scopeFor(path: String): String? {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".kt") || name.endsWith(".kts") -> "source.kotlin"
            name.endsWith(".java") -> "source.java"
            name.endsWith(".gradle") || name.endsWith(".groovy") -> "source.groovy"
            name.endsWith(".json") -> "source.json"
            name.endsWith(".xml") || name.endsWith(".svg") -> "text.xml"
            name.endsWith(".yml") || name.endsWith(".yaml") -> "source.yaml"
            name.endsWith(".md") || name.endsWith(".markdown") -> "text.html.markdown"
            name.endsWith(".sh") || name.endsWith(".bash") || name == "gradlew" -> "source.shell"
            name.endsWith(".py") -> "source.python"
            else -> null
        }
    }

    fun configure(editor: CodeEditor, path: String, dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) DARK else LIGHT)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        scopeFor(path)?.let { scope -> runCatching { editor.setEditorLanguage(TextMateLanguage.create(scope, true)) } }
        editor.setTypefaceText(Typeface.MONOSPACE)
        editor.setTextSize(13f)
        editor.isLineNumberEnabled = true
        editor.setPinLineNumber(true)
        editor.setWordwrap(false)
        // Code is always laid out left-to-right, also in Arabic.
        editor.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
}
