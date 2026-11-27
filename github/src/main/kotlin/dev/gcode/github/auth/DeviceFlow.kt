package dev.gcode.github.auth

import dev.gcode.github.GitHubApi
import dev.gcode.github.http.GitHubJson
import dev.gcode.github.http.await
import dev.gcode.github.model.AccessTokenResponse
import dev.gcode.github.model.DeviceCodeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of polling for the device-flow token. */
sealed interface DeviceFlowResult {
    data class Success(val token: String, val scopes: Set<String>) : DeviceFlowResult
    data object Denied : DeviceFlowResult
    data object Expired : DeviceFlowResult
    data class Failed(val code: String, val message: String) : DeviceFlowResult
}

/**
 * GitHub OAuth device flow (docs.github.com, "Authorizing OAuth apps", checked 2026-11-27).
 * No client secret is needed. Polling honours `interval` and adds 5 s on `slow_down`.
 */
class DeviceFlow(
    private val client: OkHttpClient,
    private val clientId: String,
    private val deviceCodeUrl: String = GitHubApi.DEVICE_CODE_URL,
    private val accessTokenUrl: String = GitHubApi.ACCESS_TOKEN_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun start(scopes: List<String> = GitHubApi.SIGN_IN_SCOPES): DeviceCodeResponse {
        require(clientId.isNotBlank()) { "No GitHub OAuth App client ID configured" }
        val body = FormBody.Builder().add("client_id", clientId).add("scope", scopes.joinToString(" ")).build()
        val text = post(deviceCodeUrl, body)
        val parsed = runCatching { GitHubJson.decodeFromString(DeviceCodeResponse.serializer(), text) }.getOrNull()
        if (parsed != null) return parsed
        val error = runCatching { GitHubJson.decodeFromString(AccessTokenResponse.serializer(), text) }.getOrNull()
        throw IllegalStateException(describe(error?.error ?: "unknown_error", error?.errorDescription))
    }

    /** Polls until the user approves, denies, or the code expires. */
    suspend fun awaitToken(code: DeviceCodeResponse, onTick: (secondsLeft: Long) -> Unit = {}): DeviceFlowResult {
        var interval = code.interval.coerceAtLeast(1).toLong()
        val deadline = clock() + code.expiresIn * 1000L
        while (true) {
            if (clock() >= deadline) return DeviceFlowResult.Expired
            sleep(interval * 1000)
            onTick(((deadline - clock()) / 1000).coerceAtLeast(0))
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", code.deviceCode)
                .add("grant_type", GitHubApi.DEVICE_GRANT_TYPE)
                .build()
            val response = GitHubJson.decodeFromString(AccessTokenResponse.serializer(), post(accessTokenUrl, body))
            response.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                return DeviceFlowResult.Success(token, response.scope.orEmpty().split(',', ' ').filter { it.isNotBlank() }.toSet())
            }
            when (val error = response.error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (response.interval?.toLong() ?: (interval + 5)).coerceAtLeast(interval + 5)
                "expired_token", "token_expired" -> return DeviceFlowResult.Expired
                "access_denied" -> return DeviceFlowResult.Denied
                null -> return DeviceFlowResult.Failed("empty_response", "GitHub returned neither a token nor an error.")
                else -> return DeviceFlowResult.Failed(error, describe(error, response.errorDescription))
            }
        }
    }

    private suspend fun post(url: String, body: FormBody): String {
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        return client.newCall(request).await().use { withContext(Dispatchers.IO) { it.body.string() } }
    }

    companion object {
        fun describe(error: String, description: String?): String = when (error) {
            "device_flow_disabled" -> "Device flow is not enabled for this OAuth App. Enable it in the app's settings on GitHub."
            "incorrect_client_credentials" -> "The GitHub OAuth App client ID is not valid."
            "incorrect_device_code" -> "The device code is not valid. Start sign-in again."
            "unsupported_grant_type" -> "GitHub rejected the grant type."
            else -> description ?: error
        }
    }
}
