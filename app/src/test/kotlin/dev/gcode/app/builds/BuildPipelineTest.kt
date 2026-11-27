package dev.gcode.app.builds

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuildPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = tmp.newFile("artifact.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun extractsTheApkFromAnArtifactZip() {
        val dir = tmp.newFolder("out")
        val apk = BuildService.extractApk(zip("output-metadata.json" to "{}", "app-debug.apk" to "APK"), dir)
        assertThat(apk).isNotNull()
        assertThat(apk!!.name).isEqualTo("app-debug.apk")
        assertThat(apk.parentFile!!.canonicalPath).isEqualTo(dir.canonicalPath)
        assertThat(apk.readText()).isEqualTo("APK")
    }

    @Test
    fun flattensPathsSoEntriesCannotEscapeTheFolder() {
        val dir = tmp.newFolder("out")
        val apk = BuildService.extractApk(zip("../../evil/app.apk" to "X"), dir)
        assertThat(apk!!.parentFile!!.canonicalPath).isEqualTo(dir.canonicalPath)
        assertThat(File(tmp.root, "evil").exists()).isFalse()
    }

    @Test
    fun returnsNullWithoutAnApk() {
        assertThat(BuildService.extractApk(zip("mapping.txt" to "m"), tmp.newFolder("out"))).isNull()
    }

    @Test
    fun monitorPollsWhileRunningAndNotifiesOnce() {
        assertThat(BuildMonitorWorker.nextAction("queued", alreadyNotified = false)).isEqualTo(MonitorAction.POLL_AGAIN)
        assertThat(BuildMonitorWorker.nextAction("in_progress", alreadyNotified = false)).isEqualTo(MonitorAction.POLL_AGAIN)
        assertThat(BuildMonitorWorker.nextAction(null, alreadyNotified = false)).isEqualTo(MonitorAction.POLL_AGAIN)
        assertThat(BuildMonitorWorker.nextAction("completed", alreadyNotified = false)).isEqualTo(MonitorAction.NOTIFY)
        assertThat(BuildMonitorWorker.nextAction("completed", alreadyNotified = true)).isEqualTo(MonitorAction.DONE)
    }
}
