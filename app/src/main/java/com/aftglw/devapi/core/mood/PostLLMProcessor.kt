package com.aftglw.devapi.core.mood
import android.util.Log
import com.aftglw.devapi.core.affect.AffectSyncRetryQueue
import com.aftglw.devapi.core.affect.AffectiveEngine
import com.aftglw.devapi.core.memory.MemoryStore

import android.content.Context
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * LLM 回复后处理：AffectiveField 更新 + 记忆落盘 + 对话反思 + 对话优化 traits 合并。
 *
 * - AffectiveField：每轮调用 AffectiveEngine.update() 更新关系场 + 扫描 pending events（P0 新增）
 * - 反思：每轮（开关开启时）调用一次 LLM 分析最新一轮对话，输出 insight/emo 两条记忆
 *   保留最近 N 条历史轨迹（insight 5 条 / ai_emo 10 条），用 topic 后缀 _i/_e 区分代次
 * - traits：每 20 轮（在 ChatScreen 触发）调用 LLM 分析用户说话特点，与已有 traits 合并而非覆盖
 *
 * eventId 幂等键：由调用方（ChatScreen）在用户消息进入时生成，跨手机/Desktop/服务器去重
 * （设计文档 14.8）。未传入时自动生成 UUID，仅在单端运行时去重。
 */
