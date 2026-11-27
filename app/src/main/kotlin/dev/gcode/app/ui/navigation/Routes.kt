package dev.gcode.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import dev.gcode.app.ui.licenses.BundledLicense
import kotlinx.serialization.Serializable

// Top-level destinations
@Serializable data object ChatRoute : NavKey
@Serializable data object ReposRoute : NavKey
@Serializable data object CodeRoute : NavKey
@Serializable data object BuildsRoute : NavKey
@Serializable data object MoreRoute : NavKey

// Reached from "More"
@Serializable data object PullRequestsRoute : NavKey
@Serializable data object IssuesRoute : NavKey
@Serializable data object ReleasesRoute : NavKey
@Serializable data object SessionsRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object LicensesRoute : NavKey
@Serializable data object AboutRoute : NavKey
@Serializable data class LicenseTextRoute(val license: BundledLicense) : NavKey
