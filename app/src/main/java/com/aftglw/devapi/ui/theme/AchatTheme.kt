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
    val accentYellow: Color = Color(0xFFFFD600),
    val isDark: Boolean = false,
    val themeId: String = "default"
)

/**
 * Global spacing and sizing tokens for UI consistency.
 */
data class DesignTokens(
    val screenPadding: androidx.compose.ui.unit.Dp = 20.dp,
    val itemSpacing: androidx.compose.ui.unit.Dp = 12.dp,
    val cardCorner: androidx.compose.ui.unit.Dp = 16.dp,
    val buttonHeight: androidx.compose.ui.unit.Dp = 52.dp
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
    accentYellow = Color(0xFFFFD600),
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
    accentYellow = Color(0xFFD32F2F),
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
    accentYellow = Color(0xFFC62828),
    themeId = "washi"
)

val MarathonAchatColors = AchatColors(
    primary = Color(0xFF00E5FF), // Electric Cyan
    background = Color(0xFF0A0A0A), // Deep Tech Black
    surface = Color(0xFF1A1A1A), // Dark Slate
    onBackground = Color.White,
    onSurface = Color.White,
    divider = Color(0xFF2C2C2C),
    chatBubbleMe = Color(0xFF00E5FF),
    chatBubbleAi = Color(0xFF2A2A2A),
    accentYellow = Color(0xFFB2FF59), // Electric Lime
    themeId = "marathon"
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

val MarathonAchatShapes = AchatShapes(
    bubbleMe = RoundedCornerShape(2.dp),
    bubbleAi = RoundedCornerShape(2.dp),
    card = RectangleShape
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

val MarathonAchatTypography = AchatTypography(
    title = FontFamily(
        Font(R.font.oswald_bold, weight = FontWeight.Bold)
    ),
    body = FontFamily(
        Font(R.font.noto_sans_sc, weight = FontWeight.Normal)
    ),
    mono = FontFamily(Font(R.font.space_mono_regular))
)

val LocalAchatColors = staticCompositionLocalOf { DefaultAchatColors }
val LocalAchatShapes = staticCompositionLocalOf { DefaultAchatShapes }
val LocalAchatTypography = staticCompositionLocalOf { DefaultAchatTypography }
val LocalDesignTokens = staticCompositionLocalOf { DesignTokens() }

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

    val tokens: DesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalDesignTokens.current
}
