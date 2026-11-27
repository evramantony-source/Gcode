package dev.gcode.app.ui.licenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen

/**
 * "Open-source licences": GCode's own licence, the LGPL notice for Sora Editor, and every dependency
 * with its licence (generated at build time by the AboutLibraries Gradle plugin).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenLicense: (BundledLicense) -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    GCodeScreen(
        title = stringResource(R.string.more_licenses),
        onBack = onBack,
        modifier = Modifier.testTag("screen_licenses"),
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("licenses_list"),
            header = { licenseHeader(onOpenLicense) },
        )
    }
}

private fun LazyListScope.licenseHeader(onOpenLicense: (BundledLicense) -> Unit) {
    item(key = "gcode_license") {
        LicenseCard(
            title = stringResource(R.string.licenses_gcode_title),
            body = stringResource(R.string.licenses_gcode_body),
            tag = "license_card_gcode",
            onViewText = { onOpenLicense(BundledLicense.APACHE_2_0) },
        )
    }
    item(key = "lgpl_notice") {
        LicenseCard(
            title = stringResource(R.string.licenses_lgpl_title),
            body = stringResource(R.string.licenses_lgpl_body),
            tag = "license_card_lgpl",
            onViewText = { onOpenLicense(BundledLicense.LGPL_2_1) },
        )
    }
    item(key = "dependencies_title") {
        Text(
            text = stringResource(R.string.licenses_dependencies),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LicenseCard(title: String, body: String, tag: String, onViewText: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onViewText) { Text(stringResource(R.string.licenses_view_full_text)) }
        }
    }
}
