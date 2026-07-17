package com.aftglw.devapi.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/**
 * 获取设备电池信息（电量、充电状态、温度）。
 * 不需要额外权限。
 */
class BatteryTool : AiTool {
    override val name = "battery_info"
    override val description = "获取设备当前电池电量百分比、充电状态和温度。无需权限。"
    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = ctx.registerReceiver(null, ifilter) ?: return "无法获取电池信息"
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val plugText = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "交流电"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
            else -> "未插电"
        }
        return buildString {
            appendLine("电量: ${pct}%")
            appendLine("状态: $statusText")
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) appendLine("充电方式: $plugText")
            appendLine("温度: ${temp}°C")
        }.trimEnd()
    }
}
