package dev.gcode.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import dev.gcode.app.ui.licenses.BundledLicense
import org.junit.Test

class BackStackOpsTest {
    private fun stackOf(vararg keys: NavKey): MutableList<NavKey> = mutableListOf(*keys)

    @Test
    fun `selecting a tab resets the stack`() {
        val stack = stackOf(MoreRoute, LicensesRoute, LicenseTextRoute(BundledLicense.LGPL_2_1))
        BackStackOps.selectTopLevel(stack, TopLevelDestination.BUILDS)
        assertThat(stack).containsExactly(BuildsRoute)
        assertThat(BackStackOps.currentTopLevel(stack)).isEqualTo(TopLevelDestination.BUILDS)
    }

    @Test
    fun `navigate pushes detail routes once and switches tabs for top level routes`() {
        val stack = stackOf(MoreRoute)
        BackStackOps.navigate(stack, LicensesRoute)
        BackStackOps.navigate(stack, LicensesRoute)
        assertThat(stack).containsExactly(MoreRoute, LicensesRoute).inOrder()

        BackStackOps.navigate(stack, ReposRoute)
        assertThat(stack).containsExactly(ReposRoute)
    }

    @Test
    fun `back pops, then returns to chat, then lets the app exit`() {
        val stack = stackOf(MoreRoute, SettingsRoute)
        assertThat(BackStackOps.back(stack)).isTrue()
        assertThat(stack).containsExactly(MoreRoute)

        assertThat(BackStackOps.canGoBack(stack)).isTrue()
        assertThat(BackStackOps.back(stack)).isTrue()
        assertThat(stack).containsExactly(ChatRoute)

        assertThat(BackStackOps.canGoBack(stack)).isFalse()
        assertThat(BackStackOps.back(stack)).isFalse()
        assertThat(stack).containsExactly(ChatRoute)
    }

    @Test
    fun `unknown root falls back to the start destination`() {
        assertThat(BackStackOps.currentTopLevel(stackOf(AboutRoute))).isEqualTo(TopLevelDestination.START)
        assertThat(BackStackOps.currentTopLevel(emptyList())).isEqualTo(TopLevelDestination.START)
    }
}
