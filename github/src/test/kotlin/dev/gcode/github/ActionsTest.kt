package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.model.RepoRef
import dev.gcode.github.actions.AndroidProjectDetector
import dev.gcode.github.actions.LogParser
import dev.gcode.github.actions.WorkflowTemplate
import dev.gcode.github.crypto.SealedBox
import dev.gcode.github.http.GitHubHttp
import dev.gcode.github.http.GitHubJson
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhRelease
import dev.gcode.github.model.GhStep
import dev.gcode.github.model.PutSecretRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ActionsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val api = MockWebServer()
    private val storage = MockWebServer()
    private lateinit var client: GitHubClient
    private val repo = RepoRef("octo", "app")

    @Before
    fun setUp() {
        api.start()
        storage.start()
        client = GitHubClient(GitHubHttp(OkHttpClient(), token = { "gho_secret" }, baseUrl = api.url("/").toString(), sleep = {}))
    }

    @After
    fun tearDown() {
        api.close()
        storage.close()
    }

    @Test
    fun `dispatch returns the new run id (API 2026-03-10)`() = runTest {
        api.enqueue(MockResponse.Builder().code(200).body("""{"workflow_run_id":987,"run_url":"u","html_url":"h"}""").build())
        val result = client.dispatch(repo, "gcode-build.yml", "main", mapOf("build_type" to "debug"))
        assertThat(result.workflowRunId).isEqualTo(987)
        val req = api.takeRequest()
        assertThat(req.url.encodedPath).isEqualTo("/repos/octo/app/actions/workflows/gcode-build.yml/dispatches")
        assertThat(req.body?.utf8()).isEqualTo("""{"ref":"main","inputs":{"build_type":"debug"}}""")
    }

    @Test
    fun `finds runs by head sha and parses jobs with steps`() = runTest {
        api.enqueue(MockResponse.Builder().body("""{"total_count":1,"workflow_runs":[{"id":5,"head_sha":"abc","status":"in_progress","event":"push","workflow_id":1,"html_url":"h"}]}""").build())
        api.enqueue(MockResponse.Builder().body("""{"total_count":1,"jobs":[{"id":9,"run_id":5,"name":"build","status":"completed","conclusion":"failure","steps":[{"name":"Build","status":"completed","conclusion":"failure","number":3}]}]}""").build())
        assertThat(client.runs(repo, headSha = "abc", event = "push").single().id).isEqualTo(5)
        assertThat(api.takeRequest().url.queryParameter("head_sha")).isEqualTo("abc")
        assertThat(client.jobs(repo, 5).single().steps.single().conclusion).isEqualTo("failure")
    }

    @Test
    fun `artifact download follows the redirect without sending the token to storage`() = runTest {
        api.enqueue(MockResponse.Builder().code(302).addHeader("Location", storage.url("/blob/artifact.zip").toString()).build())
        storage.enqueue(MockResponse.Builder().body("ZIPDATA").build())
        val dest = tmp.newFile("a.zip")
        client.downloadArtifact(repo, 42, dest)
        assertThat(dest.readText()).isEqualTo("ZIPDATA")
        assertThat(api.takeRequest().headers["Authorization"]).isEqualTo("Bearer gho_secret")
        assertThat(storage.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `secrets are sealed with the repository public key`() = runTest {
        val secretKey = ByteArray(32) { it.toByte() }
        val publicKey = Base64.getEncoder().encodeToString(SealedBox.publicKey(secretKey))
        api.enqueue(MockResponse.Builder().body("""{"key_id":"kid-1","key":"$publicKey"}""").build())
        api.enqueue(MockResponse.Builder().code(201).build())
        client.putSecret(repo, "DEBUG_KEYSTORE_BASE64", "super-secret")
        api.takeRequest()
        val put = api.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.url.encodedPath).isEqualTo("/repos/octo/app/actions/secrets/DEBUG_KEYSTORE_BASE64")
        val body = GitHubJson.decodeFromString(PutSecretRequest.serializer(), put.body!!.utf8())
        assertThat(body.keyId).isEqualTo("kid-1")
        assertThat(String(SealedBox.open(Base64.getDecoder().decode(body.encryptedValue), secretKey))).isEqualTo("super-secret")
        assertThat(body.encryptedValue).doesNotContain("super-secret")
    }

    @Test
    fun `release assets go to the upload url without the template part`() = runTest {
        api.enqueue(MockResponse.Builder().code(201).body("""{"id":1,"name":"app.apk","size":3}""").build())
        val release = GhRelease(1, "v1.0.0", uploadUrl = api.url("/repos/octo/app/releases/1/assets").toString() + "{?name,label}")
        val file = tmp.newFile("app.apk").apply { writeText("apk") }
        assertThat(client.uploadAsset(release, file, "app.apk", "application/vnd.android.package-archive").name).isEqualTo("app.apk")
        val req = api.takeRequest()
        assertThat(req.url.encodedPath).isEqualTo("/repos/octo/app/releases/1/assets")
        assertThat(req.url.queryParameter("name")).isEqualTo("app.apk")
        assertThat(req.headers["Content-Type"]).isEqualTo("application/vnd.android.package-archive")
    }

    private val log = """
        2026-11-27T10:00:00.0000000Z ##[group]Run actions/checkout@v7
        2026-11-27T10:00:01.0000000Z Syncing repository
        2026-11-27T10:00:05.0000000Z ##[group]Run ./gradlew assembleDebug
        2026-11-27T10:00:30.0000000Z e: file:///app/src/Main.kt:3:5 Unresolved reference 'foo'
        2026-11-27T10:00:31.0000000Z FAILURE: Build failed with an exception.
        2026-11-27T10:00:32.0000000Z ##[error]Process completed with exit code 1.
    """.trimIndent()

    private val job = GhJob(
        id = 1, name = "build", conclusion = "failure",
        steps = listOf(
            GhStep("Checkout", conclusion = "success", number = 1, startedAt = "2026-11-27T10:00:00Z", completedAt = "2026-11-27T10:00:04Z"),
            GhStep("Build debug APK", conclusion = "failure", number = 2, startedAt = "2026-11-27T10:00:05Z", completedAt = "2026-11-27T10:00:33Z"),
        ),
    )

    @Test
    fun `log parser assigns lines to steps by time and flags errors`() {
        val steps = LogParser.split(log, job)
        assertThat(steps.map { it.lines.size }).containsExactly(2, 4).inOrder()
        val build = steps[1]
        assertThat(build.failed).isTrue()
        assertThat(build.errorLines.map { build.lines[it] }).containsAtLeast("e: file:///app/src/Main.kt:3:5 Unresolved reference 'foo'", "##[error]Process completed with exit code 1.")
        val summary = LogParser.failureSummary(steps)
        assertThat(summary).contains("Step 2: Build debug APK (failure)")
        assertThat(summary).contains("Unresolved reference 'foo'")
        assertThat(summary).doesNotContain("Syncing repository")
    }

    @Test
    fun `template and detection`() {
        val yaml = WorkflowTemplate.android()
        assertThat(yaml).startsWith(WorkflowTemplate.MARKER)
        assertThat(yaml).contains("workflow_dispatch:")
        assertThat(yaml).contains("name: app-debug-apk")
        assertThat(yaml).contains("\${{ secrets.DEBUG_KEYSTORE_BASE64 }}")
        assertThat(AndroidProjectDetector.supportsDispatch(yaml)).isTrue()
        assertThat(AndroidProjectDetector.isAndroidApp(mapOf("settings.gradle.kts" to "", "app/build.gradle.kts" to "plugins { id(\"com.android.application\") }"))).isTrue()
        assertThat(AndroidProjectDetector.isAndroidApp(mapOf("settings.gradle.kts" to "", "lib/build.gradle.kts" to "plugins { kotlin(\"jvm\") }"))).isFalse()
        val withInputs = "on:\n  workflow_dispatch:\n    inputs:\n      flavor:\n        description: x\n      debug:\n        type: boolean\njobs: {}\n"
        assertThat(AndroidProjectDetector.dispatchInputs(withInputs)).containsExactly("flavor", "debug").inOrder()
    }
}
