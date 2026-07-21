package com.aftglw.devapi.core.mood
import android.util.Log
import com.aftglw.devapi.core.memory.MemoryStore

import android.content.Context
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * LLM 回复后处理：记忆落盘 + 对话反思 + 对话优化 traits 合并。
 *
 * - 反思：每轮（开关开启时）调用一次 LLM 分析最新一轮对话，输出 insight/emo 两条记忆
 *   保留最近 N 条历史轨迹（insight 5 条 / ai_emo 10 条），用 topic 后缀 _i/_e 区分代次
 * - traits：每 20 轮（在 ChatScreen 触发）调用 LLM 分析用户说话特点，与已有 traits 合并而非覆盖
 */
object PostLLMProcessor {
    private const val INSIGHT_HISTORY = 5
    private const val AI_EMO_HISTORY = 10

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
                    // 取上一轮的 insight/ai_emo 作为上下文，让模型做"演化"而非"重复"
                    val lastInsight = MemoryStore.listRecentByTopic("insight:$name", 1).firstOrNull()?.text ?: ""
                    val lastEmo = MemoryStore.listRecentByTopic("ai_emo:$name", 1).firstOrNull()?.text ?: ""
                    val prompt = buildString {
                        append("分析下面这段刚发生的对话。\n")
                        append("用户说：${userMessage.take(100)}\n")
                        append("AI说：${aiReply.take(100)}\n\n")
                        if (lastInsight.isNotBlank() || lastEmo.isNotBlank()) {
                            append("上一轮的判断：\n")
                            if (lastInsight.isNotBlank()) append("  对话本质：$lastInsight\n")
                            if (lastEmo.isNotBlank()) append("  AI情绪：$lastEmo\n\n")
                            append("若本轮无显著变化，可沿用；若有变化，请更新。\n\n")
                        }
                        append("按以下格式严格输出，每段一行，不要多余内容、不要解释：\n")
                        append("---INSIGHT---\n")
                        append("用一句话概括这段对话的核心（≤25字，包含：事件/话题 + 双方关系变化 + 用户深层需求或关注点）\n")
                        append("---EMO---\n")
                        append("用一句话描述 AI 在回复时的情绪状态（≤15字，包含：情绪词 + 触发原因，如\"关心，因对方提到疲惫\"）")
                    }
                    val full = AiServiceFactory.getService()
                        .sendMessage(emptyList(), prompt, "你是对话分析师。只输出指定格式，每段一行，不要多余内容。")
                    if (!full.isNullOrBlank()) {
                        val insightPart = full.split("---EMO---").getOrElse(0) { "" }.replace("---INSIGHT---", "").trim()
                        val emoPart = full.split("---EMO---").getOrElse(1) { "" }.trim()
                        if (insightPart.isNotBlank()) saveWithHistory(ctx, insightPart.take(80), "insight:$name", INSIGHT_HISTORY)
                        if (emoPart.isNotBlank()) saveWithHistory(ctx, emoPart.take(40), "ai_emo:$name", AI_EMO_HISTORY)
                    }
                } catch (e: Exception) { Log.w("PostLLMProcessor", "reflection failed", e) } /* 非关键 */
            }
        }
    }

    /**
     * 对话优化：分析用户说话特点并与已有 traits 合并（而非覆盖）。
     * 由 ChatScreen 每 20 轮调用。
     * @return 新的 traits 文本（已截断 200 字），失败时返回 null
     */
    suspend fun refineTraits(ctx: Context, name: String, recentDialogue: String): String? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val existing = prefs.getString("persona_dialogue_traits_$name", "") ?: ""
        val prompt = buildString {
            append("分析下面这段对话，提取用户的说话特点。\n")
            append("对话：\n${recentDialogue.take(1500)}\n\n")
            if (existing.isNotBlank()) {
                append("已有的用户特点（请在此基础上补充或修正，不要简单重复）：\n$existing\n\n")
            }
            append("请从以下维度各输出一句简短描述（每条≤15字，不要分点号）：\n")
            append("语气偏好 / 常用词汇 / 话题倾向 / 回复长度 / 易沉默或生气的情境\n")
            append("输出格式：直接输出特点描述，每条一行，最多 5 行；不要解释、不要前缀。")
        }
        return try {
            val text = AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "你是对话分析师。只输出特点描述，每条一行。")
            if (text.isNullOrBlank()) null
            else {
                // 简单合并：新结果若与已有重合度 >70% 则保留更长的一方；否则拼接
                val merged = if (existing.isNotBlank() && similarity(existing, text) > 0.7f) {
                    if (text.length >= existing.length) text else existing
                } else {
                    if (existing.isNotBlank()) "$existing\n$text" else text
                }
                val trimmed = merged.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(5)
                    .joinToString("\n")
                    .take(200)
                prefs.edit().putString("persona_dialogue_traits_$name", trimmed).apply()
                trimmed
            }
        } catch (e: Exception) {
            Log.w("PostLLMProcessor", "refineTraits failed", e) /* 非关键 */
            null
        }
    }

    /**
     * 保留历史轨迹：新条目存入 topic，同时清理超出 [maxHistory] 的最旧条目。
     * 实现策略：用 listRecentByTopic 取全部，按 timestamp 倒序保留前 maxHistory-1 条，
     * 其余通过 deleteByText 删除；然后 save 新条目。
     */
    private suspend fun saveWithHistory(ctx: Context, text: String, topic: String, maxHistory: Int) {
        val history = MemoryStore.listRecentByTopic(topic, maxHistory)
        // 清理超出上限的旧条目（listRecentByTopic 已按 timestamp DESC，越后越旧）
        if (history.size >= maxHistory) {
            history.drop(maxHistory - 1).forEach { old ->
                try { MemoryStore.deleteByText(old.text, topic) } catch (_: Exception) { /* 忽略 */ }
            }
        }
        MemoryStore.save(ctx, text, topic)
    }

    /** 简单的字符 Jaccard 相似度，用于判断新 traits 是否与旧高度重合 */
    private fun similarity(a: String, b: String): Float {
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size
        val union = sa.union(sb).size
        return if (union == 0) 0f else inter.toFloat() / union
    }
}
