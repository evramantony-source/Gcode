package dev.gcode.core.data

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.data.repo.SessionRepository
import org.junit.Test

class SessionTitleTest {
    @Test
    fun `title uses the first non blank line, shortened`() {
        assertThat(SessionRepository.titleFrom("\n  Fix the build  \nmore")).isEqualTo("Fix the build")
        val long = "a".repeat(80)
        assertThat(SessionRepository.titleFrom(long)).hasLength(48)
        assertThat(SessionRepository.titleFrom(long)).endsWith("…")
    }
}
