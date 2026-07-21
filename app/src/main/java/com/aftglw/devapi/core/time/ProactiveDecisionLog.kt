package com.aftglw.devapi.core.time

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProactiveScheduler.runOnce 决策轨迹结构化日志（P2.4a 新增）。
 *
 * 持久化最近 [MAX_ENTRIES] 条 [DecisionEntry]，环形覆盖。
 * - 写：runOnce 每个决策节点（SKIP/ENQUEUE/SENT）追加一条
 * - 读：DebugPage 加载展示，支持按 chatName 筛选
 * - 清：DebugPage 提供清除按钮
 *
 * 设计动机：
 * - runOnce 11 个决策节点中 9 个原先静默 continue/return，事后无法回放"为何某角色今天没收到主动消息"
 * - 参考 ProactiveDryRunStore 模式（50 条环形 + JSON 序列化 + 按 chat 筛选）
 * - 只读日志，不触发任何实际消息或重复处理
 *
 * 存储：SharedPreferences `proactive_decision_log` JSON 数组。
 */
object ProactiveDecisionLog {
    private const val TAG = "ProactiveDecisionLog"
    private const val PREFS_KEY = "proactive_decision_log"
    private const val MAX_ENTRIES = 50

    /**
     * 决策节点标识（与 runOnce 中的判断顺序对应）。
     *
     * 命名约定：节点名 + 动作后缀，如 DRY_RUN_SKIP / GATEKEEPER_SKIP / ENQUEUE。
     */
    object Node {
        const val DRY_RUN_ONLY = "dry_run_only"
        const val NIGHT_SILENCE = "night_silence"
        const val GLOBAL_PAUSED = "global_paused"
        const val CROSS_CHAT = "cross_chat"
        const val NOT_ENABLED = "not_enabled"
        const val SILENCE = "silence"
        const val DAILY_LIMIT = "daily_limit"
        const val USER_ACTIVE_30MIN = "user_active_30min"
        const val MISS_STREAK = "miss_streak"
        const val NOT_ACTIVE_HOUR = "not_active_hour"
        const val SHOULD_TRIGGER = "should_trigger"
        const val GATEKEEPER = "gatekeeper"
        const val MESSAGE_BLANK = "message_blank"
        const val DUPLICATE = "duplicate"
        const val ENQUEUE = "enqueue"
        const val SENT_DIRECT = "sent_direct"
    }

    /**
     * 单条决策记录。
     *
     * @param timestamp 决策时间戳
     * @param chatName 角色名（全局决策如 night_silence 可为空）
     * @param node 决策节点标识（见 [Node]）
     * @param action 动作：SKIP / ENQUEUE / SENT
     * @param reason 决策原因（人类可读）
     * @param warmth 决策时的 warmth（可选上下文）
     * @param tension 决策时的 tension（可选上下文）
     * @param msSinceLastActive 距上次用户活跃毫秒数（-1 表示从未活跃）
     */
    data class DecisionEntry(
        val timestamp: Long,
        val chatName: String,
        val node: String,
        val action: String,
        val reason: String,
        val warmth: Float = 0f,
        val tension: Float = 0f,
        val msSinceLastActive: Long = -1L,
    )

    /** 追加一条决策记录 */
    fun append(ctx: Context, entry: DecisionEntry) {
        try {
            val all = loadAllRaw(ctx).toMutableList()
            all.add(entry)
            while (all.size > MAX_ENTRIES) all.removeAt(0)
            saveAll(ctx, all)
        } catch (e: Exception) {
            Log.w(TAG, "append failed", e)
        }
    }

    /** 读取全部决策日志（按时间倒序，最新在前） */
    fun loadAll(ctx: Context): List<DecisionEntry> = loadAllRaw(ctx).reversed()

    /** 读取某角色最近 N 条 */
    fun loadForChat(ctx: Context, chatName: String, limit: Int = 20): List<DecisionEntry> =
        loadAll(ctx).filter { it.chatName == chatName }.take(limit)

    /** 清除全部日志 */
    fun clearAll(ctx: Context) {
        try {
            ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                .edit().remove(PREFS_KEY).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearAll failed", e)
        }
    }

    // ── 序列化 ──

    private fun loadAllRaw(ctx: Context): List<DecisionEntry> {
        return try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try { jsonToEntry(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed", e)
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, entries: List<DecisionEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(entryToJson(it)) }
        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    private fun entryToJson(entry: DecisionEntry): JSONObject = JSONObject().apply {
        put("ts", entry.timestamp)
        put("chat", entry.chatName)
        put("node", entry.node)
        put("action", entry.action)
        put("reason", entry.reason)
        put("warmth", entry.warmth)
        put("tension", entry.tension)
        put("gapMs", entry.msSinceLastActive)
    }

    private fun jsonToEntry(o: JSONObject): DecisionEntry = DecisionEntry(
        timestamp = o.getLong("ts"),
        chatName = o.optString("chat", ""),
        node = o.optString("node", ""),
        action = o.optString("action", ""),
        reason = o.optString("reason", ""),
        warmth = o.optDouble("warmth", 0.0).toFloat(),
        tension = o.optDouble("tension", 0.0).toFloat(),
        msSinceLastActive = o.optLong("gapMs", -1L),
    )
}
