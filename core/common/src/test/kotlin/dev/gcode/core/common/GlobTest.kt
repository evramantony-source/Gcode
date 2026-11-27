package dev.gcode.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlobTest {
    @Test
    fun `name patterns match at any depth`() {
        assertThat(Glob("*.kt").matches("src/main/A.kt")).isTrue()
        assertThat(Glob("*.kt").matches("A.kts")).isFalse()
        assertThat(Glob(".env").matches("config/.env")).isTrue()
        assertThat(Glob(".env.*").matches(".env.local")).isTrue()
    }

    @Test
    fun `double star spans directories`() {
        assertThat(Glob("src/**/*.kt").matches("src/a/b/C.kt")).isTrue()
        assertThat(Glob("src/**/*.kt").matches("src/C.kt")).isTrue()
        assertThat(Glob("src/**").matches("src/x/y")).isTrue()
        assertThat(Glob("src/*.kt").matches("src/a/C.kt")).isFalse()
    }

    @Test
    fun `alternatives, classes and question marks`() {
        assertThat(Glob("*.{jks,keystore}").matches("app/release.keystore")).isTrue()
        assertThat(Glob("id_rsa*").matches("home/id_rsa.pub")).isTrue()
        assertThat(Glob("file?.txt").matches("file1.txt")).isTrue()
        assertThat(Glob("[ab].md").matches("b.md")).isTrue()
        assertThat(Glob("[ab].md").matches("c.md")).isFalse()
    }
}
