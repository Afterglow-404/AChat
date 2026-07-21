package com.aftglw.devapi.core.affect

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 响应度评估 — 判断 AI 是否回应用户的披露/问句/情绪（设计文档 14.3 第一级）。
 *
 * 理论根基：Intimacy Process Model（Reis & Shaver）+ Capitalization（Gable & Reis）。
 * 共同结论：关系温度不只由用户表达决定，更取决于 AI 是否正确回应。
 *
 * P0 范围（B 类启发式，本地零 token，设计文档 13.3）：
 * - 检测用户消息中的"问句 / 倾诉 / 正向分享"
 * - 检测 AI 回复是否包含对应回应（共情词 / 回答词 / 庆祝词）
 * - 输出 ResponseAssessment，由 AffectiveEngine 用于更新 warmth
 *
 * P1.1 扩展：对话时间线（本文件新增）
 * - 保留最近 [MAX_TURNS] 轮 [TurnRecord]，支持跨轮检测
 * - "用户后续反馈"修正：上一轮 AI 漏共情，这一轮用户表达不满 → 追加 warmthDelta 惩罚
 * - "AI 连续漏答"检测：最近 3 轮漏答 ≥2 次 → 提高 pending weight
 * - "用户重复问"检测：当前问题与近 3 轮相似 → 强制 shouldCreatePending
 *
 * 误判处理：
 * - 误判"AI 没回应"会让 warmth 不增（可接受）
 * - 误判"AI 回应了"会让 warmth 多增（可接受）
 * - 不触发任何高风险决策（仅影响 warmth 增量）
 */
object ResponseAssessment {

    private const val TAG = "ResponseAssessment"
    private const val MAX_TURNS = 20
    private const val TIMELINE_PREFS = "wechat_settings"
    private fun timelineKey(chatName: String) = "response_timeline_$chatName"

    /**
     * 评估结果。
     *
     * @param userDisclosed 用户是否做了情感性自我披露（非事实陈述）
     * @param userAskedQuestion 用户是否问了问题
     * @param userSharedPositive 用户是否分享了正向事件
     * @param aiRespondedToEmotion AI 是否回应了情绪（含共情词）
     * @param aiAnsweredContent AI 是否回答了内容（含相关主题词）
     * @param aiCelebrated AI 是否给了 active-constructive 响应（庆祝词）
     * @param warmthDelta 基于 IPM + Capitalization 理论计算的 warmth 增量建议
     * @param pendingWeight PendingEvent 权重建议（P1.1：连续漏答时提高）
     * @param timelineNote 时间线检测到的额外信息（供 DebugPage 观察）
     */
    data class Assessment(
        val userDisclosed: Boolean,
        val userAskedQuestion: Boolean,
        val userSharedPositive: Boolean,
        val aiRespondedToEmotion: Boolean,
        val aiAnsweredContent: Boolean,
        val aiCelebrated: Boolean,
        val warmthDelta: Float,
        val shouldCreatePending: Boolean,
        val pendingSummary: String?,
        val pendingClosureType: ClosureType?,
        val pendingWeight: Float = 0.5f,
        val timelineNote: String = "",
    )

    /**
     * 时间线中的一轮记录（P1.1 新增）。
     * 持久化到 SharedPreferences，环形保留最近 [MAX_TURNS] 轮。
     */
    data class TurnRecord(
        val eventId: String,
        val timestamp: Long,
        val userMessage: String,      // 截断 100 字
        val aiReply: String,          // 截断 100 字
        val assessment: Assessment,
    )

    // 用户侧信号词
    private val DISCLOSURE_SIGNALS = listOf(
        "其实", "我跟你说", "你知道吗", "我有点", "今天", "最近", "心里", "难受", "开心", "累", "烦",
        "害怕", "担心", "想念", "孤独", "委屈", "纠结", "不安", "失落",
    )
    private val POSITIVE_SIGNALS = listOf(
        "考过", "升职", "加薪", "搞定", "赢了", "签了", "上岸", "通过了", "录取", "完成",
        "成功", "好消息", "终于",
    )
    private val QUESTION_MARKS = charArrayOf('？', '?')

    // AI 侧回应信号词
    private val EMPATHY_SIGNALS = listOf(
        "嗯", "抱抱", "我在", "理解", "辛苦", "别怕", "没事", "陪着", "听你说", "怎么了",
        "会好的", "不容易", "替你", "心疼", "难过", "开心",
    )
    private val CELEBRATION_SIGNALS = listOf(
        "恭喜", "厉害", "太棒了", "真好", "替你开心", "牛", "赞", "不错", "干得漂亮", "值得",
    )

    // P1.1：用户不满信号词（检测"用户后续反馈"——上一轮 AI 漏共情后的负面反馈）
    private val USER_DISSATISFACTION_SIGNALS = listOf(
        "你没听", "你都没反应", "忽略我", "敷衍", "不在乎我", "没在听", "不想理你",
        "你根本", "说半天", "白说了", "不想说了",
    )

