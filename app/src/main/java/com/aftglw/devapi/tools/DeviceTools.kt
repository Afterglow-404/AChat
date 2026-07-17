package com.aftglw.devapi.tools

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.app.usage.UsageStatsManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 检查「开诚布公」模式是否开启。
 */
private fun isOpenBookMode(ctx: Context): Boolean {
    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    return prefs.getBoolean("open_book_mode", false)
}

// ─── 1. 获取设备位置 ──────────────────────────────────────────────

class LocationTool : AiTool {
    override val name = "access_location"
    override val description = "获取设备当前所在位置的经纬度。需要用户开启「开诚布公」模式并授予位置权限。"
    override val inputSchema = JSONObject()

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        if (!isOpenBookMode(ctx))
            return "❌ 「开诚布公」模式未开启，请在设置中开启后再试。"
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "❌ 位置权限未授予，请在设置中允许「位置信息」权限。"
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        val loc = gps ?: net
        return if (loc != null) {
            val lat = String.format("%.6f", loc.latitude)
            val lng = String.format("%.6f", loc.longitude)
            """{"latitude": $lat, "longitude": $lng, "accuracy_meters": ${loc.accuracy}}"""
        } else {
            "❌ 暂未获取到位置信息，请确保 GPS 或网络定位已开启。"
        }
    }
}

// ─── 2. 读取当前活跃通知 ──────────────────────────────────────────

class ReadNotificationsTool : AiTool {
    override val name = "read_notifications"
    override val description = "读取当前设备上该应用展示的活跃通知列表。需要用户开启「开诚布公」模式。"
    override val inputSchema = JSONObject()

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        if (!isOpenBookMode(ctx))
            return "❌ 「开诚布公」模式未开启，请在设置中开启后再试。"

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.activeNotifications
        } else {
            emptyArray()
        }
        if (active.isEmpty()) return "当前无活跃通知。"
        val list = active.mapIndexed { i, status ->
            val n = status.notification
            val title = n.extras?.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val text = n.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            "${i + 1}. [${status.packageName}] ${title}: ${text}"
        }
        return "当前活跃通知 (${active.size}):\n" + list.joinToString("\n")
    }
}

// ─── 3. 读取应用使用统计 ─────────────────────────────────────────

class ReadAppUsageTool : AiTool {
    override val name = "read_app_usage"
    override val description = "读取最近 24 小时内的应用使用情况统计（各应用使用时长）。需要用户开启「开诚布公」模式并授予使用情况访问权限。"
    override val inputSchema = JSONObject()

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        if (!isOpenBookMode(ctx))
            return "❌ 「开诚布公」模式未开启，请在设置中开启后再试。"

        if (!hasUsageStatsPermission(ctx)) {
            return "❌ 使用情况访问权限未授予，请在设置中允许「使用情况访问」。"
        }

        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 24 * 60 * 60 * 1000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        if (stats.isNullOrEmpty()) return "暂无使用统计数据。"
        val sorted = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(15)
        if (sorted.isEmpty()) return "暂无使用统计数据。"
        val lines = sorted.mapIndexed { i, s ->
            val pm = ctx.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
            } catch (_: Exception) { s.packageName }
            val minutes = s.totalTimeInForeground / 60000
            "${i + 1}. $appName — ${minutes} 分钟"
        }
        return "最近 24 小时应用使用情况:\n" + lines.joinToString("\n")
    }

    private fun hasUsageStatsPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
