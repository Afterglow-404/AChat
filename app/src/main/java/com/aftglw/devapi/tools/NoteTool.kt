package com.aftglw.devapi.tools

import android.content.Context
import com.aftglw.devapi.MemoryStore

class NoteTool : AiTool {
    override val name = "note"
    override val description = "记一条笔记或信息，后续可以回忆。参数：text=要记的内容"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        val text = args["text"] ?: args["content"] ?: return "缺少内容"
        MemoryStore.save(ctx, text, "note")
        return "已记住：$text"
    }
}