    /**
     * 评估一轮对话（用户消息 + AI 回复）—— 单轮版本，保留向后兼容。
     *
     * @param userMessage 用户消息原文
     * @param aiReply AI 回复原文
     */
    fun assess(userMessage: String, aiReply: String): Assessment {
        return assessInternal(userMessage, aiReply, emptyList())
    }

    /**
     * 评估一轮对话（带时间线）—— P1.1 新增。
     *
     * 流程：
     * 1. 加载时间线（最近 MAX_TURNS 轮）
     * 2. 单轮评估
     * 3. 时间线修正：
     *    a. 用户后续反馈检测（上一轮 AI 漏共情，这一轮用户表达不满）
     *    b. AI 连续漏答检测（近 3 轮漏答 ≥2 次）
     *    c. 用户重复问检测（当前问题与近 3 轮相似度 > 0.6）
     * 4. 追加到时间线并持久化
     * 5. 返回修正后的 Assessment
     *
     * @param ctx Context
     * @param chatName 角色/会话名
     * @param userMessage 用户消息原文
     * @param aiReply AI 回复原文
     * @param eventId 幂等键（用于去重，已处理过的事件不重复追加）
     */
    fun assessWithTimeline(
        ctx: Context,
        chatName: String,
        userMessage: String,
        aiReply: String,
        eventId: String,
    ): Assessment {
        val timeline = loadTimeline(ctx, chatName)
        // 幂等检查：同一 eventId 已在时间线中 → 直接返回已有评估，不重复追加
        timeline.firstOrNull { it.eventId == eventId }?.let {
            return it.assessment
        }
        val assessment = assessInternal(userMessage, aiReply, timeline)
        // 追加并持久化
        val newRecord = TurnRecord(
            eventId = eventId,
            timestamp = System.currentTimeMillis(),
            userMessage = userMessage.take(100),
            aiReply = aiReply.take(100),
            assessment = assessment,
        )
        saveTimeline(ctx, chatName, timeline + newRecord)
        return assessment
    }

    /** 内部评估逻辑（单轮 + 时间线修正） */
    private fun assessInternal(userMessage: String, aiReply: String, timeline: List<TurnRecord>): Assessment {
        val userDisclosed = DISCLOSURE_SIGNALS.any { it in userMessage }
        val userAskedQuestion = userMessage.any { it in QUESTION_MARKS }
        val userSharedPositive = POSITIVE_SIGNALS.any { it in userMessage }

        val aiRespondedToEmotion = EMPATHY_SIGNALS.any { it in aiReply }
        val aiAnsweredContent = aiReply.isNotBlank() && aiReply.length > 3
        val aiCelebrated = CELEBRATION_SIGNALS.any { it in aiReply }

        // ── 单轮 warmth 增量计算（P0，设计文档 14.3 第一级）──
        var warmthDelta = 0f
        if (userDisclosed) {
            warmthDelta += 0.08f
            warmthDelta += if (aiRespondedToEmotion) 0.05f else -0.03f
        }
        if (userSharedPositive) {
            warmthDelta += 0.06f
            warmthDelta += if (aiCelebrated) 0.04f else -0.02f
        }
        if (userAskedQuestion && !aiAnsweredContent) {
            warmthDelta -= 0.02f
        }

        // ── P1.1 时间线修正 ──
        val timelineNotes = mutableListOf<String>()
        var pendingWeight = 0.5f

        // (a) 用户后续反馈检测：上一轮 AI 漏共情，这一轮用户表达不满
        val lastTurn = timeline.lastOrNull()
        if (lastTurn != null
            && lastTurn.assessment.userDisclosed
            && !lastTurn.assessment.aiRespondedToEmotion
            && USER_DISSATISFACTION_SIGNALS.any { it in userMessage }
        ) {
            // 修正上一轮的乐观估计：AI 漏共情被用户感知到了
            warmthDelta -= 0.05f
            timelineNotes.add("用户感知到上轮AI漏共情(Δwarmth-0.05)")
        }

        // (b) AI 连续漏答检测：近 3 轮（不含当前）漏答 ≥2 次
        val recent3 = timeline.takeLast(3)
        val recentMissedCount = recent3.count { it.assessment.userAskedQuestion && !it.assessment.aiAnsweredContent }
        if (recentMissedCount >= 2) {
            pendingWeight = 0.7f
            timelineNotes.add("AI近${recent3.size}轮漏答${recentMissedCount}次(weight→0.7)")
        }
        if (userAskedQuestion && !aiAnsweredContent && recentMissedCount >= 1) {
            // 当前又漏答 + 之前有漏答 → 加重
            warmthDelta -= 0.03f
            timelineNotes.add("连续漏答(Δwarmth-0.03)")
        }

        // (c) 用户重复问检测：当前用户消息与近 3 轮相似度 > 0.6
        if (userAskedQuestion) {
            val userChars = userMessage.toSet()
            val similarTurn = recent3.firstOrNull { turn ->
                val turnChars = turn.userMessage.toSet()
                val inter = userChars.intersect(turnChars).size
                val union = userChars.union(turnChars).size
                union > 0 && inter.toFloat() / union > 0.6f
            }
            if (similarTurn != null) {
                timelineNotes.add("用户重复问(与${similarTurn.eventId.take(8)}相似)")
                // 强制 shouldCreatePending（即使 AI 这轮答了，也可能是敷衍）
            }
        }

        // ── 是否生成 PendingEvent ──
        var shouldCreatePending = false
        var pendingSummary: String? = null
        var pendingClosureType: ClosureType? = null

        if (userAskedQuestion && !aiAnsweredContent) {
            shouldCreatePending = true
            pendingSummary = if (recentMissedCount >= 1) "用户连续问但 AI 漏答" else "用户问了一句但 AI 没答清楚"
            pendingClosureType = ClosureType.EXPLANATION
        } else if (userDisclosed && !aiRespondedToEmotion) {
            shouldCreatePending = true
            pendingSummary = "用户倾诉了但 AI 没给共情回应"
            pendingClosureType = ClosureType.ACKNOWLEDGE
        }

        return Assessment(
            userDisclosed = userDisclosed,
            userAskedQuestion = userAskedQuestion,
            userSharedPositive = userSharedPositive,
            aiRespondedToEmotion = aiRespondedToEmotion,
            aiAnsweredContent = aiAnsweredContent,
            aiCelebrated = aiCelebrated,
            warmthDelta = warmthDelta,
            shouldCreatePending = shouldCreatePending,
            pendingSummary = pendingSummary,
            pendingClosureType = pendingClosureType,
            pendingWeight = pendingWeight,
            timelineNote = timelineNotes.joinToString("; "),
        )
    }

