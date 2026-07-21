package com.aftglw.devapi.core.affect

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 韵律感知器 — 从对话节奏捕捉用户状态（设计文档 2.1）。
 *
 * 重要约束（设计文档 14.4.3）：
 * RhythmSensor 是**非诊断性的、低置信度行为启发式**。
 * 它的价值不在于准确识别用户心理，而在于帮助 Wisp 选择更低负担、更少施压的回应策略。
 *
 * 三层分离（绝不混层）：
 * 1. 观测层 — 可直接测量的行为信号（客观事实）
 * 2. 启发式解释层 — 对观测的可能解释（低置信度假设，含替代解释）
 * 3. 行为建议层 — AI 应采取的策略（保守、低风险、不施压）
 *
 * 三条工程约束：
 * - R-S1：至少组合两个以上信号（latency 单独不能触发）
 * - R-S2：stateHint 使用概率和替代解释（不使用确定性判断）
 * - R-S3：只能影响低风险行为（不触发关系降温/依恋损伤/孤独风险等）
 *
 * 存储：SharedPreferences `rhythm_$chatName`，JSON 数组保留最近 100 条样本
 */
object RhythmSensor {

    private const val MAX_SAMPLES = 100
    private const val MIN_SAMPLES_FOR_TREND = 20
    private const val TAG = "RhythmSensor"

    /**
     * 单条韵律样本（设计文档 2.1.2）。
     * 每次用户发送消息时记录一条。
     */
    data class RhythmSample(
        val timestamp: Long,
        val replyLatencyMs: Long,      // 距上一条消息间隔（首次发话题 = 0）
        val lengthChars: Int,
        val exclamCount: Int,          // ！数量
        val questionCount: Int,        // ？数量
        val ellipsisCount: Int,        // …/... 数量
        val tildeCount: Int,           // ～/~ 数量
        val isInitiative: Boolean,     // 是否主动开话题（距上一条 > 30min）
        val hourOfDay: Int,            // 0-23
    )

    /**
     * 派生指标（设计文档 2.1.3，简化版）。
     * P0 不含 Polyvagal 三态（已降级为实验性假设，见 14.4.1）。
     */
    data class RhythmProfile(
        val sampleCount: Int,
        val latencyPercentile: Float,    // 0-1，1 = 比自己 99% 时候都慢
        val lengthPercentile: Float,     // 0-1，1 = 比自己 99% 时候都长
        val latencyTrend: Float,         // -1 加速 → +1 减速
        val lengthTrend: Float,          // -1 变短 → +1 变长
        val initiativeRate7d: Float,     // 最近 7 天主动开话题比例
    )

    /**
     * 三层分离的 stateHint（设计文档 14.4.3）。
     *
     * 注入 PromptBuilder 时必须三层完整，绝不只注入解释层（避免 LLM 把假设当事实）。
     * 当所有层都为空时返回 null（不注入）。
     *
     * **风险 2 修复**：添加 [signals] 结构化字段，供调度逻辑（dryRunScan 等）使用，
     * 避免依赖 [observation] 中文文案匹配。文案变化不影响调度。
     */
    data class StateHint(
        val observation: String,         // 观测层：客观事实（展示用，调度不应依赖）
        val hypothesis: String,          // 启发式解释层：可能解释（含"可能"和替代解释）
        val actionSuggestion: String,    // 行为建议层：保守、低风险、不施压的策略
        val signals: Set<RhythmSignal> = emptySet(),  // 结构化信号（调度用，风险 2 修复）
    ) {
        /** 是否非空（至少有一层有内容） */
        fun isNonEmpty(): Boolean = observation.isNotBlank() || hypothesis.isNotBlank() || actionSuggestion.isNotBlank()

        /** 注入 PromptBuilder 的格式（signals 不注入 prompt，只用于调度） */
        fun toPromptBlock(): String {
            if (!isNonEmpty()) return ""
            return buildString {
                append("\n\n【对方状态】")
                if (observation.isNotBlank()) append("\n观测：$observation")
                if (hypothesis.isNotBlank()) append("\n可能：$hypothesis")
                if (actionSuggestion.isNotBlank()) append("\n建议：$actionSuggestion")
            }
        }
    }

    /**
     * 结构化韵律信号（风险 2 修复）。
     *
     * 调度逻辑（dryRunScan / ProactiveScheduler）应使用这些枚举值，
     * 而非匹配 [StateHint.observation] 的中文文案。
     * 文案可能因 UX 调整而变化，但枚举值稳定。
     */
    enum class RhythmSignal {
        /** 回复变慢（latencyPercentile > 0.8） */
        LATENCY_INCREASING,

        /** 回复变短（lengthPercentile < 0.3） */
        LENGTH_DECREASING,

