package com.aftglw.devapi.core.tools

import android.content.Context
import com.aftglw.devapi.tools.AiTool
import org.json.JSONObject
import java.util.UUID

/**
 * UUID 生成工具 — 生成 UUID v4 随机字符串。
 * 通过 .wsptool 描述文件注册为动态工具。
 */
class UuidTool : AiTool {
    override val name: String get() = "generate_uuid"
    override val description: String get() = "生成 UUID v4 随机字符串"
    override val inputSchema: JSONObject get() = JSONObject().apply {
        put("type", "object"); put("properties", JSONObject())
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        return UUID.randomUUID().toString()
    }
}
