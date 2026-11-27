package dev.gcode.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import dev.gcode.app.R
import dev.gcode.app.ui.components.GCodeScreen
import dev.gcode.app.ui.navigation.AboutRoute
import dev.gcode.app.ui.navigation.IssuesRoute
import dev.gcode.app.ui.navigation.LicensesRoute
import dev.gcode.app.ui.navigation.PullRequestsRoute
import dev.gcode.app.ui.navigation.ReleasesRoute
import dev.gcode.app.ui.navigation.SecretsRoute
import dev.gcode.app.ui.navigation.SessionsRoute
import dev.gcode.app.ui.navigation.SettingsRoute

private data class MoreItem(val route: NavKey, @StringRes val label: Int, @DrawableRes val icon: Int, val tag: String)

private val MORE_ITEMS = listOf(
    MoreItem(PullRequestsRoute, R.string.more_pull_requests, R.drawable.ic_pull_request, "more_pull_requests"),
    MoreItem(IssuesRoute, R.string.more_issues, R.drawable.ic_issue, "more_issues"),
    MoreItem(ReleasesRoute, R.string.more_releases, R.drawable.ic_release, "more_releases"),
    MoreItem(SecretsRoute, R.string.more_secrets, R.drawable.ic_key, "more_secrets"),
    MoreItem(SessionsRoute, R.string.more_sessions, R.drawable.ic_sessions, "more_sessions"),
    MoreItem(SettingsRoute, R.string.more_settings, R.drawable.ic_settings, "more_settings"),
    MoreItem(LicensesRoute, R.string.more_licenses, R.drawable.ic_licenses, "more_licenses"),
    MoreItem(AboutRoute, R.string.more_about, R.drawable.ic_info, "more_about"),
)

@Composable
fun MoreScreen(onNavigate: (NavKey) -> Unit) {
    GCodeScreen(title = stringResource(R.string.nav_more), modifier = Modifier.testTag("screen_more")) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(MORE_ITEMS, key = { it.tag }) { item ->
                ListItem(
                    modifier = Modifier
                        .testTag(item.tag)
                        .clickable { onNavigate(item.route) },
                    leadingContent = { Icon(painterResource(item.icon), contentDescription = null) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                ) {
                    Text(stringResource(item.label))
                }
            }
        }
    }
}
