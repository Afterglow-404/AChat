package com.aftglw.devapi.core.mood
import android.util.Log
import com.aftglw.devapi.core.memory.MemoryStore

import android.content.Context
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PostLLMProcessor {
    suspend fun process(ctx: Context, name: String, userMessage: String, aiReply: String) {
        // ① 存用户输入 → 轻量上下文记忆
        MemoryStore.save(ctx, userMessage, "turn:$name")

        // ② 存 AI 回复 → 后续关键词事实提取的素材
        MemoryStore.save(ctx, aiReply.take(100), "reply:$name")

        // ③ 对话反思（需开启）：合并为单次 LLM 调用，一次输出 insight + ai_emo，省一半配额
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("reflection_$name", false)) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prompt = buildString {
                    append("分析这段对话。\n用户说：${userMessage.take(80)}\nAI说：${aiReply.take(80)}\n\n")
                    append("按以下格式输出，不要多余内容：\n")
                    append("---INSIGHT---\n用一句话概括对话的核心（20字内，不要评价）\n")
                    append("---EMO---\n用一句话描述AI在回复时的情绪状态（15字内）")
                    }
                    val full = AiServiceFactory.getService()
                        .sendMessage(emptyList(), prompt, "你是对话分析师。只输出指定格式，不要多余内容。")
                    if (!full.isNullOrBlank()) {
                        val insightPart = full.split("---EMO---").getOrElse(0) { "" }.replace("---INSIGHT---", "").trim()
                        val emoPart = full.split("---EMO---").getOrElse(1) { "" }.trim()
                        if (insightPart.isNotBlank()) MemoryStore.save(ctx, insightPart.take(80), "insight:$name")
                        if (emoPart.isNotBlank()) MemoryStore.save(ctx, emoPart.take(40), "ai_emo:$name")
                    }
                } catch (e: Exception) { Log.w("PostLLMProcessor", "reflection failed", e) } /* 非关键 */
            }
        }
    }
}
