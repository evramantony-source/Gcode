package dev.gcode.app.ui.licenses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.theme.CodeTextStyle

/** Shows a bundled licence text verbatim (English, monospace, always left-to-right). */
@Composable
fun LicenseTextScreen(license: BundledLicense, onBack: () -> Unit) {
    val resources = LocalResources.current
    val text = remember(license) {
        resources.openRawResource(license.text).bufferedReader().use { it.readText() }
    }
    GCodeScreen(title = stringResource(license.title), onBack = onBack, modifier = Modifier.testTag("screen_license_text")) { padding ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(text = text, style = CodeTextStyle, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
