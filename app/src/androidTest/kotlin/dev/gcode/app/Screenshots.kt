package dev.gcode.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves a screenshot of the current screen to the app's files/screenshots (pulled by CI with run-as and
 * used in the README). Best effort: a failed capture never fails a test.
 */
object Screenshots {
    fun take(rule: ComposeTestRule, name: String) {
        runCatching {
            rule.waitForIdle()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
