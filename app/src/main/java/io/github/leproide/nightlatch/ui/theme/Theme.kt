/*
 * NightLatch - NFC alarm clock
 * Copyright (C) 2026 Leproide
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.leproide.nightlatch.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Night-city palette: deep violet-black, neon cyan, hot magenta, warning yellow. */
object Neon {
    val Void = Color(0xFF07060D)
    val Panel = Color(0xFF110E1C)
    val PanelHi = Color(0xFF1A1530)
    val Cyan = Color(0xFF00F0FF)
    val Magenta = Color(0xFFFF2BD6)
    val Yellow = Color(0xFFF5FF3B)
    val Red = Color(0xFFFF3355)
    val Green = Color(0xFF39FF88)
    val Dim = Color(0xFF7D7A99)
    val Text = Color(0xFFE6E3FF)
}

private val Mono = FontFamily.Monospace

private val CyberTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 72.sp, letterSpacing = 2.sp),
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 48.sp, letterSpacing = 2.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.5.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.5.sp),
)

@Composable
fun NightLatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan,
            onPrimary = Neon.Void,
            secondary = Neon.Magenta,
            onSecondary = Neon.Void,
            tertiary = Neon.Yellow,
            background = Neon.Void,
            onBackground = Neon.Text,
            surface = Neon.Panel,
            onSurface = Neon.Text,
            surfaceVariant = Neon.PanelHi,
            onSurfaceVariant = Neon.Dim,
            surfaceContainerHigh = Neon.PanelHi,
            surfaceContainerHighest = Neon.PanelHi,
            outline = Neon.Dim,
            error = Neon.Red,
        ),
        typography = CyberTypography,
        shapes = Shapes(
            small = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
            medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
            large = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
            extraLarge = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
        ),
        content = content,
    )
}
