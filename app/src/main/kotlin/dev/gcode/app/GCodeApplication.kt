package dev.gcode.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.gcode.git.SandboxedSystemReader
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class GCodeApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // JGit must not look for a system git or a home directory on Android.
        SandboxedSystemReader.install(File(filesDir, "jgit"))
    }
}
