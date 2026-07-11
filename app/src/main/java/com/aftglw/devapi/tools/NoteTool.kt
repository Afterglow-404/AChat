package com.aftglw.devapi.tools
import com.aftglw.devapi.core.memory.MemoryStore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoteTool : AiTool {
    override val name = "note"
    override val description = "记一条笔记或信息，之后可以用 recall 工具回忆"

    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要记住的内容")
            })
            put("topic", JSONObject().apply {
                put("type", "string")
                put("description", "笔记主题标签（可选），如 工作/生活/灵感")
            })
        })
        val req = JSONArray()
        req.put("text")
        put("required", req)
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val text = args.optString("text", "").ifEmpty { return "请提供要记住的内容" }
        val topic = args.optString("topic", "note")
        MemoryStore.save(ctx, text, topic)
        return "已记住${topic}笔记：$text"
    }
}
