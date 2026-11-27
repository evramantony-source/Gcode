package dev.gcode.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun `masks known secret values`() {
        val out = SecretRedactor.redact("key=supersecretvalue end", listOf("supersecretvalue"))
        assertThat(out).isEqualTo("key=[REDACTED] end")
    }

    @Test
    fun `masks github and google token shapes`() {
        // Built from parts so the repository secret scanner does not flag these fake values.
        val text = "t1 " + "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789" +
            " t2 " + "github_pat_" + "11ABCDEFG0123456789_abcdefghij" +
            " k " + "AIza" + "SyA1234567890abcdefghijklmnopqrstuvw"
        val out = SecretRedactor.redact(text)
        assertThat(out).doesNotContain("ghp_")
        assertThat(out).doesNotContain("github_pat_")
        assertThat(out).doesNotContain("AIza")
    }

    @Test
    fun `keeps header names but masks values`() {
        assertThat(SecretRedactor.redact("Authorization: Bearer abc.def"))
            .isEqualTo("Authorization: Bearer [REDACTED]")
        assertThat(SecretRedactor.redact("x-goog-api-key: something"))
            .isEqualTo("x-goog-api-key: [REDACTED]")
    }

    @Test
    fun `ignores very short known values`() {
        assertThat(SecretRedactor.redact("a b c", listOf("a"))).isEqualTo("a b c")
    }
}
