package com.aftglw.devapi.ui.screens

import android.content.Context
import com.aftglw.devapi.MemoryStore
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PostLLMProcessor {
    fun process(ctx: Context, name: String, userMessage: String, aiReply: String) {
        // ① 存用户输入 → 轻量上下文记忆
        MemoryStore.save(ctx, userMessage, "turn:$name")

        // ② 对话反思（需开启）
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("reflection_$name", false)) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prompt = "分析这段对话的本质。用户说：${userMessage.take(80)}。AI说：${aiReply.take(80)}。用一句话概括对话的核心（20字内，不要评价）："
                    val insight = AiServiceFactory.getService()
                        .sendMessage(emptyList(), prompt, "你是对话分析师。只输出概括，不要多余内容。")
                    if (!insight.isNullOrBlank()) MemoryStore.save(ctx, insight.trim(), "insight:$name")
                } catch (_: Exception) {} /* 非关键 */
            }
        }

        // ③ 存 AI 回复 → 后续关键词事实提取的素材
        MemoryStore.save(ctx, aiReply.take(100), "reply:$name")
    }
}
