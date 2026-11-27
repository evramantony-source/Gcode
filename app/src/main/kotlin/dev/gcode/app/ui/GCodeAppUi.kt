package dev.gcode.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gcode.app.R
import dev.gcode.app.ui.licenses.LicenseTextScreen
import dev.gcode.app.ui.licenses.LicensesScreen
import dev.gcode.app.ui.navigation.AboutRoute
import dev.gcode.app.ui.navigation.BackStackOps
import dev.gcode.app.ui.navigation.BuildsRoute
import dev.gcode.app.ui.navigation.ChatRoute
import dev.gcode.app.ui.navigation.CodeRoute
import dev.gcode.app.ui.navigation.IssuesRoute
import dev.gcode.app.ui.navigation.LicenseTextRoute
import dev.gcode.app.ui.navigation.LicensesRoute
import dev.gcode.app.ui.navigation.MoreRoute
import dev.gcode.app.ui.navigation.PullRequestsRoute
import dev.gcode.app.ui.navigation.ReleasesRoute
import dev.gcode.app.ui.navigation.ReposRoute
import dev.gcode.app.ui.navigation.SessionsRoute
import dev.gcode.app.ui.navigation.SettingsRoute
import dev.gcode.app.ui.navigation.TopLevelDestination
import dev.gcode.app.ui.screens.AboutScreen
import dev.gcode.app.ui.screens.MoreScreen
import dev.gcode.app.ui.screens.PlaceholderScreen
import dev.gcode.app.ui.chat.ChatScreen
import dev.gcode.app.ui.approval.ApprovalHost
import dev.gcode.app.ui.code.CodeScreen
import dev.gcode.app.ui.repos.ReposScreen
import dev.gcode.app.ui.settings.SettingsScreen

/** App shell: adaptive navigation (bottom bar on phones, rail on tablets) around a Navigation 3 back stack. */
@Composable
fun GCodeAppUi() {
    val backStack = rememberNavBackStack(TopLevelDestination.START.route)
    val current = BackStackOps.currentTopLevel(backStack)
    val navigate: (NavKey) -> Unit = { BackStackOps.navigate(backStack, it) }
    val back: () -> Unit = { BackStackOps.back(backStack) }

    // At the root of a tab other than Chat, Back returns to Chat (NavDisplay only handles deeper stacks).
    BackHandler(enabled = backStack.size == 1 && BackStackOps.canGoBack(backStack)) { back() }

    ApprovalHost()
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = destination == current,
                    onClick = { BackStackOps.selectTopLevel(backStack, destination) },
                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                    modifier = Modifier.testTag(destination.testTag),
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { back() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<ChatRoute> { ChatScreen() }
                entry<ReposRoute> { ReposScreen(onOpened = { BackStackOps.selectTopLevel(backStack, TopLevelDestination.CODE) }) }
                entry<CodeRoute> { CodeScreen(onOpenRepos = { BackStackOps.selectTopLevel(backStack, TopLevelDestination.REPOS) }) }
                entry<BuildsRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.nav_builds), stringResource(R.string.placeholder_builds),
                        phase = 6, icon = R.drawable.ic_builds, testTag = "screen_builds",
                    )
                }
                entry<MoreRoute> { MoreScreen(onNavigate = navigate) }
                entry<PullRequestsRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.more_pull_requests), stringResource(R.string.placeholder_pull_requests),
                        phase = 8, icon = R.drawable.ic_pull_request, testTag = "screen_pull_requests", onBack = back,
                    )
                }
                entry<IssuesRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.more_issues), stringResource(R.string.placeholder_issues),
                        phase = 8, icon = R.drawable.ic_issue, testTag = "screen_issues", onBack = back,
                    )
                }
                entry<ReleasesRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.more_releases), stringResource(R.string.placeholder_releases),
                        phase = 8, icon = R.drawable.ic_release, testTag = "screen_releases", onBack = back,
                    )
                }
                entry<SessionsRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.more_sessions), stringResource(R.string.placeholder_sessions),
                        phase = 9, icon = R.drawable.ic_sessions, testTag = "screen_sessions", onBack = back,
                    )
                }
                entry<SettingsRoute> { SettingsScreen(onBack = back) }
                entry<LicensesRoute> {
                    LicensesScreen(onBack = back, onOpenLicense = { navigate(LicenseTextRoute(it)) })
                }
                entry<LicenseTextRoute> { key -> LicenseTextScreen(license = key.license, onBack = back) }
                entry<AboutRoute> { AboutScreen(onBack = back) }
            },
        )
    }
}
