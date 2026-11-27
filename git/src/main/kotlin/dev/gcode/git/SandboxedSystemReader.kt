package dev.gcode.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Keeps JGit away from the host: on Android there is no git binary to discover a system config with,
 * and no meaningful home directory. User-level config lives in the app's own directory.
 */
class SandboxedSystemReader(private val configDir: File, private val delegate: SystemReader = SystemReader.getInstance()) : SystemReader() {
    override fun getHostname(): String = "gcode"
    override fun getenv(variable: String): String? = null
    override fun getProperty(key: String): String? = if (key == "user.home") configDir.absolutePath else delegate.getProperty(key)

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = FileBasedConfig(parent, File(configDir, "gitconfig"), fs)

    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = FileBasedConfig(parent, File(configDir, "jgitconfig"), fs)

    override fun getCurrentTime(): Long = System.currentTimeMillis()
    override fun getTimezone(`when`: Long): Int = delegate.getTimezone(`when`)

    private class EmptyConfig(parent: Config?, fs: FS) : FileBasedConfig(parent, null, fs) {
        override fun load() = Unit
        override fun isOutdated(): Boolean = false
    }

    companion object {
        @Volatile private var installed = false

        /** Installs the reader once per process, before the first JGit call. */
        @Synchronized
        fun install(configDir: File) {
            if (installed) return
            configDir.mkdirs()
            setInstance(SandboxedSystemReader(configDir))
            installed = true
        }
    }
}