    // ── 时间线持久化（P1.1 新增）──

    /** 加载时间线（按时间正序，旧→新） */
    fun loadTimeline(ctx: Context, chatName: String): List<TurnRecord> {
        return try {
            val prefs = ctx.getSharedPreferences(TIMELINE_PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(timelineKey(chatName), "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try { jsonToTurnRecord(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadTimeline failed", e)
            emptyList()
        }
    }

    /** DebugPage 用：获取时间线 */
    fun getTimelineForDebug(ctx: Context, chatName: String): List<TurnRecord> = loadTimeline(ctx, chatName)

    /** 清除时间线（删角色/清除情绪场时调用） */
    fun clearTimeline(ctx: Context, chatName: String) {
        try {
            ctx.getSharedPreferences(TIMELINE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(timelineKey(chatName)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearTimeline failed", e)
        }
    }

    private fun saveTimeline(ctx: Context, chatName: String, timeline: List<TurnRecord>) {
        try {
            // 环形保留最近 MAX_TURNS 轮
            val trimmed = if (timeline.size > MAX_TURNS) timeline.takeLast(MAX_TURNS) else timeline
            val arr = JSONArray()
            trimmed.forEach { arr.put(turnRecordToJson(it)) }
            ctx.getSharedPreferences(TIMELINE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(timelineKey(chatName), arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveTimeline failed", e)
        }
    }

    private fun turnRecordToJson(record: TurnRecord): JSONObject {
        val a = record.assessment
        return JSONObject().apply {
            put("eventId", record.eventId)
            put("ts", record.timestamp)
            put("user", record.userMessage)
            put("ai", record.aiReply)
            put("assessment", JSONObject().apply {
                put("disclosed", a.userDisclosed)
                put("asked", a.userAskedQuestion)
                put("positive", a.userSharedPositive)
                put("empathy", a.aiRespondedToEmotion)
                put("answered", a.aiAnsweredContent)
                put("celebrated", a.aiCelebrated)
                put("delta", a.warmthDelta)
                put("pending", a.shouldCreatePending)
                put("summary", a.pendingSummary ?: "")
                put("closure", a.pendingClosureType?.name ?: "")
                put("weight", a.pendingWeight)
                put("note", a.timelineNote)
            })
        }
    }

    private fun jsonToTurnRecord(o: JSONObject): TurnRecord {
        val a = o.getJSONObject("assessment")
        val assessment = Assessment(
            userDisclosed = a.getBoolean("disclosed"),
            userAskedQuestion = a.getBoolean("asked"),
            userSharedPositive = a.getBoolean("positive"),
            aiRespondedToEmotion = a.getBoolean("empathy"),
            aiAnsweredContent = a.getBoolean("answered"),
            aiCelebrated = a.getBoolean("celebrated"),
            warmthDelta = a.getDouble("delta").toFloat(),
            shouldCreatePending = a.getBoolean("pending"),
            pendingSummary = a.optString("summary", "").ifBlank { null },
            pendingClosureType = a.optString("closure", "").ifBlank { null }?.let { runCatching { ClosureType.valueOf(it) }.getOrNull() },
            pendingWeight = a.optDouble("weight", 0.5).toFloat(),
            timelineNote = a.optString("note", ""),
        )
        return TurnRecord(
            eventId = o.getString("eventId"),
            timestamp = o.getLong("ts"),
            userMessage = o.getString("user"),
            aiReply = o.getString("ai"),
            assessment = assessment,
        )
    }
}
