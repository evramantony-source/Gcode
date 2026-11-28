package dev.gcode.git

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConflictsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file = """
        |fun a() = 1
        |<<<<<<< HEAD
        |fun b() = 2
        |=======
        |fun b() = 3
        |>>>>>>> feature
        |fun c() = 4
        |<<<<<<< HEAD
        |val x = "ours"
        |||||||| base
        |val x = "base"
        |=======
        |val x = "theirs"
        |val y = 1
        |>>>>>>> feature
        |
    """.trimMargin()

    @Test
    fun parsesHunksWithLabelsAndBase() {
        val segments = Conflicts.parse(file)
        val hunks = Conflicts.hunks(segments)
        assertThat(hunks).hasSize(2)
        assertThat(hunks[0].ours).isEqualTo("fun b() = 2\n")
        assertThat(hunks[0].theirs).isEqualTo("fun b() = 3\n")
        assertThat(hunks[0].oursLabel).isEqualTo("HEAD")
        assertThat(hunks[0].theirsLabel).isEqualTo("feature")
        assertThat(hunks[0].base).isNull()
        assertThat(hunks[1].base).isEqualTo("val x = \"base\"\n")
        assertThat(hunks[1].theirs).isEqualTo("val x = \"theirs\"\nval y = 1\n")
        assertThat(Conflicts.hasMarkers(file)).isTrue()
    }

    @Test
    fun resolvesEachWay() {
        val segments = Conflicts.parse(file)
        assertThat(Conflicts.resolve(segments, mapOf(0 to Resolution.Ours, 1 to Resolution.Theirs)))
            .isEqualTo("fun a() = 1\nfun b() = 2\nfun c() = 4\nval x = \"theirs\"\nval y = 1\n")
        assertThat(Conflicts.resolve(segments, mapOf(0 to Resolution.Both, 1 to Resolution.Custom("val x = \"merged\""))))
            .isEqualTo("fun a() = 1\nfun b() = 2\nfun b() = 3\nfun c() = 4\nval x = \"merged\"\n")
        assertThrows(IllegalStateException::class.java) { Conflicts.resolve(segments, mapOf(0 to Resolution.Ours)) }
    }

    @Test
    fun keepsWindowsLineEndingsAndIgnoresStrayMarkers() {
        val crlf = "a\r\n<<<<<<< HEAD\r\nb\r\n=======\r\nc\r\n>>>>>>> other\r\nd\r\n"
        assertThat(Conflicts.resolve(Conflicts.parse(crlf), mapOf(0 to Resolution.Ours))).isEqualTo("a\r\nb\r\nd\r\n")
        // A Markdown underline outside a conflict, and an unterminated block, are plain text.
        val text = "Title\n=======\n<<<<<<< HEAD\nno end\n"
        assertThat(Conflicts.hunks(Conflicts.parse(text))).isEmpty()
        assertThat(Conflicts.resolve(Conflicts.parse(text), emptyMap())).isEqualTo(text)
        assertThat(Conflicts.hasMarkers("plain\n")).isFalse()
    }

    @Test
    fun resolvingAMergeOnDiskCreatesAMergeCommit() {
        SandboxedSystemReader.install(tmp.newFolder("home"))
        val dir = tmp.newFolder("repo")
        val me = Author("T", "t@example.com")
        JGitRepository.init(dir, "main").use { r ->
            File(dir, "App.kt").writeText("fun b() = 1\n")
            r.addAll(); r.commit("init", me)
            r.createBranch("feature", checkout = true)
            File(dir, "App.kt").writeText("fun b() = 3\n")
            r.addAll(); r.commit("theirs", me)
            r.checkout("main")
            File(dir, "App.kt").writeText("fun b() = 2\n")
            r.addAll(); r.commit("ours", me)

            assertThat(r.merge("feature")).isInstanceOf(MergeOutcome.Conflicts::class.java)
            assertThat(r.conflicts()).containsExactly("App.kt")
            assertThat(r.mergeMessage()).contains("Merge branch 'feature'")
            val segments = Conflicts.parse(File(dir, "App.kt").readText())
            File(dir, "App.kt").writeText(Conflicts.resolve(segments, mapOf(0 to Resolution.Custom("fun b() = 5\n"))))
            r.add(listOf("App.kt"))
            assertThat(r.conflicts()).isEmpty()
            r.commit(r.mergeMessage()!!, me)
            assertThat(r.isMerging()).isFalse()
            assertThat(r.log(1).single().parents).hasSize(2)
        }
        assertThat(File(dir, "App.kt").readText()).isEqualTo("fun b() = 5\n")
    }

    @Test
    fun takingOneSideOfADeleteModifyConflict() {
        SandboxedSystemReader.install(tmp.newFolder("home2"))
        val dir = tmp.newFolder("repo2")
        val me = Author("T", "t@example.com")
        JGitRepository.init(dir, "main").use { r ->
            File(dir, "a.txt").writeText("1\n")
            File(dir, "b.bin").writeText("base\n")
            r.addAll(); r.commit("init", me)
            r.createBranch("feature", checkout = true)
            File(dir, "a.txt").delete()
            File(dir, "b.bin").writeText("theirs\n")
            r.addAll(); r.commit("theirs", me)
            r.checkout("main")
            File(dir, "a.txt").writeText("2\n")
            File(dir, "b.bin").writeText("ours\n")
            r.addAll(); r.commit("ours", me)
            assertThat(r.merge("feature")).isInstanceOf(MergeOutcome.Conflicts::class.java)
            assertThat(r.conflicts()).containsExactly("a.txt", "b.bin")
            r.takeSide("a.txt", ours = false) // theirs deleted it
            r.takeSide("b.bin", ours = true)
            assertThat(r.conflicts()).isEmpty()
            assertThat(File(dir, "a.txt").exists()).isFalse()
            assertThat(File(dir, "b.bin").readText()).isEqualTo("ours\n")
        }
    }
}
