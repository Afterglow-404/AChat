package com.aftglw.devapi.core.time

import android.content.Context
import android.util.Log
import com.aftglw.devapi.core.affect.AffectiveField
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProactiveScheduler dry-run 建议日志存储（P0.1 新增）。
 *
 * 持久化最近 [MAX_ENTRIES] 条 [ProactiveDryRunEntry]，环形覆盖。
 * - 写：每次 dryRunScan() 完成后追加
 * - 读：DebugPage 加载展示
 * - 清：DebugPage 提供清除按钮
 *
 * 存储：SharedPreferences `proactive_dry_run_log` JSON 数组。
 * 不入 Room —— 这是临时调试日志，不需要跨端同步。
 */
object ProactiveDryRunStore {
    private const val TAG = "ProactiveDryRunStore"
    private const val PREFS_KEY = "proactive_dry_run_log"
    private const val MAX_ENTRIES = 50

    /** 追加一条 dry-run 建议 */
    fun append(ctx: Context, entry: ProactiveDryRunEntry) {
        try {
            val all = loadAll(ctx).toMutableList()
            all.add(entry)
            while (all.size > MAX_ENTRIES) all.removeAt(0)
            saveAll(ctx, all)
        } catch (e: Exception) {
            Log.w(TAG, "append failed", e)
        }
    }

    /** 读取全部 dry-run 日志（按时间倒序，最新在前） */
    fun loadAll(ctx: Context): List<ProactiveDryRunEntry> {
        return try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try { jsonToEntry(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }.reversed()  // 存储顺序是旧→新，反转后新→旧
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed", e)
            emptyList()
        }
    }

    /** 读取某角色最近 N 条 */
    fun loadForChat(ctx: Context, chatName: String, limit: Int = 20): List<ProactiveDryRunEntry> {
        return loadAll(ctx).filter { it.chatName == chatName }.take(limit)
    }

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

    private fun saveAll(ctx: Context, entries: List<ProactiveDryRunEntry>) {
        val arr = JSONArray()
        // 存储顺序：旧→新（追加在末尾）
        entries.forEach { arr.put(entryToJson(it)) }
        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    private fun entryToJson(entry: ProactiveDryRunEntry): JSONObject {
        return JSONObject().apply {
            put("ts", entry.timestamp)
            put("chat", entry.chatName)
            put("recommend", entry.recommendContact)
            put("reason", entry.reason)
            put("field", JSONObject().apply {
                put("tension", entry.field.tension)
                put("warmth", entry.field.warmth)
                put("anticipation", entry.field.anticipation)
                put("drift", entry.field.drift)
                put("lastTs", entry.field.lastUpdatedTs)
            })
            put("rhythmObs", entry.rhythmObservation)
            // 风险 2 修复：序列化结构化 signals
            put("rhythmSignals", JSONArray().apply {
                entry.rhythmSignals.forEach { put(it.name) }
            })
            put("pending", entry.pendingCount)
            put("closure", entry.closureCandidateCount)
            put("gapMs", entry.msSinceLastActive)
        }
    }

    private fun jsonToEntry(o: JSONObject): ProactiveDryRunEntry {
        val f = o.getJSONObject("field")
        val field = AffectiveField(
            tension = f.getDouble("tension").toFloat(),
            warmth = f.getDouble("warmth").toFloat(),
            anticipation = f.getDouble("anticipation").toFloat(),
            drift = f.getDouble("drift").toFloat(),
            lastUpdatedTs = f.getLong("lastTs"),
        )
        // 风险 2 修复：反序列化结构化 signals
        val signals = try {
            val arr = o.optJSONArray("rhythmSignals")
            if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    try { com.aftglw.devapi.core.affect.RhythmSensor.RhythmSignal.valueOf(arr.getString(i)) }
                    catch (_: Exception) { null }
                }.toSet()
            } else emptySet()
        } catch (_: Exception) { emptySet() }
        return ProactiveDryRunEntry(
            timestamp = o.getLong("ts"),
            chatName = o.getString("chat"),
            recommendContact = o.getBoolean("recommend"),
            reason = o.getString("reason"),
            field = field,
            rhythmObservation = o.optString("rhythmObs", ""),
            rhythmSignals = signals,
            pendingCount = o.optInt("pending", 0),
            closureCandidateCount = o.optInt("closure", 0),
            msSinceLastActive = o.optLong("gapMs", -1L),
        )
    }
}
