package dev.gcode.app.builds

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A run to open on the Builds screen, e.g. from a "build finished" notification. */
data class BuildLink(val repo: String, val runId: Long)

object BuildLinks {
    private val _pending = MutableStateFlow<BuildLink?>(null)
    val pending: StateFlow<BuildLink?> = _pending.asStateFlow()

    fun open(link: BuildLink) {
        _pending.value = link
    }

    /** Reads the extras set by [BuildMonitorWorker.notifyFinished]. */
    fun handle(intent: Intent?) {
        val repo = intent?.getStringExtra(BuildMonitorWorker.EXTRA_OPEN_BUILD_REPO) ?: return
        val runId = intent.getLongExtra(BuildMonitorWorker.EXTRA_OPEN_BUILD_RUN, 0)
        if (runId > 0) open(BuildLink(repo, runId))
        intent.removeExtra(BuildMonitorWorker.EXTRA_OPEN_BUILD_REPO)
    }

    fun consume(link: BuildLink) {
        _pending.compareAndSet(link, null)
    }
}
