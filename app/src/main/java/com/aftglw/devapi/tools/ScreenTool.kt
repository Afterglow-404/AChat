package com.aftglw.devapi.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import org.json.JSONObject

/**
 * 获取屏幕信息（分辨率、亮度、方向、是否亮屏）。
 * 不需要额外权限。
 */
class ScreenTool : AiTool {
    override val name = "screen_info"
    override val description = "获取设备屏幕分辨率、亮度、方向和当前是否亮屏。无需权限。"
    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val windowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = ctx.resources.displayMetrics.density
            metrics.densityDpi = ctx.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        val rotation = if (metrics.widthPixels < metrics.heightPixels) "竖屏 (0°)" else "横屏 (90°)"

        val brightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (android.os.Build.VERSION.SDK_INT >= 20) pm.isInteractive else true

        return buildString {
            appendLine("分辨率: ${metrics.widthPixels}×${metrics.heightPixels}px")
            appendLine("密度: ${metrics.densityDpi}dpi (${"%.1f".format(metrics.density)}x)")
            appendLine("方向: $rotation")
            if (brightness >= 0) appendLine("亮度: ${brightness * 100 / 255}%")
            appendLine("屏幕状态: ${if (isScreenOn) "亮屏" else "息屏"}")
        }.trimEnd()
    }
}
