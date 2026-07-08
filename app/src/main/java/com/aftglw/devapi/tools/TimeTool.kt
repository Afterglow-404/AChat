package com.aftglw.devapi.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class TimeTool : AiTool {
    override val name = "time"
    override val description = "获取当前的精确日期和时间，含星期几和时段"

    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("format", JSONObject().apply {
                put("type", "string")
                put("description", "时间格式：full（完整）/ date（仅日期）/ time（仅时间），默认 full")
                val enumArr = JSONArray()
                enumArr.put("full"); enumArr.put("date"); enumArr.put("time")
                put("enum", enumArr)
            })
        })
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val now = System.currentTimeMillis()
        val formatStr = args.optString("format", "full")
        return when (formatStr) {
            "date" -> SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINESE).format(Date(now))
            "time" -> SimpleDateFormat("HH:mm:ss", Locale.CHINESE).format(Date(now))
            else -> SimpleDateFormat("yyyy年M月d日 HH:mm:ss EEEE", Locale.CHINESE).format(Date(now))
        }
    }
}
