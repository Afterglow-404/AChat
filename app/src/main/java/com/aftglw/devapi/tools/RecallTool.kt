package com.aftglw.devapi.tools
import com.aftglw.devapi.core.memory.MemoryStore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RecallTool : AiTool {
    override val name = "recall"
    override val description = "回忆之前记过的笔记或对话内容，支持搜索关键词"

    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("q", JSONObject().apply {
                put("type", "string")
                put("description", "要搜索的关键词，越精确结果越好")
            })
            put("limit", JSONObject().apply {
                put("type", "number")
                put("description", "返回结果数量上限（默认 3，最多 10）")
            })
        })
        val req = JSONArray()
        req.put("q")
        put("required", req)
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val query = args.optString("q", "").ifEmpty { return "请提供要搜索的内容" }
        val limit = args.optInt("limit", 3).coerceIn(1, 10)
        val results = MemoryStore.search(ctx, query, limit)
        return if (results.isEmpty()) "没有找到与「${query}」相关的记忆"
        else results.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.text}" }
    }
}
