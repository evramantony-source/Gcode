package dev.gcode.gemini

import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiApiException
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff with full jitter; a server `retryDelay` wins when present. */
class BackoffPolicy(
    val baseMs: Long = 1_000,
    val factor: Double = 2.0,
    val maxMs: Long = 32_000,
    val maxAttempts: Int = 5,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int, serverDelayMs: Long?): Long {
        if (serverDelayMs != null && serverDelayMs > 0) return min(serverDelayMs, MAX_SERVER_DELAY_MS)
        val cap = min(maxMs.toDouble(), baseMs * factor.pow(attempt.toDouble())).toLong()
        return random.nextLong(cap / 2, cap + 1)
    }

    private companion object {
        const val MAX_SERVER_DELAY_MS = 120_000L
    }
}

/** What happened while trying to get a response; shown in the chat UI. */
sealed interface StreamEvent {
    data class Chunk(val model: String, val response: GenerateContentResponse) : StreamEvent
    data class Waiting(val model: String, val attempt: Int, val delayMs: Long, val reason: String) : StreamEvent
    data class Switched(val from: String, val to: String, val reason: String) : StreamEvent
    data class Started(val model: String) : StreamEvent
}

/**
 * Calls Gemini with rate-limit handling (ARCHITECTURE.md section 5.5):
 * - 429: backoff and retry the same model (up to [BackoffPolicy.maxAttempts]);
 *   then, with auto-switch on, put it on cool-down and move to the next candidate;
 * - model unavailable for this key (404/403/zero quota): mark it for 24 h and move on (always);
 * - 5xx and network errors: retried with backoff on the same model.
 * Retries happen only before the first chunk; a stream that already produced output is not replayed.
 */
class ResilientGemini(
    private val transport: GeminiTransport,
    private val health: ModelHealth,
    private val policy: BackoffPolicy = BackoffPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun stream(
        candidates: List<String>,
        autoSwitch: Boolean,
        buildRequest: (model: String) -> GenerateContentRequest,
    ): Flow<StreamEvent> = flow {
        require(candidates.isNotEmpty()) { "No model available" }
        var index = 0
        var model = candidates[0]
        var attempt = 0
        while (true) {
            var emitted = false
            try {
                emit(StreamEvent.Started(model))
                transport.streamGenerateContent(model, buildRequest(model)).collect {
                    emitted = true
                    emit(StreamEvent.Chunk(model, it))
                }
                health.clear(model)
                return@flow
            } catch (e: GeminiApiException) {
                if (emitted) throw e
                val now = clock()
                when {
                    e.isModelUnavailable -> {
                        health.markUnavailable(model, now, e.message)
                        val next = candidates.getOrNull(++index) ?: throw e
                        emit(StreamEvent.Switched(model, next, e.message))
                        model = next
                        attempt = 0
                    }
                    e.isRateLimited || e.isServerError -> {
                        if (attempt + 1 < policy.maxAttempts) {
                            val wait = policy.delayFor(attempt, e.retryDelayMillis)
                            attempt++
                            emit(StreamEvent.Waiting(model, attempt, wait, if (e.isRateLimited) "Rate limited" else "Server error"))
                            sleep(wait)
                        } else if (e.isRateLimited && autoSwitch) {
                            health.markRateLimited(model, now)
                            val next = candidates.drop(index + 1).firstOrNull { health.isUsable(it, now) } ?: throw e
                            index = candidates.indexOf(next)
                            emit(StreamEvent.Switched(model, next, "Rate limited"))
                            model = next
                            attempt = 0
                        } else {
                            throw e
                        }
                    }
                    else -> throw e
                }
            } catch (e: IOException) {
                if (emitted || attempt + 1 >= policy.maxAttempts) throw e
                val wait = policy.delayFor(attempt, null)
                attempt++
                emit(StreamEvent.Waiting(model, attempt, wait, "Network error"))
                sleep(wait)
            }
        }
    }

    /** Non-streaming call with the same retry rules (used for summaries, search and compaction). */
    suspend fun generate(candidates: List<String>, autoSwitch: Boolean, request: (String) -> GenerateContentRequest): Pair<String, GenerateContentResponse> {
        val acc = StreamAccumulator()
        var used = candidates.first()
        var last: GenerateContentResponse? = null
        stream(candidates, autoSwitch, request).collect { event ->
            if (event is StreamEvent.Chunk) {
                used = event.model
                acc.add(event.response)
                last = event.response
            }
        }
        val merged = GenerateContentResponse(
            candidates = listOf(
                dev.gcode.gemini.model.Candidate(
                    content = acc.content(),
                    finishReason = acc.finishReason,
                    groundingMetadata = acc.groundingMetadata,
                ),
            ),
            usageMetadata = acc.usage,
            modelVersion = last?.modelVersion,
        )
        return used to merged
    }
}