        /** 主动开话题减少（initiativeRate7d < 0.2） */
        INITIATIVE_DROPPING,

        /** 回复变长（lengthPercentile > 0.7） */
        LENGTH_INCREASING,

        /** 主动开话题增多（initiativeRate7d > 0.4） */
        INITIATIVE_RISING,

        /** 回复加速（latencyPercentile < 0.3） */
        LATENCY_DECREASING,
    }

    // ── 采样 ──

    /**
     * 记录一条用户消息样本。
     * @param prevUserMsgTs 上一条用户消息的时间戳（首次为 0）
     */
    fun recordSample(
        ctx: Context,
        chatName: String,
        text: String,
        prevUserMsgTs: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        try {
            val latency = if (prevUserMsgTs > 0) (now - prevUserMsgTs).coerceAtLeast(0L) else 0L
            val sample = RhythmSample(
                timestamp = now,
                replyLatencyMs = latency,
                lengthChars = text.length,
                exclamCount = countChar(text, '！', '!'),
                questionCount = countChar(text, '？', '?'),
                ellipsisCount = countEllipsis(text),
                tildeCount = countChar(text, '～', '~'),
                isInitiative = latency > 30 * 60 * 1000L,
                hourOfDay = ((now / 3_600_000L) % 24).toInt(),
            )
            val samples = loadSamples(ctx, chatName).toMutableList()
            samples.add(sample)
            while (samples.size > MAX_SAMPLES) samples.removeAt(0)
            saveSamples(ctx, chatName, samples)
        } catch (e: Exception) {
            Log.w(TAG, "recordSample failed", e)
        }
    }

    // ── 计算 RhythmProfile ──

    /**
     * 计算 RhythmProfile。
     * 样本不足 20 条时 trend 返回 0（不计算趋势）。
     */
    fun computeProfile(ctx: Context, chatName: String, now: Long = System.currentTimeMillis()): RhythmProfile {
        val samples = loadSamples(ctx, chatName)
        if (samples.isEmpty()) {
            return RhythmProfile(0, 0.5f, 0.5f, 0f, 0f, 0f)
        }
        val latest = samples.last()
        val latencyPercentile = if (samples.size >= 2) {
            samples.count { it.replyLatencyMs < latest.replyLatencyMs }.toFloat() / samples.size
        } else 0.5f
        val lengthPercentile = if (samples.size >= 2) {
            samples.count { it.lengthChars < latest.lengthChars }.toFloat() / samples.size
        } else 0.5f

        val latencyTrend = if (samples.size >= MIN_SAMPLES_FOR_TREND) {
            val recentAvg = samples.takeLast(10).map { it.replyLatencyMs }.average().toFloat()
            val baselineAvg = samples.map { it.replyLatencyMs }.average().toFloat()
            val denom = maxOf(baselineAvg, 5000f)
            ((recentAvg - baselineAvg) / denom).coerceIn(-1f, 1f)
        } else 0f

        val lengthTrend = if (samples.size >= MIN_SAMPLES_FOR_TREND) {
            val recentAvg = samples.takeLast(10).map { it.lengthChars.toFloat() }.average().toFloat()
            val baselineAvg = samples.map { it.lengthChars.toFloat() }.average().toFloat()
            val denom = maxOf(baselineAvg, 10f)
            ((recentAvg - baselineAvg) / denom).coerceIn(-1f, 1f)
        } else 0f

        val sevenDaysAgo = now - 7 * 86_400_000L
        val recentSamples = samples.filter { it.timestamp >= sevenDaysAgo }
        val initiativeRate7d = if (recentSamples.isNotEmpty()) {
            recentSamples.count { it.isInitiative }.toFloat() / recentSamples.size
        } else 0f

        return RhythmProfile(
            sampleCount = samples.size,
            latencyPercentile = latencyPercentile,
            lengthPercentile = lengthPercentile,
            latencyTrend = latencyTrend,
            lengthTrend = lengthTrend,
            initiativeRate7d = initiativeRate7d,
        )
    }

    // ── 三层分离的 stateHint（核心，遵循 14.4.3）──

