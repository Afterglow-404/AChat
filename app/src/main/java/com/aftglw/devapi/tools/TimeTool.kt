package com.aftglw.devapi.tools

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

class TimeTool : AiTool {
    override val name = "time"
    override val description = "获取当前的精确日期和时间"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy年M月d日 HH:mm:ss EEEE", Locale.CHINESE)
        val battery = args["battery"] // 如果传了 battery 参数，也返回电量信息
        val timeStr = "现在是${sdf.format(Date(now))}"
        return if (battery != null) "$timeStr，当时电量约${battery}%" else timeStr
    }
}
