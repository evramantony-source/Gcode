package dev.gcode.app.ui.code

import com.google.common.truth.Truth.assertThat
import dev.gcode.app.editor.EditorSupport
import org.junit.Test

class SideBySideTest {
    private val diff = """
        diff --git a/a.kt b/a.kt
        --- a/a.kt
        +++ b/a.kt
        @@ -1,4 +1,4 @@
         fun main() {
        -    println(1)
        +    println(2)
        +    println(3)
         }
        -// end
    """.trimIndent()

    @Test
    fun `pairs removed and added lines and keeps line numbers`() {
        val rows = SideBySide.rows(diff)
        assertThat(rows.first().kind).isEqualTo(SplitRow.Kind.FILE)
        assertThat(rows.first().right).isEqualTo("a.kt")
        val changed = rows.first { it.kind == SplitRow.Kind.CHANGED }
        assertThat(changed.leftNumber).isEqualTo(2)
        assertThat(changed.left).isEqualTo("    println(1)")
        assertThat(changed.rightNumber).isEqualTo(2)
        assertThat(changed.right).isEqualTo("    println(2)")
        val added = rows.first { it.kind == SplitRow.Kind.ADDED }
        assertThat(added.rightNumber).isEqualTo(3)
        assertThat(added.left).isNull()
        val context = rows.filter { it.kind == SplitRow.Kind.CONTEXT }
        assertThat(context.map { it.leftNumber to it.rightNumber }).containsExactly(1 to 1, 3 to 4).inOrder()
        assertThat(rows.last().kind).isEqualTo(SplitRow.Kind.REMOVED)
        assertThat(rows.last().leftNumber).isEqualTo(4)
    }

    @Test
    fun `maps file extensions to bundled grammars`() {
        assertThat(EditorSupport.scopeFor("app/src/Main.kt")).isEqualTo("source.kotlin")
        assertThat(EditorSupport.scopeFor("build.gradle.kts")).isEqualTo("source.kotlin")
        assertThat(EditorSupport.scopeFor("settings.gradle")).isEqualTo("source.groovy")
        assertThat(EditorSupport.scopeFor(".github/workflows/build.yml")).isEqualTo("source.yaml")
        assertThat(EditorSupport.scopeFor("res/layout/main.xml")).isEqualTo("text.xml")
        assertThat(EditorSupport.scopeFor("gradlew")).isEqualTo("source.shell")
        assertThat(EditorSupport.scopeFor("notes.txt")).isNull()
    }
}