    /**
     * 生成三层分离的 stateHint。
     *
     * 约束 R-S1：至少组合两个以上信号（latency 单独不能触发）
     * 约束 R-S2：使用"可能"和替代解释
     * 约束 R-S3：只输出低风险行为建议
     *
     * 风险 2 修复：同时输出结构化 [RhythmSignal] 集合，供调度逻辑使用。
     */
    fun computeStateHint(profile: RhythmProfile): StateHint {
        if (profile.sampleCount < MIN_SAMPLES_FOR_TREND) {
            return StateHint("", "", "")  // 样本不足，不产生 hint
        }

        // 单信号判定（用于 signals 字段，R-S1 仍要求组合两个以上才产生 hint）
        val latencyIncreasing = profile.latencyPercentile > 0.8f
        val lengthDecreasing = profile.lengthPercentile < 0.3f
        val initiativeDropping = profile.initiativeRate7d < 0.2f
        val lengthIncreasing = profile.lengthPercentile > 0.7f
        val initiativeRising = profile.initiativeRate7d > 0.4f

        // 信号组合判定（R-S1：至少两个信号）
        val slowAndShort = latencyIncreasing && lengthDecreasing
        val slowAndLowInitiative = latencyIncreasing && initiativeDropping
        val shortAndLowInitiative = profile.lengthPercentile < 0.2f && initiativeDropping
        val longAndHighInitiative = lengthIncreasing && initiativeRising

        return when {
            // 用户回复变慢 + 变短 → 可能不方便深聊
            slowAndShort || slowAndLowInitiative || shortAndLowInitiative -> {
                val signals = buildSet {
                    if (latencyIncreasing) add(RhythmSignal.LATENCY_INCREASING)
                    if (lengthDecreasing || profile.lengthPercentile < 0.2f) add(RhythmSignal.LENGTH_DECREASING)
                    if (initiativeDropping) add(RhythmSignal.INITIATIVE_DROPPING)
                }
                StateHint(
                    observation = "用户最近回复变慢且变短" +
                        if (slowAndLowInitiative || shortAndLowInitiative) "，主动开话题也少了" else "",
                    hypothesis = "可能暂时不方便深入交流，也可能是疲惫、忙碌或不想聊天",
                    actionSuggestion = "回复保持简短温和，不连续追问，不要求用户立即回应",
                    signals = signals,
                )
            }
            // 用户回复长 + 主动率高 → 关系在升温
            longAndHighInitiative -> {
                StateHint(
                    observation = "用户最近消息变长且主动开话题增多",
                    hypothesis = "可能愿意深入交流，关系状态较好",
                    actionSuggestion = "可以更自然地回应，适当延伸话题",
                    signals = setOf(RhythmSignal.LENGTH_INCREASING, RhythmSignal.INITIATIVE_RISING),
                )
            }
            else -> StateHint("", "", "")
        }
    }

    // ── 持久化 ──

    private fun prefsKey(chatName: String) = "rhythm_$chatName"

    private fun loadSamples(ctx: Context, chatName: String): List<RhythmSample> {
        return try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val json = prefs.getString(prefsKey(chatName), "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RhythmSample(
                    timestamp = o.getLong("ts"),
                    replyLatencyMs = o.getLong("latency"),
                    lengthChars = o.getInt("len"),
                    exclamCount = o.getInt("excl"),
                    questionCount = o.getInt("q"),
                    ellipsisCount = o.getInt("elli"),
                    tildeCount = o.getInt("tilde"),
                    isInitiative = o.getBoolean("init"),
                    hourOfDay = o.getInt("hour"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadSamples failed", e)
            emptyList()
        }
    }

    private fun saveSamples(ctx: Context, chatName: String, samples: List<RhythmSample>) {
        try {
            val arr = JSONArray()
            samples.forEach { s ->
                arr.put(JSONObject().apply {
                    put("ts", s.timestamp)
                    put("latency", s.replyLatencyMs)
                    put("len", s.lengthChars)
                    put("excl", s.exclamCount)
                    put("q", s.questionCount)
                    put("elli", s.ellipsisCount)
                    put("tilde", s.tildeCount)
                    put("init", s.isInitiative)
                    put("hour", s.hourOfDay)
                })
            }
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            prefs.edit().putString(prefsKey(chatName), arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveSamples failed", e)
        }
    }

    /** 获取上一条用户消息的时间戳（用于计算 replyLatencyMs） */
    fun getLastSampleTs(ctx: Context, chatName: String): Long {
        val samples = loadSamples(ctx, chatName)
        return samples.lastOrNull()?.timestamp ?: 0L
    }

    /** DebugPage 用：获取原始样本列表 */
    fun getSamplesForDebug(ctx: Context, chatName: String): List<RhythmSample> {
        return loadSamples(ctx, chatName)
    }

    // ── 工具 ──

    private fun countChar(text: String, vararg chars: Char): Int {
        var count = 0
        for (c in text) {
            if (c in chars) count++
        }
        return count
    }

    private fun countEllipsis(text: String): Int {
        // 中文省略号 … 或 …… 或英文 ...
        var count = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '…') {
                count++
                i++
                // 跳过连续的 …
                while (i < text.length && text[i] == '…') i++
            } else if (c == '.' && i + 2 < text.length && text[i + 1] == '.' && text[i + 2] == '.') {
                count++
                i += 3
            } else {
                i++
            }
        }
        return count
    }
}
