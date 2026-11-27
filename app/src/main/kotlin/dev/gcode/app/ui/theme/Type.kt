package dev.gcode.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

internal val GCodeTypography = Typography()

/** Monospace style for code, diffs and logs. Always laid out left-to-right by callers. */
val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
