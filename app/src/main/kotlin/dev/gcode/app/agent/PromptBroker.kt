package dev.gcode.app.agent

import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.UserPrompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** An approval waiting for the user; the UI shows it and calls [answer]. */
class PendingPrompt(val request: ApprovalRequest, private val result: CompletableDeferred<ApprovalResult>) {
    fun answer(value: ApprovalResult) {
        result.complete(value)
    }
}

/** Bridges tool approvals to the UI. One prompt at a time (ARCHITECTURE.md section 6.2). */
@Singleton
class PromptBroker @Inject constructor() : UserPrompter {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingPrompt?>(null)
    val pending: StateFlow<PendingPrompt?> = _pending.asStateFlow()

    override suspend fun ask(request: ApprovalRequest): ApprovalResult = mutex.withLock {
        val deferred = CompletableDeferred<ApprovalResult>()
        _pending.value = PendingPrompt(request, deferred)
        try {
            deferred.await()
        } finally {
            _pending.value = null
        }
    }
}
