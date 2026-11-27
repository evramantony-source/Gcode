package dev.gcode.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import dev.gcode.app.R

/**
 * Bottom bar (phone) / navigation rail (tablet) destinations. Files and source control share "Code",
 * because both belong to the open repository; this keeps the bar at five items.
 */
enum class TopLevelDestination(val route: NavKey, @StringRes val label: Int, @DrawableRes val icon: Int) {
    CHAT(ChatRoute, R.string.nav_chat, R.drawable.ic_chat),
    REPOS(ReposRoute, R.string.nav_repos, R.drawable.ic_repos),
    CODE(CodeRoute, R.string.nav_code, R.drawable.ic_code),
    BUILDS(BuildsRoute, R.string.nav_builds, R.drawable.ic_builds),
    MORE(MoreRoute, R.string.nav_more, R.drawable.ic_more),
    ;

    val testTag: String get() = "nav_${name.lowercase()}"

    companion object {
        val START = CHAT

        fun of(route: NavKey?): TopLevelDestination? = entries.firstOrNull { it.route == route }
    }
}
