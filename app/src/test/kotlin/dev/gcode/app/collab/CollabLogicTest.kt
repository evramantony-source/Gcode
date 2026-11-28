package dev.gcode.app.collab

import com.google.common.truth.Truth.assertThat
import dev.gcode.app.ai.AiAssistant
import dev.gcode.app.ui.collab.ApkChoice
import dev.gcode.app.ui.collab.PrMergeConfirm
import dev.gcode.app.ui.collab.ReleasesViewModel
import dev.gcode.app.ui.repos.RepoSettingsViewModel
import dev.gcode.core.data.db.BuildEntity
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.git.ConflictHunk
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhPullRef
import dev.gcode.github.model.GhRelease
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollabLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aiProposalsLoseMarkdownFences() {
        assertThat(AiAssistant.stripFences("```kotlin\nval x = 1\n```\n")).isEqualTo("val x = 1\n")
        assertThat(AiAssistant.stripFences("```\nval x = 1\nval y = 2\n```")).isEqualTo("val x = 1\nval y = 2\n")
        assertThat(AiAssistant.stripFences("val x = 1\n")).isEqualTo("val x = 1\n")
    }

    @Test
    fun conflictPromptCarriesBothSidesBaseAndContext() {
        val hunk = ConflictHunk(0, "val x = 1\n", "val x = 2\n", "val x = 0\n", "HEAD", "feature")
        val prompt = AiAssistant.conflictPrompt("app/Main.kt", hunk, "fun a() {\n", "}\n")
        assertThat(prompt).contains("File: app/Main.kt")
        assertThat(prompt).contains("<ours>\nval x = 1\n</ours>")
        assertThat(prompt).contains("<theirs>\nval x = 2\n</theirs>")
        assertThat(prompt).contains("<base>\nval x = 0\n</base>")
        assertThat(prompt).contains("(HEAD)")
        assertThat(prompt).contains("(feature)")
        assertThat(AiAssistant.conflictPrompt("a", hunk.copy(base = null), "", "")).doesNotContain("<base>")
    }

    private fun pr(base: String) = GhPull(5, "x", head = GhPullRef("feature", "abc"), base = GhPullRef(base))

    @Test
    fun mergeIntoTheDefaultBranchUsesTheStrongConfirmation() {
        assertThat(PrMergeConfirm.of(pr("main"), "merge", "main").intoDefault).isTrue()
        assertThat(PrMergeConfirm.of(pr("develop"), "squash", "main").intoDefault).isFalse()
        assertThat(PrMergeConfirm.of(pr("develop"), "merge", null).intoDefault).isTrue()
    }

    @Test
    fun releaseAssetsAreNamedAfterTheTag() {
        val release = GhRelease(1, "v1.2.0")
        assertThat(ReleasesViewModel.assetName(release, ApkChoice("/x/app-debug.apk", ""))).isEqualTo("app-debug-v1.2.0.apk")
        assertThat(ReleasesViewModel.assetName(release, ApkChoice("/x/app-v1.2.0.apk", ""))).isEqualTo("app-v1.2.0.apk")
        assertThat(ReleasesViewModel.assetName(GhRelease(2, "release/1.0"), ApkChoice("/x/app.apk", ""))).isEqualTo("app-release-1.0.apk")
    }

    @Test
    fun onlyApksStillOnTheDeviceCanBeAttached() {
        val apk = tmp.newFile("app-debug.apk")
        fun build(id: Long, path: String?) = BuildEntity(
            repoFullName = "o/r", runId = id, runNumber = id.toInt(), workflowId = 1, workflowName = "Build", branch = "main", headSha = "abcdef123",
            event = "push", status = "completed", conclusion = "success", htmlUrl = "", createdAt = 0, updatedAt = 0, apkPath = path, apkLabel = "dev.x 1.0 (3)",
        )
        val choices = ReleasesViewModel.apkChoices(listOf(build(1, apk.path), build(2, "/missing.apk"), build(3, null)))
        assertThat(choices.map { it.path }).containsExactly(apk.path)
        assertThat(choices.single().label).contains("#1 main abcdef1")
    }

    @Test
    fun repoSettingsAreNormalised() {
        val repo = LocalRepoEntity("o/r", "/p", "main", "u", clonedAt = 0, lastOpenedAt = 0)
        val n = RepoSettingsViewModel.normalise(repo.copy(authorName = "  ", authorEmail = " me@x.dev ", buildWorkflow = "", autoFixLimit = 42, defaultMode = "full_auto"))
        assertThat(n.authorName).isNull()
        assertThat(n.authorEmail).isEqualTo("me@x.dev")
        assertThat(n.buildWorkflow).isNull()
        assertThat(n.autoFixLimit).isEqualTo(RepoSettingsViewModel.MAX_FIX_LIMIT)
        // Full auto is never stored as a default.
        assertThat(n.defaultMode).isNull()
        assertThat(RepoSettingsViewModel.normalise(repo.copy(defaultMode = "plan")).defaultMode).isEqualTo("plan")
    }
}
