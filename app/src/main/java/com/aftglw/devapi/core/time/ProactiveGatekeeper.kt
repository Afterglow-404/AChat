package com.aftglw.devapi.core.time

import android.content.Context
import android.util.Log
import com.aftglw.devapi.core.affect.AffectiveEngine
import com.aftglw.devapi.core.affect.RhythmSensor
import org.json.JSONObject

/**
 * ProactiveScheduler 发送审批守门员（P1.2 新增）。
 *
 * 整合四类审批逻辑：
 * 0. **全局时间窗 DND**（P2.1 新增）：用户配置的睡眠时段（如 23:00-07:00）内全部拒绝
 * 1. **AffectiveField 审批**：复用 dryRunScan 的决策规则，确保实际发送与 dry-run 建议一致
 * 2. **动态冷却时间**：warmth 高的可以更频繁，warmth 低的冷却更长
 * 3. **临时免打扰**：tension 高时临时免打扰 N 小时（避免在关系紧张时施压）
 *
 * 设计原则：
 * - 守门员是"额外审批层"，不替代 runOnce 中已有的审批（proactive_enabled / silence / 配额等）
 * - 守门员的决策可解释（返回 reason 供 DebugPage 观察）
 * - 守门员失败不阻塞发送（fail-open，返回 allow + reason="gatekeeper error"）
 * - 每次 decide() 都会记录最近一次决策到 prefs，供 DebugPage 观察
 *
 * 设计文档第十四章 P1.2 + P2.1（时间窗 DND） + 14.4.3 R-S3（只能影响低风险行为）。
 */
object ProactiveGatekeeper {

    private const val TAG = "ProactiveGatekeeper"
    private const val PREFS = "wechat_settings"

    /**
     * 审批决策。
     *
     * @param allow 是否允许发送
     * @param reason 决策原因（供 DebugPage / 日志观察）
     * @param cooldownHours 建议的冷却时间（小时），仅当 allow=false 时有意义
     */
    data class GateDecision(
        val allow: Boolean,
        val reason: String,
        val cooldownHours: Int = 0,
    )

    /**
     * 最近一次审批决策的持久化记录（DebugPage 展示用）。
     *
     * @param timestamp 决策时间戳
     * @param allow 是否允许
     * @param reason 决策原因
     * @param cooldownHours 建议冷却小时数（仅 allow=false 时有意义）
     * @param tensionDndUntil 临时免打扰到期时间戳（0 表示未在免打扰期）
     * @param warmth 决策时的 warmth 值（便于观察）
     * @param tension 决策时的 tension 值
     * @param msSinceLastActive 决策时距上次用户活跃的毫秒数（-1 表示从未活跃）
     */
    data class LastDecision(
        val timestamp: Long,
        val allow: Boolean,
        val reason: String,
        val cooldownHours: Int,
        val tensionDndUntil: Long,
        val warmth: Float,
        val tension: Float,
        val msSinceLastActive: Long,
    )

