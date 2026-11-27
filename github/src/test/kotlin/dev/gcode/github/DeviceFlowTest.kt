package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.github.auth.DeviceFlow
import dev.gcode.github.auth.DeviceFlowResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DeviceFlowTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private var now = 0L
    private lateinit var flow: DeviceFlow

    @Before
    fun setUp() {
        server.start()
        flow = DeviceFlow(
            OkHttpClient(), "Ov23liTestClient",
            deviceCodeUrl = server.url("/login/device/code").toString(),
            accessTokenUrl = server.url("/login/oauth/access_token").toString(),
            clock = { now },
            sleep = { sleeps += it; now += it },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val code = """{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

    @Test
    fun `requests a code with client id and scopes, then polls through pending and slow_down`() = runTest {
        server.enqueue(json(code))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"error":"slow_down","interval":10}"""))
        server.enqueue(json("""{"access_token":"gho_token","token_type":"bearer","scope":"repo,workflow,read:org"}"""))

        val device = flow.start()
        assertThat(device.userCode).isEqualTo("WDJB-MJHT")
        val first = server.takeRequest()
        assertThat(first.headers["Accept"]).isEqualTo("application/json")
        assertThat(first.body?.utf8()).contains("client_id=Ov23liTestClient")
        assertThat(first.body?.utf8()).contains("scope=repo+workflow+read%3Aorg")

        val result = flow.awaitToken(device) as DeviceFlowResult.Success
        assertThat(result.token).isEqualTo("gho_token")
        assertThat(result.scopes).containsExactly("repo", "workflow", "read:org")
        assertThat(sleeps).containsExactly(5_000L, 5_000L, 10_000L).inOrder()
        val poll = server.takeRequest().body?.utf8().orEmpty()
        assertThat(poll).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
        assertThat(poll).contains("device_code=dc")
    }

    @Test
    fun `denied, expired and disabled are reported`() = runTest {
        val device = dev.gcode.github.http.GitHubJson.decodeFromString(dev.gcode.github.model.DeviceCodeResponse.serializer(), code)
        server.enqueue(json("""{"error":"access_denied"}"""))
        assertThat(flow.awaitToken(device)).isEqualTo(DeviceFlowResult.Denied)
        server.enqueue(json("""{"error":"token_expired"}"""))
        assertThat(flow.awaitToken(device)).isEqualTo(DeviceFlowResult.Expired)
        server.enqueue(json("""{"error":"device_flow_disabled","error_description":"x"}"""))
        val failed = flow.awaitToken(device) as DeviceFlowResult.Failed
        assertThat(failed.message).contains("Enable it")
    }

    @Test
    fun `gives up when the code lifetime runs out`() = runTest {
        val shortLived = dev.gcode.github.model.DeviceCodeResponse("dc", "AAAA-BBBB", "https://github.com/login/device", expiresIn = 8, interval = 5)
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        assertThat(flow.awaitToken(shortLived)).isEqualTo(DeviceFlowResult.Expired)
    }
}
