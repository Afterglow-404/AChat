package com.example.wechatclone.ui

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.wechatclone.R

/**
 * Font switching.
 *
 * When custom_font=true:
 *   English → Space Mono (monospace)
 *   Chinese → Noto Sans SC (fallback, Space Mono)
 */

private val CombinedFont = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold,   FontWeight.Bold),
    Font(R.font.noto_sans_sc,      FontWeight.Normal),
)

fun isCustomFontEnabled(context: Context): Boolean =
    context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        .getBoolean("custom_font", false)

fun buildCustomTypography(): Typography = Typography(
    displayLarge   = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Bold,   fontSize = 32.sp),
    displayMedium  = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Bold,   fontSize = 28.sp),
    displaySmall   = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Bold,   fontSize = 24.sp),
    headlineLarge  = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 24.sp),
    headlineMedium = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    headlineSmall  = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleLarge     = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Bold,   fontSize = 18.sp),
    titleMedium    = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall     = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge      = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Normal,  fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Normal,  fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Normal,  fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium    = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall     = TextStyle(fontFamily = CombinedFont, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)