    /**
     * 综合审批：全局时间窗 DND + AffectiveField + 动态冷却 + 临时免打扰。
     *
     * 调用时机：runOnce 在通过已有审批（proactive_enabled / silence / 配额等）后，
     * 发送消息前调用此方法。
     *
     * 内部行为：
     * - 第 0 层（时间窗 DND）单独判断，不调用 snapshot（最常命中，避免无谓 IO）
     * - 第 1-3 层共享一次 AffectiveEngine.snapshot() 调用
     * - 不论 allow/deny，都会把决策记录到 prefs（`proactive_gatekeeper_last_$chat`）
     *
     * @param ctx Context
     * @param chatName 角色/会话名
     * @return GateDecision
     */
    suspend fun decide(ctx: Context, chatName: String): GateDecision {
        return try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // 0. 全局时间窗 DND（P2.1 新增）— 最常命中，单独处理避免无谓 snapshot
            val timeWindowDnd = checkGlobalTimeWindowDnd(prefs, now)
            if (timeWindowDnd != null) {
                // 时间窗 DND 时不调用 snapshot；warmth/tension 记录为 0 便于 DebugPage 识别
                recordDecision(prefs, chatName, now, timeWindowDnd, snapshot = null, msSinceLastActive = -1L)
                return timeWindowDnd
            }

            // 只调用一次 snapshot，下面 3 个子检查共享
            val snapshot = AffectiveEngine.snapshot(ctx, chatName)
            val lastActive = prefs.getLong("last_active_$chatName", 0L)
            val msSinceLastActive = if (lastActive > 0) now - lastActive else -1L

            // 1. 临时免打扰检查（tension 驱动）
            val tensionDnd = checkTensionDnd(prefs, chatName, now, snapshot)
            if (tensionDnd != null) {
                recordDecision(prefs, chatName, now, tensionDnd, snapshot, msSinceLastActive)
                return tensionDnd
            }

            // 2. AffectiveField 审批（复用 dryRunScan 决策规则）
            val affectiveApproval = checkAffectiveField(snapshot, msSinceLastActive)
            if (!affectiveApproval.allow) {
                recordDecision(prefs, chatName, now, affectiveApproval, snapshot, msSinceLastActive)
                return affectiveApproval
            }

            // 3. 动态冷却时间检查
            val cooldownCheck = checkDynamicCooldown(prefs, chatName, now, snapshot)
            if (!cooldownCheck.allow) {
                recordDecision(prefs, chatName, now, cooldownCheck, snapshot, msSinceLastActive)
                return cooldownCheck
            }

            // 全部通过
            val ok = GateDecision(allow = true, reason = "审批通过（时间窗 + AffectiveField + 冷却 + 免打扰）")
            recordDecision(prefs, chatName, now, ok, snapshot, msSinceLastActive)
            ok
        } catch (e: Exception) {
            // fail-open：守门员出错时不阻塞发送
            Log.w(TAG, "decide failed, fail-open", e)
            val fail = GateDecision(allow = true, reason = "gatekeeper error: ${e.message}")
            // 错误情况下也记录决策，便于 DebugPage 观察（snapshot 可能再次失败，忽略）
            try {
                recordDecision(
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                    chatName, System.currentTimeMillis(), fail,
                    AffectiveEngine.snapshot(ctx, chatName), -1L,
                )
            } catch (_: Exception) { /* 二次错误忽略 */ }
            fail
        }
    }

    // ── 0. 全局时间窗 DND（P2.1 新增）──

    /**
     * 检查全局时间窗 DND（用户配置的睡眠时段）。
     *
     * 配置（SharedPreferences）：
     * - `proactive_dnd_start_hour`：DND 起始小时（0-23，默认 23）
     * - `proactive_dnd_end_hour`：DND 结束小时（0-23，默认 7）
     *
     * 跨午夜处理：start > end 时按 [start, 24) ∪ [0, end) 判断
     * 禁用：start == end 时返回 null（不启用时间窗 DND）
     *
     * @return null 表示不在 DND 时段；非 null 表示拒绝
     */
    private fun checkGlobalTimeWindowDnd(
        prefs: android.content.SharedPreferences,
        now: Long,
    ): GateDecision? {
        val startHour = prefs.getInt("proactive_dnd_start_hour", 23)
        val endHour = prefs.getInt("proactive_dnd_end_hour", 7)
        if (startHour == endHour) return null  // 禁用

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (!isInDndWindow(hour, startHour, endHour)) return null

        val remainMin = computeDndRemainMinutes(hour, startHour, endHour)
        val startLabel = "%02d".format(startHour)
        val endLabel = "%02d".format(endHour)
        return GateDecision(
            allow = false,
            reason = "时间窗 DND（$startLabel:00-$endLabel:00，当前 ${"%02d".format(hour)} 时，剩余 ${remainMin}min）",
        )
    }

    /** 判断当前小时是否在 DND 窗口内（支持跨午夜） */
    private fun isInDndWindow(hour: Int, start: Int, end: Int): Boolean {
        return if (start < end) {
            // 不跨午夜：[start, end)
            hour in start until end
        } else {
            // 跨午夜：[start, 24) ∪ [0, end)
            hour >= start || hour < end
        }
    }

    /** 计算当前时刻到 DND 结束的剩余分钟数（用于 reason 展示） */
    private fun computeDndRemainMinutes(hour: Int, start: Int, end: Int): Long {
        // 把当前小时换算成"DND 内的相对分钟数"
        val cal = java.util.Calendar.getInstance()
        val minute = cal.get(java.util.Calendar.MINUTE)
        val currentMinutesInDay = hour * 60L + minute
        val endMinutesInDay = end * 60L
        val remain = if (endMinutesInDay > currentMinutesInDay) {
            endMinutesInDay - currentMinutesInDay
        } else {
            // 跨午夜：end 在第二天
            (24 * 60L - currentMinutesInDay) + endMinutesInDay
        }
        return remain
    }

    // ── 1. 临时免打扰（tension 驱动）──

    /**
     * 检查基于 tension 的临时免打扰。
     *
     * 规则：
     * - tension > 0.6 → 临时免打扰 6 小时（关系剑拔弩张，避免施压）
     * - tension > 0.4 → 临时免打扰 2 小时（关系紧张，让用户先发起）
     * - tension > 0.2 且 stateHint 含 LATENCY_INCREASING → 临时免打扰 1 小时
     *
     * 免打扰状态持久化到 `proactive_tension_dnd_$chat`（时间戳，到期自动解除）。
     *
     * @return null 表示不在免打扰期；非 null 表示拒绝（含 reason）
     */
    private fun checkTensionDnd(
        prefs: android.content.SharedPreferences,
        chatName: String,
        now: Long,
        snapshot: AffectiveEngine.Snapshot,
    ): GateDecision? {
        // 检查是否已在临时免打扰期
        val dndUntil = prefs.getLong("proactive_tension_dnd_$chatName", 0L)
        if (now < dndUntil) {
            val remainMin = (dndUntil - now) / 60_000L
            return GateDecision(
                allow = false,
                reason = "临时免打扰中（tension 驱动），剩余 ${remainMin}min",
                cooldownHours = 0,  // 已在免打扰期，不需要再加冷却
            )
        }

        // 检查是否需要触发新的临时免打扰
        val field = snapshot.field
        val signals = snapshot.stateHint.signals
        return when {
            field.tension > 0.6f -> {
                prefs.edit().putLong("proactive_tension_dnd_$chatName", now + 6 * 3_600_000L).apply()
                GateDecision(
                    allow = false,
                    reason = "tension=${"%.2f".format(field.tension)} > 0.6，临时免打扰 6h",
                    cooldownHours = 6,
                )
            }
            field.tension > 0.4f -> {
                prefs.edit().putLong("proactive_tension_dnd_$chatName", now + 2 * 3_600_000L).apply()
                GateDecision(
                    allow = false,
                    reason = "tension=${"%.2f".format(field.tension)} > 0.4，临时免打扰 2h",
                    cooldownHours = 2,
                )
            }
            field.tension > 0.2f && RhythmSensor.RhythmSignal.LATENCY_INCREASING in signals -> {
                prefs.edit().putLong("proactive_tension_dnd_$chatName", now + 1 * 3_600_000L).apply()
                GateDecision(
                    allow = false,
                    reason = "tension=${"%.2f".format(field.tension)} 且用户回复变慢，临时免打扰 1h",
                    cooldownHours = 1,
                )
            }
            else -> null  // 不需要免打扰
        }
    }

    // ── 2. AffectiveField 审批（复用 dryRunScan 决策规则）──

    /**
     * 基于 AffectiveField 的发送审批。
     *
     * 复用 ProactiveScheduler.computeDryRunEntry 的决策规则，但只返回 allow/deny + reason。
     * 确保实际发送与 dry-run 建议一致。
     */
    private fun checkAffectiveField(
        snapshot: AffectiveEngine.Snapshot,
        msSinceLastActive: Long,
    ): GateDecision {
        val field = snapshot.field
        val signals = snapshot.stateHint.signals

        // 从未交互 → 拒绝（无足够数据）
        if (msSinceLastActive < 0 && field.lastUpdatedTs == 0L) {
            return GateDecision(allow = false, reason = "从未交互，无 AffectiveField 数据")
        }

        // 禁止条件（与 dryRunScan 一致）
        val tooSoon = msSinceLastActive in 0..<2 * 3_600_000L
        if (tooSoon) {
            return GateDecision(allow = false, reason = "刚聊过 ${msSinceLastActive / 60_000L}min，无需主动")
        }
        if (field.tension > 0.4f) {
            return GateDecision(allow = false, reason = "关系紧张(tension=${"%.2f".format(field.tension)})")
        }
        if (field.warmth < -0.2f) {
            return GateDecision(allow = false, reason = "关系冷淡(warmth=${"%.2f".format(field.warmth)})")
        }
        val userSlowingDown = RhythmSensor.RhythmSignal.LATENCY_INCREASING in signals
            || RhythmSensor.RhythmSignal.INITIATIVE_DROPPING in signals
        if (userSlowingDown) {
            return GateDecision(allow = false, reason = "用户回复变慢/主动减少，避免施压（R-S3）")
        }

        // 建议条件（至少满足一个才允许）
        val warmAndIdle = field.warmth > 0.2f && msSinceLastActive > 6 * 3_600_000L
        val hasClosure = snapshot.closureCandidates.isNotEmpty()
        if (!warmAndIdle && !hasClosure) {
            return GateDecision(allow = false, reason = "条件未达建议阈值(warmth=${"%.2f".format(field.warmth)}, gap=${if (msSinceLastActive < 0) "从未" else "${msSinceLastActive / 3_600_000L}h"})")
        }

        return GateDecision(allow = true, reason = buildString {
            append("AffectiveField 审批通过")
            if (warmAndIdle) append("（warmth=${"%.2f".format(field.warmth)}, gap=${msSinceLastActive / 3_600_000L}h）")
            if (hasClosure) append("（${snapshot.closureCandidates.size} 件待跟进）")
        })
    }

    // ── 3. 动态冷却时间（warmth 驱动）──

    /**
     * 检查动态冷却时间。
     *
     * 规则：距上次主动消息的时间间隔必须 ≥ 动态冷却时间。
     * - warmth > 0.6（亲密）→ 冷却 4h（可以更频繁）
     * - warmth > 0.2（熟络）→ 冷却 8h
     * - warmth > -0.2（初识）→ 冷却 12h
     * - warmth <= -0.2（疏远）→ 冷却 24h（很少主动）
     *
     * 这与 runOnce 中已有的 `proactive_interval_min_$chat`（用户配置的固定间隔）是 OR 关系：
     * 两者取较大值。此方法只检查 AffectiveField 驱动的动态冷却。
     */
    private fun checkDynamicCooldown(
        prefs: android.content.SharedPreferences,
        chatName: String,
        now: Long,
        snapshot: AffectiveEngine.Snapshot,
    ): GateDecision {
        val lastProactive = prefs.getLong("proactive_last_$chatName", 0L)
        if (lastProactive <= 0) {
            return GateDecision(allow = true, reason = "从未主动发过，无冷却限制")
        }

        val warmth = snapshot.field.warmth
        val cooldownHours = when {
            warmth > 0.6f -> 4
            warmth > 0.2f -> 8
            warmth > -0.2f -> 12
            else -> 24
        }
        val cooldownMs = cooldownHours * 3_600_000L
        val elapsed = now - lastProactive
        if (elapsed < cooldownMs) {
            val remainHours = (cooldownMs - elapsed) / 3_600_000L
            return GateDecision(
                allow = false,
                reason = "动态冷却中（warmth=${"%.2f".format(warmth)} → 冷却${cooldownHours}h，剩余${remainHours}h）",
            )
        }
        return GateDecision(allow = true, reason = "动态冷却已过（warmth=${"%.2f".format(warmth)} → 冷却${cooldownHours}h）")
    }

    // ── 工具方法（DebugPage 用）──

    /** 清除某角色的临时免打扰状态（DebugPage 用） */
    fun clearTensionDnd(ctx: Context, chatName: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("proactive_tension_dnd_$chatName").apply()
    }

    /** 查询某角色的临时免打扰到期时间（DebugPage 用）；0 表示未在免打扰 */
    fun getTensionDndUntil(ctx: Context, chatName: String): Long {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("proactive_tension_dnd_$chatName", 0L)
    }

    /**
     * 获取某角色最近一次审批决策（DebugPage 用）；null 表示从未决策过。
     */
    fun getLastDecision(ctx: Context, chatName: String): LastDecision? {
        return try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("proactive_gatekeeper_last_$chatName", null) ?: return null
            val o = JSONObject(json)
            LastDecision(
                timestamp = o.getLong("ts"),
                allow = o.getBoolean("allow"),
                reason = o.getString("reason"),
                cooldownHours = o.optInt("cooldown", 0),
                tensionDndUntil = o.optLong("dndUntil", 0L),
                warmth = o.optDouble("warmth", 0.0).toFloat(),
                tension = o.optDouble("tension", 0.0).toFloat(),
                msSinceLastActive = o.optLong("gap", -1L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "getLastDecision failed", e)
            null
        }
    }

    /** 清除某角色的最近决策记录（DebugPage 删除角色时调用） */
    fun clearLastDecision(ctx: Context, chatName: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("proactive_gatekeeper_last_$chatName").apply()
    }

    // ── 内部：决策记录持久化 ──

    /**
     * 记录最近一次决策到 prefs（DebugPage 用）。
     *
     * @param snapshot 可空 — 时间窗 DND 等不调用 snapshot 的检查传 null，warmth/tension 记为 0
     */
    private fun recordDecision(
        prefs: android.content.SharedPreferences,
        chatName: String,
        timestamp: Long,
        decision: GateDecision,
        snapshot: AffectiveEngine.Snapshot?,
        msSinceLastActive: Long,
    ) {
        try {
            val dndUntil = prefs.getLong("proactive_tension_dnd_$chatName", 0L)
            val json = JSONObject().apply {
                put("ts", timestamp)
                put("allow", decision.allow)
                put("reason", decision.reason)
                put("cooldown", decision.cooldownHours)
                put("dndUntil", dndUntil)
                put("warmth", snapshot?.field?.warmth?.toDouble() ?: 0.0)
                put("tension", snapshot?.field?.tension?.toDouble() ?: 0.0)
                put("gap", msSinceLastActive)
            }
            prefs.edit().putString("proactive_gatekeeper_last_$chatName", json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "recordDecision failed", e)
        }
    }
}
