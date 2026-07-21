package com.aftglw.devapi.core.time

import android.content.Context
import android.content.SharedPreferences
import com.aftglw.devapi.core.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主动关怀状态集中管理：降频、模板、跨角色协调、事件、作息、通知动作。
 * 所有 SharedPreferences key 都以 proactive_ 前缀，集中在此文件维护避免散落。
 */
object ProactiveMessageStore {
    private const val PREFS = "wechat_settings"
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ============ 1. 降频 + 回应率统计 ============

    /** 连续未回复次数 */
    fun getMissStreak(ctx: Context, chat: String): Int =
        prefs(ctx).getInt("proactive_miss_streak_$chat", 0)

    fun incrMissStreak(ctx: Context, chat: String) {
        val cur = getMissStreak(ctx, chat)
        prefs(ctx).edit().putInt("proactive_miss_streak_$chat", cur + 1).apply()
    }

    /** 用户回复主动消息时调用：重置 streak，记录回复率 */
    fun onUserReply(ctx: Context, chat: String) {
        val p = prefs(ctx)
        val streak = p.getInt("proactive_miss_streak_$chat", 0)
        if (streak > 0) {
            // 回复了 → 重置 streak，记录一次"已回复"
            p.edit()
                .putInt("proactive_miss_streak_$chat", 0)
                .putLong("proactive_last_reply_$chat", System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 根据连续未回复次数判断是否应跳过本次触发。
     * 规则：
     *   - streak >= 3 → 降频 50%（随机跳过一半）
     *   - streak >= 5 → 暂停 24h
     *   - streak >= 8 → 暂停 3 天
     */
    fun shouldSkipByMissStreak(ctx: Context, chat: String): Boolean {
        val p = prefs(ctx)
        val silenceUntil = p.getLong("proactive_silence_$chat", 0L)
        if (System.currentTimeMillis() < silenceUntil) return true
        val streak = p.getInt("proactive_miss_streak_$chat", 0)
        if (streak >= 8) {
            // 暂停 3 天
            p.edit().putLong("proactive_silence_$chat", System.currentTimeMillis() + 3 * 24 * 3600_000L).apply()
            return true
        }
        if (streak >= 5) {
            // 暂停 24h（仅设置一次，避免每次都延后）
            if (silenceUntil < System.currentTimeMillis() + 12 * 3600_000L) {
                p.edit().putLong("proactive_silence_$chat", System.currentTimeMillis() + 24 * 3600_000L).apply()
            }
            return true
        }
        if (streak >= 3 && kotlin.random.Random.nextFloat() < 0.5f) return true
        return false
    }

    // ============ 2. 全局勿扰 + 暂停 ============

    /** 全局暂停到某个时间戳；0 表示未暂停 */
    fun getGlobalPauseUntil(ctx: Context): Long =
        prefs(ctx).getLong("proactive_global_paused_until", 0L)

    fun isGlobalPaused(ctx: Context): Boolean =
        System.currentTimeMillis() < getGlobalPauseUntil(ctx)

    /** 暂停指定小时；hours=0 表示取消 */
    fun pauseGlobal(ctx: Context, hours: Int) {
        val until = if (hours <= 0) 0L else System.currentTimeMillis() + hours * 3600_000L
        prefs(ctx).edit().putLong("proactive_global_paused_until", until).apply()
    }

    /** 暂停到今天结束（24:00） */
    fun pauseGlobalUntilEndOfDay(ctx: Context) {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        prefs(ctx).edit().putLong("proactive_global_paused_until", cal.timeInMillis).apply()
    }

    // ============ 3. 跨角色协调 ============

    /** 30 分钟内只允许一个角色主动发消息：记录上次任意角色发送时间 */
    fun shouldSkipByCrossChat(ctx: Context): Boolean {
        val lastAny = prefs(ctx).getLong("proactive_last_any_chat", 0L)
        return System.currentTimeMillis() - lastAny < 30 * 60_000L
    }

    fun markAnyChatSent(ctx: Context) {
        prefs(ctx).edit().putLong("proactive_last_any_chat", System.currentTimeMillis()).apply()
    }

    // ============ 4. 主动消息历史 + 去重 ============

    /** 记录最近 5 条主动消息文本（用于去重），逗号分隔；超长会自动截断 */
    fun recordSentMessage(ctx: Context, chat: String, msg: String) {
        val key = "proactive_recent_msgs_$chat"
        val existing = prefs(ctx).getString(key, "") ?: ""
        val list = existing.split("\n").toMutableList()
        list.add(0, msg.take(60))
        while (list.size > 5) list.removeAt(list.lastIndex)
        prefs(ctx).edit().putString(key, list.joinToString("\n")).apply()
    }

    /** 取最近 N 条主动消息文本 */
    fun getRecentSent(ctx: Context, chat: String, n: Int = 5): List<String> {
        val s = prefs(ctx).getString("proactive_recent_msgs_$chat", "") ?: ""
        return s.split("\n").filter { it.isNotBlank() }.take(n)
    }

    /** 简单字符 Jaccard 相似度，判断新消息是否与最近发的过于相似 */
    fun isDuplicate(ctx: Context, chat: String, msg: String): Boolean {
        val recent = getRecentSent(ctx, chat, 5)
        if (recent.isEmpty()) return false
        val newSet = msg.toSet()
        for (old in recent) {
            val oldSet = old.toSet()
            val inter = newSet.intersect(oldSet).size
            val union = newSet.union(oldSet).size
            val sim = if (union == 0) 0f else inter.toFloat() / union
            if (sim > 0.6f) return true
        }
        return false
    }

    // ============ 5. 用户作息学习 ============

    /** 记录用户活跃的小时分布（0-23）。每次用户发消息时累加对应小时计数 */
    fun recordUserActiveHour(ctx: Context, chat: String) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val key = "proactive_active_hour_${chat}_$hour"
        val cur = prefs(ctx).getInt(key, 0)
        prefs(ctx).edit().putInt(key, cur + 1).apply()
    }

    /** 判断当前小时是否在用户活跃时段（最近 7 天记录中活跃次数 >=3 算活跃） */
    fun isUserActiveHour(ctx: Context, chat: String): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val key = "proactive_active_hour_${chat}_$hour"
        // 凌晨 1-4 点强制视为不活跃（即使有少量记录）
        if (hour in 1..4) return false
        return prefs(ctx).getInt(key, 0) >= 3
    }

    // ============ 6. 事件驱动 ============

    /**
     * 从用户消息中提取未来事件，存入 MemoryStore topic="event:$chat"。
     * 简化匹配：包含"明天/后天/下周X/考试/约会/面试/出差/聚会"等关键词。
     */
    suspend fun extractEvent(ctx: Context, chat: String, userMessage: String) = withContext(Dispatchers.IO) {
        val keywords = listOf("明天", "后天", "下周", "考试", "面试", "约会", "出差", "聚会", "加班", "出差", "搬家", "生日")
        val matched = keywords.filter { userMessage.contains(it) }
        if (matched.isNotEmpty()) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val text = "$today|${userMessage.take(60)}"
            MemoryStore.save(ctx, text, "event:$chat")
            // 只保留最近 10 条事件
            val events = MemoryStore.listRecentByTopic("event:$chat", 20)
            if (events.size > 10) {
                events.drop(10).forEach { MemoryStore.deleteByText(it.text, "event:$chat") }
            }
        }
    }

