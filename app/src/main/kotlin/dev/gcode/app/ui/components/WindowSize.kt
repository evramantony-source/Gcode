package dev.gcode.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width breakpoint for two-pane layouts (Material "expanded" window width). */
val WIDE_LAYOUT_MIN_WIDTH: Dp = 840.dp

/** True on tablets, unfolded foldables and wide windows: the window, not the screen, decides. */
@Composable
fun isWideLayout(minWidth: Dp = WIDE_LAYOUT_MIN_WIDTH): Boolean {
    val width = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { width.toDp() } >= minWidth
}
