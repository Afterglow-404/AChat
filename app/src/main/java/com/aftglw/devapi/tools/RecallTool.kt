package com.aftglw.devapi.tools

import android.content.Context
import com.aftglw.devapi.MemoryStore

class RecallTool : AiTool {
    override val name = "recall"
    override val description = "回忆之前记过的笔记或对话内容。参数：q=要搜索的内容"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        val query = args["q"] ?: args["query"] ?: return "请提供要搜索的内容"
        val results = MemoryStore.search(ctx, query, 3)
        return if (results.isEmpty()) "没有找到相关记忆"
        else results.joinToString("\n") { "- ${it.text}" }
    }
}