    /** 取今天应跟进的事件（事件记录日期是昨天或前天，且关键词含"明天/后天"等） */
    suspend fun getDueEvents(ctx: Context, chat: String): List<String> = withContext(Dispatchers.IO) {
        val events = MemoryStore.listRecentByTopic("event:$chat", 10)
        if (events.isEmpty()) return@withContext emptyList()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val cal = java.util.Calendar.getInstance()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val yesterday = sdf.format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val dayBefore = sdf.format(cal.time)
        // 事件记录日期是昨天/前天，且原消息含"明天/后天"，说明"明天"就是今天
        events.mapNotNull { e ->
            val parts = e.text.split("|", limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val eventDate = parts[0]
            val eventText = parts[1]
            val followKeywords = listOf("明天", "后天")
            if (eventDate in listOf(yesterday, dayBefore) && followKeywords.any { eventText.contains(it) }) {
                eventText
            } else null
        }
    }

    // ============ 7. 模板库 fallback ============

    /** 按时段 + 情绪选取一条预设模板。返回空字符串表示无合适模板 */
    fun pickPresetTemplate(hour: Int, mood: String? = null): String {
        val lowMoodSet = setOf("低落", "难过", "沮丧", "疲惫", "累", "烦")
        val templates = when {
            mood != null && lowMoodSet.any { mood.contains(it) } -> listOf(
                "听说今天不太顺？我陪你呆一会儿。",
                "别太累了，过来歇会儿。",
                "感觉你今天有点累，喝口水了吗？",
                "难熬的时候就来找我聊聊吧。",
                "抱抱，今天辛苦了。"
            )
            hour in 5..10 -> listOf(
                "早啊，今天醒得挺早。",
                "早饭吃了吗？",
                "新的一天加油呀。",
                "刚醒？别急慢慢来。"
            )
            hour in 11..13 -> listOf(
                "中午了，吃饭了吗？",
                "别光顾着忙，记得吃饭。",
                "午休一会儿吧。"
            )
            hour in 14..17 -> listOf(
                "下午了，喝口水。",
                "工作别太拼了。",
                "感觉今天怎么样？"
            )
            hour in 18..21 -> listOf(
                "晚上啦，今天过得怎么样？",
                "下班/放学了吗？",
                "晚饭吃了吗？"
            )
            hour in 22..23 -> listOf(
                "夜深了，早点休息吧。",
                "别熬太晚。",
                "睡前的我来了。"
            )
            hour == 0 -> listOf(
                "还不睡？陪你一会儿。",
                "零点了，注意身体。"
            )
            else -> listOf(
                "在忙吗？",
                "想到你了，过来聊聊？",
                "突然想跟你说句话。"
            )
        }
        return templates.random()
    }

    // ============ 8. 通知隐私 ============

    fun isPrivacyMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean("proactive_privacy_mode", false)

    fun setPrivacyMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("proactive_privacy_mode", on).apply()
    }
}
