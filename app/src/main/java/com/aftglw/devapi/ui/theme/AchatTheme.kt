package com.aftglw.devapi.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aftglw.devapi.R

/**
 * Semantic colors for Achat.
 */
data class AchatColors(
    val primary: Color,
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val onSurface: Color,
    val divider: Color,
    val chatBubbleMe: Color,
    val chatBubbleAi: Color,
    val stampRed: Color = Color(0xFFC62828),
    val isDark: Boolean = false,
    val themeId: String = "default"
)

/**
 * Semantic shapes for Achat.
 */
data class AchatShapes(
    val bubbleMe: Shape,
    val bubbleAi: Shape,
    val card: Shape
)

/**
 * Semantic typography for Achat.
 */
data class AchatTypography(
    val title: FontFamily,
    val body: FontFamily,
    val mono: FontFamily
)

val DefaultAchatColors = AchatColors(
    primary = Color(0xFF07C160),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    divider = Color(0xFFE0E0E0),
    chatBubbleMe = Color(0xFF8CE09C),
    chatBubbleAi = Color.White,
    themeId = "default"
)

val NewspaperAchatColors = AchatColors(
    primary = Color(0xFFAA0000), // Deeper Ink Red
    background = Color(0xFFEBE4D5), // More aged paper
    surface = Color(0xFFF2ECE0), // Lighter aged paper
    onBackground = Color(0xFF1A1A1A), // Heavy Ink
    onSurface = Color(0xFF1A1A1A),
    divider = Color(0xFFC0BBA8),
    chatBubbleMe = Color(0xFFE0DAC8),
    chatBubbleAi = Color.White,
    themeId = "newspaper"
)

val WashiAchatColors = AchatColors(
    primary = Color(0xFF708090), // Slate Gray / Indigo
    background = Color(0xFFFDF5E6), // Old Lace / Washi
    surface = Color(0xFFFAF0E6), // Linen
    onBackground = Color(0xFF2E2E2E), // Sumi Ink
    onSurface = Color(0xFF2E2E2E),
    divider = Color(0xFFE6D5B8),
    chatBubbleMe = Color(0xFFDEE4E7),
    chatBubbleAi = Color.White,
    stampRed = Color(0xFFC62828),
    themeId = "washi"
)

val DefaultAchatShapes = AchatShapes(
    bubbleMe = RoundedCornerShape(20.dp, 8.dp, 20.dp, 20.dp),
    bubbleAi = RoundedCornerShape(8.dp, 20.dp, 20.dp, 20.dp),
    card = RoundedCornerShape(16.dp)
)

val NewspaperAchatShapes = AchatShapes(
    bubbleMe = RoundedCornerShape(4.dp),
    bubbleAi = RoundedCornerShape(4.dp),
    card = RectangleShape
)

val WashiAchatShapes = AchatShapes(
    bubbleMe = RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp),
    bubbleAi = RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp),
    card = RoundedCornerShape(12.dp)
)

val DefaultAchatTypography = AchatTypography(
    title = FontFamily.Default,
    body = FontFamily.Default,
    mono = FontFamily.Monospace
)

val NewspaperAchatTypography = AchatTypography(
    title = FontFamily(
        Font(R.font.notoserifsc_bold, weight = FontWeight.Bold)
    ),
    body = FontFamily(
        Font(R.font.notoserifsc_regular, weight = FontWeight.Normal)
    ),
    mono = FontFamily(Font(R.font.special_elite_regular))
)

val WashiAchatTypography = AchatTypography(
    title = FontFamily(
        Font(R.font.zhimangxing_regular, weight = FontWeight.Normal),
        Font(R.font.homemadeapple_regular, weight = FontWeight.Normal)
    ),
    body = FontFamily(
        Font(R.font.notoserifsc_regular, weight = FontWeight.Normal)
    ),
    mono = FontFamily(Font(R.font.homemadeapple_regular))
)

val LocalAchatColors = staticCompositionLocalOf { DefaultAchatColors }
val LocalAchatShapes = staticCompositionLocalOf { DefaultAchatShapes }
val LocalAchatTypography = staticCompositionLocalOf { DefaultAchatTypography }

object AchatTheme {
    val colors: AchatColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAchatColors.current

    val shapes: AchatShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalAchatShapes.current

    val typography: AchatTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAchatTypography.current
}
