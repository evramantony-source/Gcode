package dev.gcode.app.builds

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gcode.app.MainActivity
import dev.gcode.app.R
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.model.RepoRef
import dev.gcode.github.api.ActionsApi
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Background build monitoring (ARCHITECTURE.md section 8.4): checks a run, records it, and either
 * re-enqueues itself or posts a "succeeded / failed" notification. Short one-shot works keep within
 * job quotas (Android 16 counts jobs that run alongside a foreground service).
 */
enum class MonitorAction { POLL_AGAIN, NOTIFY, DONE }

@HiltWorker
class BuildMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val actions: ActionsApi,
    private val db: GCodeDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = inputData.getString(KEY_REPO) ?: return Result.failure()
        val runId = inputData.getLong(KEY_RUN, 0)
        val ref = RepoRef.parse(repo) ?: return Result.failure()
        val run = try {
            actions.run(ref, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        val dao = db.builds()
        val existing = dao.get(repo, runId)
        val updated = existing?.copy(status = run.status, conclusion = run.conclusion, updatedAt = System.currentTimeMillis())
        when (nextAction(run.status, existing?.notified == true)) {
            MonitorAction.POLL_AGAIN -> {
                updated?.let { dao.update(it) }
                schedule(applicationContext, repo, runId, delaySeconds = POLL_SECONDS)
            }
            MonitorAction.NOTIFY -> {
                updated?.let { dao.update(it.copy(notified = true)) }
                notifyFinished(applicationContext, repo, runId, run.runNumber, run.conclusion)
            }
            MonitorAction.DONE -> updated?.let { dao.update(it) }
        }
        return Result.success()
    }

    companion object {
        const val POLL_SECONDS = 30L
        private const val MAX_ATTEMPTS = 5
        private const val KEY_REPO = "repo"
        private const val KEY_RUN = "run"
        const val CHANNEL_ID = "builds"
        const val EXTRA_OPEN_BUILD_REPO = "open_build_repo"
        const val EXTRA_OPEN_BUILD_RUN = "open_build_run"

        /** What to do after checking a run: poll again while it runs, notify once when it completes. */
        fun nextAction(status: String?, alreadyNotified: Boolean): MonitorAction = when {
            status != "completed" -> MonitorAction.POLL_AGAIN
            alreadyNotified -> MonitorAction.DONE
            else -> MonitorAction.NOTIFY
        }

        fun schedule(context: Context, repo: String, runId: Long, delaySeconds: Long = 5) {
            val request = OneTimeWorkRequestBuilder<BuildMonitorWorker>()
                .setInputData(workDataOf(KEY_REPO to repo, KEY_RUN to runId))
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("build-monitor")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("build-$repo-$runId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_builds), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        fun notifyFinished(context: Context, repo: String, runId: Long, runNumber: Int, conclusion: String?) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(context)
            val ok = conclusion == "success"
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_BUILD_REPO, repo).putExtra(EXTRA_OPEN_BUILD_RUN, runId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, runId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_builds)
                .setContentTitle(context.getString(if (ok) R.string.build_succeeded_title else R.string.build_failed_title, runNumber))
                .setContentText(context.getString(if (ok) R.string.build_succeeded_body else R.string.build_failed_body, repo, conclusion ?: "unknown"))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_builds, context.getString(if (ok) R.string.build_install else R.string.build_view_logs), pending)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(runId.toInt(), notification) }
        }
    }
}
