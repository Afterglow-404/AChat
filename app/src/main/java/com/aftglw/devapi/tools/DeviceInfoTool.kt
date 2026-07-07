package com.aftglw.devapi.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

class DeviceInfoTool : AiTool {
    override val name = "device_info"
    override val description = "获取设备信息，如电量、存储空间等。参数：type=battery/storage"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        when (args["type"] ?: "battery") {
            "battery" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                return if (level >= 0) "当前电量${level}%"
                else "无法读取电量信息"
            }
            "storage" -> {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes / (1024 * 1024 * 1024)
                val free = stat.availableBytes / (1024 * 1024 * 1024)
                return "存储空间：总共${total}GB，剩余${free}GB"
            }
            else -> return "支持的类型：battery（电量）, storage（存储）"
        }
    }
}
