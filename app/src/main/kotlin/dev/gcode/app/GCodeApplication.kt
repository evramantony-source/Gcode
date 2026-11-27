package dev.gcode.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.gcode.git.SandboxedSystemReader
import java.io.File

@HiltAndroidApp
class GCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // JGit must not look for a system git or a home directory on Android.
        SandboxedSystemReader.install(File(filesDir, "jgit"))
    }
}