object PostLLMProcessor {
    private const val INSIGHT_HISTORY = 5
    private const val AI_EMO_HISTORY = 10
    private val affectSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun process(
        ctx: Context,
        name: String,
        userMessage: String,
        aiReply: String,
        eventId: String = UUID.randomUUID().toString(),
    ) {
        // ⓪ AffectiveField 更新（P0 新增，设计文档第十四章 P0 最小闭环）
        // eventId 由调用方传入，保证跨端幂等
        try {
            val updateResult = AffectiveEngine.update(ctx, name, userMessage, aiReply, eventId)
            syncAffectiveSnapshot(ctx, name, eventId, updateResult)
        } catch (e: Exception) {
            Log.w("PostLLMProcessor", "AffectiveEngine.update failed", e)
            // 非关键：AffectiveField 失败不阻塞后续处理
        }

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
    /**
     * Sends a snapshot only when the user explicitly enabled the local
     * Desktop bridge. It never falls back to the configured AI API URL.
     */
    private fun syncAffectiveSnapshot(
        ctx: Context,
        chatName: String,
        eventId: String,
        result: AffectiveEngine.UpdateResult,
    ) {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wisp_affect_sync_enabled", false)) return
        val syncUrl = prefs.getString("wisp_affect_sync_url", "")?.trimEnd('/') ?: return
        if (!isSafeDesktopUrl(syncUrl)) {
            Log.w("PostLLMProcessor", "Affective sync blocked: Desktop URL is not a local Wisp address")
            return
        }

        affectSyncScope.launch {
            // P1.5: bodyJson/token 提到 try 外，使 catch 块在请求阶段失败时仍可入队重试
            // bodyJson 为 null 表示快照构建阶段就失败，无可重试内容，跳过入队
            var bodyJson: String? = null
            var token: String = ""
            try {
                val snapshot = AffectiveEngine.snapshot(ctx, chatName)
                val body = JSONObject().apply {
                    put("chatName", chatName)
                    put("eventId", eventId)
                    put("source", "android")
                    put("affectiveField", JSONObject().apply {
                        put("tension", snapshot.field.tension)
                        put("warmth", snapshot.field.warmth)
                        put("anticipation", snapshot.field.anticipation)
                        put("drift", snapshot.field.drift)
                        put("lastUpdatedTs", snapshot.field.lastUpdatedTs)
                    })
                    put("rhythmProfile", JSONObject().apply {
                        put("sampleCount", snapshot.rhythmProfile.sampleCount)
                        put("latencyPercentile", snapshot.rhythmProfile.latencyPercentile)
                        put("lengthPercentile", snapshot.rhythmProfile.lengthPercentile)
                        put("latencyTrend", snapshot.rhythmProfile.latencyTrend)
                        put("lengthTrend", snapshot.rhythmProfile.lengthTrend)
                        put("initiativeRate7d", snapshot.rhythmProfile.initiativeRate7d)
                    })
                    put("stateHint", JSONObject().apply {
                        put("observation", snapshot.stateHint.observation)
                        put("hypothesis", snapshot.stateHint.hypothesis)
                        put("actionSuggestion", snapshot.stateHint.actionSuggestion)
                        // 风险 2 修复：上报结构化 signals，供 Desktop 调度逻辑使用
                        put("signals", JSONArray().apply {
                            snapshot.stateHint.signals.forEach { put(it.name) }
                        })
                    })
                    put("pendingEvents", pendingEventsJson(snapshot.activePendingEvents))
                    put("closureCandidates", pendingEventsJson(snapshot.closureCandidates))
                    put("responseAssessment", JSONObject().apply {
                        put("userDisclosed", result.assessment.userDisclosed)
                        put("userAskedQuestion", result.assessment.userAskedQuestion)
                        put("userSharedPositive", result.assessment.userSharedPositive)
                        put("aiRespondedToEmotion", result.assessment.aiRespondedToEmotion)
                        put("aiAnsweredContent", result.assessment.aiAnsweredContent)
                        put("aiCelebrated", result.assessment.aiCelebrated)
                        put("warmthDelta", result.assessment.warmthDelta)
                        put("shouldCreatePending", result.assessment.shouldCreatePending)
                        put("pendingSummary", result.assessment.pendingSummary ?: "")
                        put("pendingClosureType", result.assessment.pendingClosureType?.name ?: "")
                    })
                }
                bodyJson = body.toString()
                token = prefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
                // 修复 P2.4: 用 bodyStr 局部变量替代 bodyJson!!（消除 !! warning）
                val bodyStr = body.toString()
                val request = if (token.isBlank()) {
                    HttpClient.postJson(
                        "$syncUrl/api/v1/debug/affect/snapshot",
                        bodyStr,
                        "X-Wisp-Source" to "android",
                    )
                } else {
                    HttpClient.postJson(
                        "$syncUrl/api/v1/debug/affect/snapshot",
                        bodyStr,
                        "Authorization" to "Bearer $token",
                        "X-Wisp-Source" to "android",
                    )
                }
                HttpClient.clientFor(syncUrl).newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // P2.4 修复: 统计首次同步成功
                        AffectSyncRetryQueue.recordFirstSyncSuccess()
                    } else {
                        Log.w("PostLLMProcessor", "Affective sync returned HTTP ${response.code}")
                        val retryable = AffectSyncRetryQueue.isRetryableStatus(response.code)
                        // P2.4 修复: 统计首次同步失败
                        AffectSyncRetryQueue.recordFirstSyncFailure(response.code, "HTTP ${response.code}", retryable)
                        // P1.5: 失败入队重试（指数退避 10s→30s→90s，最多 3 次）
                        // 状态码分类：仅可重试错误（408/425/429/5xx）入队，
                        // 永久错误（400/401/403/404 等）直接丢弃，避免无谓重试
                        if (retryable) {
                            // 修复 P2.4: 用 bodyStr 替代 bodyJson!!（消除 !! warning）
                            AffectSyncRetryQueue.enqueue(bodyStr, syncUrl, token, attempts = 0)
                        } else {
                            Log.w("PostLLMProcessor", "Affective sync HTTP ${response.code} is permanent, not enqueuing retry")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PostLLMProcessor", "Affective sync failed: ${e.message}")
                // P2.4 修复: 统计首次同步失败（网络异常，默认可重试）
                AffectSyncRetryQueue.recordFirstSyncFailure(0, "network: ${e.javaClass.simpleName}", retryable = true)
                // P1.5: 网络异常默认可重试 — 仅在 body 已构建成功（请求阶段失败）时入队
                val body = bodyJson
                if (body != null) {
                    AffectSyncRetryQueue.enqueue(body, syncUrl, token, attempts = 0)
                }
            }
        }
    }

    private fun isSafeDesktopUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase() ?: return false
            val port = uri.port
            val localHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
            val privateV4 = host.startsWith("10.") || host.startsWith("192.168.") ||
                (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
            val privateV6 = host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
            uri.scheme in listOf("http", "https") && port in 17890..17909 && (localHost || privateV4 || privateV6)
        } catch (_: Exception) {
            false
        }
    }

    private fun pendingEventsJson(events: List<com.aftglw.devapi.core.affect.PendingEvent>): JSONArray {
        return JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("summary", event.summary)
                    put("triggerText", event.triggerText)
                    put("weight", event.weight)
                    put("closureType", event.closureType.name)
                    put("attemptCount", event.attemptCount)
                    put("lastAttemptAt", event.lastAttemptAt ?: JSONObject.NULL)
                    put("createdAt", event.createdAt)
                    put("resolved", event.resolved)
                    put("archived", event.archived)
                    put("staleness", event.staleness())
                })
            }
        }
    }

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
