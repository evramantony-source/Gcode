package dev.gcode.app.builds

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Result of the last install, published by [InstallResultReceiver]. */
data class InstallResult(val success: Boolean, val message: String)

/**
 * Installs APKs with a PackageInstaller session (ARCHITECTURE.md section 8.4). The system always shows its
 * own confirmation; GCode additionally confirms before starting (installing is in the always-confirm set).
 */
object ApkInstaller {
    private val _results = MutableStateFlow<InstallResult?>(null)
    val results: StateFlow<InstallResult?> = _results.asStateFlow()

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" setting for GCode. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "package versionName (versionCode)", or null if the file is not a valid APK. */
    fun describe(context: Context, apk: File): String? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return "${info.packageName} ${info.versionName ?: ""} ($code)"
    }

    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        _results.value = null
        requireNotNull(describe(context, apk)) { "${apk.name} is not a valid APK" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    internal fun publish(result: InstallResult) {
        _results.value = result
    }
}

/** Receives PackageInstaller status: launches the system confirmation, then reports the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.publish(InstallResult(true, "Installed"))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || message.contains("INCOMPATIBLE") || message.contains("signatures")) {
                    " The installed app was signed with a different key. Uninstall it first, or set up a stable debug key in Builds › Signing."
                } else ""
                ApkInstaller.publish(InstallResult(false, "Install failed ($status): $message$hint"))
            }
        }
    }
}
