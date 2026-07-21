package com.aftglw.devapi.core.time

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aftglw.devapi.MainActivity
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.mood.AffinityManager
import com.aftglw.devapi.core.storage.ChatHistory
import com.aftglw.devapi.core.storage.room.AppDatabase
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ProactiveScheduler {
    fun enqueue(context: Context) {
        // app 启动时调用：用 REPLACE 策略确保每次启动都会跑一次（取消 pending 的周期任务）。
        // runOnce 末尾的 finally 会重新 enqueue 30min 周期任务，因此这里覆盖不会丢失后续调度。
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "proactive",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun triggerNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch { runOnce(context) }
    }

    /** 仅供 UI 测试：生成一条消息但不入库不发通知，返回文本供 Toast 预览 */
    suspend fun generateMessagePublic(ctx: Context, chatName: String): String? = try {
        generateMessage(ctx, chatName).ifBlank { null }
    } catch (e: Exception) { Log.w(TAG, "preview failed", e); null }

    fun forceSend(context: Context, chatName: String) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = "Test message ($now)"
        CoroutineScope(Dispatchers.IO).launch {
            // 单条 append 避免全量 load+save 覆盖并发写入
            ChatHistory.appendMessage(context, chatName, com.aftglw.devapi.model.ChatMessage("assistant", message))
        }
        sendNotif(context, chatName, message)
    }

    suspend fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("worker_last_run", System.currentTimeMillis()).apply()
        try {
            // 全局凌晨静默：1-5 点不触发任何主动消息（避免夜间骚扰）
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 1..4) return
            // 5 点也只对已醒用户触发（暂简化为 1-4 点静默，5 点允许）
            for (item in AppDatabase.get(context).chatDao().getAll()) {
                val chatDisplay = item.name
                val chat = item.id.ifEmpty { chatDisplay }
                if (!prefs.getBoolean("proactive_enabled_$chat", false)) continue
                if (System.currentTimeMillis() < prefs.getLong("proactive_silence_$chat", 0L)) continue

                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val todayCount = prefs.getInt("proactive_count_${chat}_$today", 0)
                if (todayCount >= prefs.getInt("proactive_daily_limit_$chat", 10)) continue

                // P1-4: 用户 30 分钟内发过消息则跳过（用户还在主动聊天，不需要 AI 主动打扰）
                val lastActive = prefs.getLong("last_active_$chat", 0L)
                if (lastActive > 0 && System.currentTimeMillis() - lastActive < 30 * 60_000L) continue

                val mode = prefs.getString("proactive_trigger_mode_$chat", "custom") ?: "custom"
                val shouldTrigger = if (mode == "ai") {
                    true
                } else {
                    shouldTrigger(prefs, chat)
                }
                if (!shouldTrigger) continue

                val message = generateMessage(context, chat)
                // 用空字符串表示"不发"；只判 isBlank，避免 AI 真返回 "..." 被误拦截
                if (message.isBlank()) continue
                // 单条 append 避免全量 load+save 覆盖并发写入
                ChatHistory.appendMessage(context, chat, com.aftglw.devapi.model.ChatMessage("assistant", message))
                sendNotif(context, chatDisplay, message)
                prefs.edit()
                    .putInt("proactive_count_${chat}_$today", todayCount + 1)
                    .putLong("proactive_last_$chat", System.currentTimeMillis())
                    .apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proactive trigger failed", e)
        } finally {
            // P0: 周期触发 —— 本轮跑完后重新 enqueue 自己，实现类似 AlarmManager.setInexactRepeating 的效果。
            // 延迟 30 分钟（与默认 proactive_interval_min 一致），由 WorkManager 在 Doze 下由系统统一调度，比 AlarmManager 省电。
            // ExistingWorkPolicy.KEEP 保证若已有 pending 工作不会重复排队。
            val nextRequest = OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "proactive",
                ExistingWorkPolicy.KEEP,
                nextRequest
            )
        }
    }

    private fun shouldTrigger(prefs: android.content.SharedPreferences, chat: String): Boolean {
        val checkMode = prefs.getString("proactive_check_mode_$chat", "random") ?: "random"
        if (checkMode == "random" && kotlin.random.Random.nextFloat() > 0.4f) return false
        val affinity = AffinityManager.getAffinity(prefs, chat)
        val minLevel = prefs.getInt("proactive_affinity_min_$chat", 0)
        if (AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) < minLevel) return false

        val lastActive = prefs.getLong("last_active_$chat", 0L)
        val hoursSince = if (lastActive > 0) {
            (System.currentTimeMillis() - lastActive) / 3_600_000L
        } else {
            Long.MAX_VALUE
        }
        val lastProactive = prefs.getLong("proactive_last_$chat", 0L)
        val interval = prefs.getInt("proactive_interval_min_$chat", 30).coerceAtLeast(1)
        val minimumDelay = if (checkMode == "fixed") interval * 60_000L else 7_200_000L
        if (System.currentTimeMillis() - lastProactive < minimumDelay) return false

        val idleHours = prefs.getInt("proactive_idle_hours_$chat", 0)
        val triggers = prefs.getString("proactive_triggers_$chat", "1,2,3,4,5,6")
            ?.split(',') ?: emptyList()
        return triggers.any { trigger ->
            when (trigger.trim()) {
                "1" -> idleHours == 0 || hoursSince >= idleHours
                "2" -> {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    hour in 1..4 && hoursSince < 12
                }
                "3" -> hoursSince < 3
                "4" -> hoursSince >= 24
                "5" -> AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) >= 3 && hoursSince < 6
                "6" -> hoursSince >= 72
                else -> false
            }
        }
    }

    private suspend fun generateMessage(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        if (prefs.getString("proactive_trigger_mode_$chatName", "custom") == "ai") {
            return generateMessageAiDriven(ctx, chatName)
        }

        val apiKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key")
        if (apiKey.isBlank()) return ""
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        val messages = history.map {
            com.aftglw.devapi.model.ChatMessage(if (it.second) "user" else "assistant", it.first)
        }
        // 注入反思产物 + 当前时间 + 上次对话间隔，让 AI 主动消息有"情境感"
        val insightText = try { MemoryStore.listRecentByTopic("insight:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val aiEmoText = try { MemoryStore.listRecentByTopic("ai_emo:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val now = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val lastActive = prefs.getLong("last_active_$chatName", 0L)
        val gapHint = if (lastActive > 0L) {
            val mins = (System.currentTimeMillis() - lastActive) / 60_000L
            when {
                mins < 60 -> "距上次聊天 ${mins} 分钟"
                mins < 1440 -> "距上次聊天 ${mins / 60} 小时"
                else -> "距上次聊天 ${mins / 1440} 天"
            }
        } else "之前没聊过"
        val prompt = buildString {
            if (persona.isNotBlank()) append(persona).append("\n\n")
            append("【当前时间】$now\n")
            append("【距离上次聊天】$gapHint\n")
            if (insightText.isNotBlank()) append("【上次对话本质】$insightText\n")
            if (aiEmoText.isNotBlank()) append("【你上次的情绪】$aiEmoText\n")
            append("\n你现在要主动给对方发一条消息，像朋友自然地打招呼或关心一下。")
            append("要求：1-2 句话，每句不超过 15 字；不要提及\"我刚想起来\"\"我主动\"等元描述；不要重复上次说过的话。")
            append("如果没什么好说的，就回复空白。")
        }
        return try {
            com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(messages, "主动发起一条自然的消息", prompt)
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Message generation failed", e)
            ""
        }
    }

    private suspend fun generateMessageAiDriven(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        // AI 模式：让模型自己决定是否要发消息；同时给足上下文（反思产物 + 时间 + 间隔）
        val insightText = try { MemoryStore.listRecentByTopic("insight:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val aiEmoText = try { MemoryStore.listRecentByTopic("ai_emo:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val memo = try { MemoryStore.listRecentByTopic("turn:$chatName", 3).joinToString("\n") { "- ${it.text}" } } catch (_: Exception) { "" }
        val now = SimpleDateFormat("MM-dd HH:mm E", Locale.CHINESE).format(Date())
        val lastActive = prefs.getLong("last_active_$chatName", 0L)
        val gapHint = if (lastActive > 0L) {
            val mins = (System.currentTimeMillis() - lastActive) / 60_000L
            when {
                mins < 60 -> "${mins} 分钟前"
                mins < 1440 -> "${mins / 60} 小时前"
                else -> "${mins / 1440} 天前"
            }
        } else "很久没聊"
        val prompt = buildString {
            append("你要决定是否主动给对方发一条消息。\n\n")
            append("【当前时间】$now\n")
            append("【上次聊天】$gapHint\n")
            if (persona.isNotBlank()) append("【你的人设】\n").append(persona).append("\n\n")
            append("【最近对话】\n")
            history.takeLast(6).forEach { append(if (it.second) "对方: " else "你: ").append(it.first.take(80)).append('\n') }
            if (memo.isNotBlank()) append("\n【记忆碎片】\n").append(memo).append('\n')
            if (insightText.isNotBlank()) append("\n【上次对话本质】").append(insightText).append('\n')
            if (aiEmoText.isNotBlank()) append("【你上次情绪】").append(aiEmoText).append('\n')
            append("\n请判断：现在主动发消息是否合适？")
            append("\n- 如果不合适（如刚聊完、深夜、没话说），输出空白一行")
            append("\n- 如果合适，直接输出消息内容（1-2 句话，每句 ≤15 字），不要任何解释或前缀")
        }
        return try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "你是这个对话中的角色本人。只输出消息内容或空白。")
            reply?.trim()?.takeIf { it.isNotBlank() }?.take(100) ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "AI proactive generation failed", e)
            ""
        }
    }

    private suspend fun loadPersona(ctx: Context, chatName: String): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).chatDao()
        val entity = dao.getById(chatName) ?: dao.getAll().firstOrNull { it.name == chatName }
        entity?.persona ?: ""
    }

    private fun sendNotif(ctx: Context, chat: String, message: String) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("proactive", "主动消息", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("open_chat", chat)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, chat.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(ctx, "proactive")
            .setContentTitle(chat)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(chat.hashCode(), notification)
    }

    private const val TAG = "ProactiveScheduler"
}
