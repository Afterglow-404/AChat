package com.aftglw.devapi.core.tools

import android.content.Context
import com.aftglw.devapi.tools.AiTool
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class Base64Tool : AiTool {
    override val name: String get() = "encode_base64"
    override val description: String get() = "将文本编码为 Base64 格式"
    override val inputSchema: JSONObject get() = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要编码的文本")
            })
        })
        put("required", JSONArray().apply { put("text") })
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val text = args.optString("text", "")
        if (text.isBlank()) return "请提供要编码的文本"
        return Base64.getEncoder().encodeToString(text.toByteArray())
    }
}
