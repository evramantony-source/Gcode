package dev.gcode.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
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
import dev.gcode.app.ui.navigation.SecretsRoute
import dev.gcode.app.ui.navigation.ConflictsRoute
import dev.gcode.app.ui.collab.IssuesScreen
import dev.gcode.app.ui.collab.PullRequestsScreen
import dev.gcode.app.ui.collab.ReleasesScreen
import dev.gcode.app.ui.conflicts.ConflictsScreen
import dev.gcode.app.ui.builds.SecretsScreen
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
import dev.gcode.app.ui.builds.BuildsScreen
import dev.gcode.app.builds.BuildLinks
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gcode.app.ui.repos.ReposScreen
import dev.gcode.app.ui.settings.SettingsScreen

/** App shell: adaptive navigation (bottom bar on phones, rail on tablets) around a Navigation 3 back stack. */
@Composable
fun GCodeAppUi() {
    val backStack = rememberNavBackStack(TopLevelDestination.START.route)
    val current = BackStackOps.currentTopLevel(backStack)
    val navigate: (NavKey) -> Unit = { BackStackOps.navigate(backStack, it) }
    val back: () -> Unit = { BackStackOps.back(backStack) }
    val openRepos: () -> Unit = { BackStackOps.selectTopLevel(backStack, TopLevelDestination.REPOS) }

    // At the root of a tab other than Chat, Back returns to Chat (NavDisplay only handles deeper stacks).
    BackHandler(enabled = backStack.size == 1 && BackStackOps.canGoBack(backStack)) { back() }

    // "Build finished" notifications open the run on the Builds tab.
    val buildLink by BuildLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(buildLink) { if (buildLink != null) BackStackOps.selectTopLevel(backStack, TopLevelDestination.BUILDS) }

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
        // Tablets and large screens: the chat stays beside the file, diff or build panel (ARCHITECTURE.md section 12).
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Box(Modifier.width(420.dp).fillMaxHeight().testTag("chat_pane")) { ChatScreen() }
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
        NavDisplay(
            backStack = backStack,
            onBack = { back() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<ChatRoute> {
                    if (wide) {
                        CodeScreen(onOpenRepos = openRepos, onResolveConflicts = { navigate(ConflictsRoute) })
                    } else {
                        ChatScreen()
                    }
                }
                entry<ReposRoute> { ReposScreen(onOpened = { BackStackOps.selectTopLevel(backStack, TopLevelDestination.CODE) }) }
                entry<CodeRoute> { CodeScreen(onOpenRepos = openRepos, onResolveConflicts = { navigate(ConflictsRoute) }) }
                entry<BuildsRoute> { BuildsScreen(onOpenRepos = { BackStackOps.selectTopLevel(backStack, TopLevelDestination.REPOS) }) }
                entry<MoreRoute> { MoreScreen(onNavigate = navigate) }
                entry<PullRequestsRoute> { PullRequestsScreen(onBack = back, onOpenRepos = openRepos) }
                entry<IssuesRoute> { IssuesScreen(onBack = back, onOpenRepos = openRepos) }
                entry<ReleasesRoute> { ReleasesScreen(onBack = back, onOpenRepos = openRepos) }
                entry<SessionsRoute> {
                    PlaceholderScreen(
                        stringResource(R.string.more_sessions), stringResource(R.string.placeholder_sessions),
                        phase = 9, icon = R.drawable.ic_sessions, testTag = "screen_sessions", onBack = back,
                    )
                }
                entry<SecretsRoute> { SecretsScreen(onBack = back, onOpenRepos = openRepos) }
                entry<ConflictsRoute> { ConflictsScreen(onBack = back) }
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
    }
}
