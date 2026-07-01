package com.aftglw.devapi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        Thread {
            try { ProactiveScheduler.runOnce(context) } catch (e: Exception) {  }
            result.finish()
        }.start()
    }
}

object ProactiveScheduler {
    fun enqueue(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, ProactiveReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 60000, 900000, pi)
        runOnce(context)
    }

    fun triggerNow(context: Context) { runOnce(context) }

    fun forceSend(context: Context, chatName: String) {
        val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val msg = "👋 测试消息 ($now)"
        ChatHistory.save(context, chatName, ChatHistory.load(context, chatName) + Triple(msg, false, now))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) nm.createNotificationChannel(android.app.NotificationChannel("proactive", "主动消息", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = android.app.PendingIntent.getActivity(context, chatName.hashCode(), Intent(context, MainActivity::class.java).apply { putExtra("open_chat", chatName); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (android.app.Notification.Builder(context, "proactive").setContentTitle(chatName).setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setAutoCancel(true).build()).let { nm.notify(chatName.hashCode(), it) }
    }

    fun runOnce(context: Context) {
        // 确保网络请求在 IO 线程
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("worker_last_run", System.currentTimeMillis()).apply()
        try {
            val arr = org.json.JSONArray(context.getSharedPreferences("wechat_chats", Context.MODE_PRIVATE).getString("chats", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val chat = arr.getJSONObject(i).getString("name")
                if (!prefs.getBoolean("proactive_enabled_${chat}", false)) continue
                if (System.currentTimeMillis() < prefs.getLong("proactive_silence_${chat}", 0L)) continue
                val limit = prefs.getInt("proactive_daily_limit_${chat}", 10)
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                val todayCount = prefs.getInt("proactive_count_${chat}_$today", 0)
                if (todayCount >= limit) continue
                val triggerMode = prefs.getString("proactive_trigger_mode_${chat}", "custom") ?: "custom"
                if (triggerMode == "ai") { /* AI 模式跳过触发条件 */ } else {
                val idleHours = prefs.getInt("proactive_idle_hours_${chat}", 0)
                val checkMode = prefs.getString("proactive_check_mode_${chat}", "random") ?: "random"
                val triggers = prefs.getString("proactive_triggers_${chat}", "1,2,3,4,5,6") ?: "1,2,3,4,5,6"
                if (checkMode == "random" && kotlin.random.Random.nextFloat() > 0.4f) continue
                val affinity = AffinityManager.getAffinity(prefs, chat)
                val affinityMin = prefs.getInt("proactive_affinity_min_${chat}", 0)
                if (AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) < affinityMin) continue
                val lastActive = prefs.getLong("last_active_${chat}", 0L)
                val hoursSince = if (lastActive > 0) (System.currentTimeMillis() - lastActive) / 3600000 else 999L
                val lastProactive = prefs.getLong("proactive_last_${chat}", 0L)
                val intervalMin = prefs.getInt("proactive_interval_min_${chat}", 30).coerceAtLeast(1)
                if (System.currentTimeMillis() - lastProactive < (if (checkMode == "fixed") intervalMin * 60000L else 7200000L)) continue
                val shouldTrigger = triggers.split(",").any { t ->
                    when (t) {
                        "1" -> if (idleHours == 0) true else hoursSince >= idleHours
                        "2" -> { val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); h in 1..4 && hoursSince < 12 }
                        "3" -> { val m = prefs.getString("last_mood_${chat}", "") ?: ""; m in listOf("悲伤","愤怒","害怕","厌恶") && hoursSince < 3 }
                        "4" -> hoursSince >= 24; "5" -> AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) >= 3 && hoursSince < 6
                        "6" -> hoursSince >= 72; else -> false
                    }
                }
                if (!shouldTrigger) continue
                }
                val msg = generateMessage(context, chat)
                if (msg.isBlank() || msg == "在干嘛呢？") continue  // 跳过
                val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                ChatHistory.save(context, chat, ChatHistory.load(context, chat) + Triple(msg, false, now))
                sendNotif(context, chat, msg)
                prefs.edit().putInt("proactive_count_${chat}_$today", todayCount + 1).putLong("proactive_last_${chat}", System.currentTimeMillis()).apply()
            }
        } catch (_: Exception) {}
        }
    }

    private fun generateMessage(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("proactive_trigger_mode_$chatName", "custom") ?: "custom"
        if (mode == "ai") return generateMessageAiDriven(ctx, chatName)
        // 以下为原有的自定义规则模式
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        if (apiKey.isBlank()) return "在干嘛呢？"
        val persona = loadPersona(ctx, chatName)
        val affinity = AffinityManager.getAffinity(prefs, chatName).toInt()
        val level = AffinityManager.getLevel(affinity.toFloat())
        val hint = AffinityManager.getLevelHint(level) ?: ""
        val optimized = prefs.getString("persona_optimized_$chatName", "") ?: ""
        val optBlock = if (optimized.isNotBlank()) "\n【聊天偏好】$optimized" else ""
        val nc = prefs.getBoolean("proactive_need_care_$chatName", false); if (nc) prefs.edit().putBoolean("proactive_need_care_$chatName", false).apply()
        val moodHint = if (nc) "\n对方可能心情不好，关心一下。" else ""
        val affinityBlock = if (prefs.getBoolean("affinity_enabled", false)) "\n【当前关系】${level.name}\n$hint" else ""
        val mems = MemoryStore.search(ctx, chatName, 1).joinToString("\n") { "- ${it.text}" }
        val memBlock = if (mems.isNotEmpty()) "\n【关于对方的记忆】\n$mems" else ""
        val baseInstruction = "\n回复要求：每句话不超过15个字，一次只说1-2句。禁止AI套话。"
        val systemPrompt = buildString {
            append(if (persona.isNotBlank()) "$persona\n\n" else "")
            append(baseInstruction)
            append(affinityBlock)
            append(optBlock)
            append(memBlock)
            append(if (moodHint.isNotEmpty()) "\n$moodHint" else "")
        }
        val chatHistory = ChatHistory.load(ctx, chatName)
        val msgHistory = chatHistory.map { com.aftglw.devapi.model.ChatMessage(if (it.second) "user" else "assistant", it.first) }
        // 复用已有的 AiServiceFactory，它在 ChatScreen 里工作正常
        return try {
            com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(msgHistory, "自然地开启聊天", systemPrompt) ?: "在干嘛呢？"
        } catch (_: Exception) { "在干嘛呢？" }
    }

    private fun generateMessageAiDriven(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val persona = loadPersona(ctx, chatName)
        val chatHistory = ChatHistory.load(ctx, chatName)
        val lastMsg = chatHistory.lastOrNull()?.first ?: ""
        val hoursSince = if (chatHistory.isNotEmpty()) {
            val lastTime = prefs.getLong("last_active_$chatName", 0L)
            if (lastTime > 0) (System.currentTimeMillis() - lastTime) / 3600000 else 0
        } else 0
        val affinity = AffinityManager.getAffinity(prefs, chatName).toInt()
        val level = AffinityManager.getLevel(affinity.toFloat())
        val longContext = prefs.getBoolean("proactive_long_history_$chatName", false)
        val needCare = prefs.getBoolean("proactive_need_care_$chatName", false)
        if (needCare) prefs.edit().putBoolean("proactive_need_care_$chatName", false).apply()
        val memo = MemoryStore.search(ctx, chatName, 2).joinToString("\n") { "- ${it.text}" }
        val customRules = prefs.getString("proactive_custom_rules_$chatName", "") ?: ""

        val prompt = buildString {
            appendLine("你在考虑要不要主动找对方聊天。请看以下信息：")
            if (longContext && chatHistory.size >= 3) {
                appendLine("\n【最近对话】")
                chatHistory.takeLast(6).forEach { appendLine("  ${if (it.second) "对方" else "你"}：${it.first}") }
            } else {
                appendLine("\n距上次聊天：${hoursSince}小时")
                appendLine("对方上一条消息：$lastMsg")
                appendLine("你们的关系：${level.name}")
                if (memo.isNotEmpty()) appendLine("记忆片段：$memo")
            }
            if (needCare) appendLine("\n（对方似乎需要关心）")
            if (customRules.isNotBlank()) appendLine("\n【自定义规则】\n$customRules")
            appendLine("\n当前时间：${com.aftglw.devapi.TimeService.getFormattedTime(ctx)}（${com.aftglw.devapi.TimeService.getTimeOfDay(ctx)}）")
            if (persona.isNotBlank()) appendLine("\n你的人物设定：$persona")
            appendLine("\n如果你觉得现在适合主动说话，按以下格式回复：")
            appendLine("决定：发")
            appendLine("消息：[你想说的话]")
            appendLine("\n如果觉得不适合，只回复：决定：不发")
        }

        try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "你是角色本人。")
            if (reply != null && reply.contains("决定：发")) {
                val msg = reply.substringAfter("消息：").trim().take(100)
                return if (msg.isNotBlank()) msg else "在干嘛呢？"
            }
        } catch (_: Exception) {}
        return ""  // 返回空 = 跳过本次
    }

    private fun loadPersona(ctx: Context, chatName: String): String {
        val arr = org.json.JSONArray(ctx.getSharedPreferences("wechat_chats", Context.MODE_PRIVATE).getString("chats", "[]") ?: "[]")
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("name") == chatName) return o.optString("persona", "") }
        return ""
    }

    private fun sendNotif(ctx: Context, chat: String, msg: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) nm.createNotificationChannel(android.app.NotificationChannel("proactive", "主动消息", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = android.app.PendingIntent.getActivity(ctx, chat.hashCode(), Intent(ctx, MainActivity::class.java).apply { putExtra("open_chat", chat); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (android.app.Notification.Builder(ctx, "proactive").setContentTitle(chat).setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setAutoCancel(true).build()).let { nm.notify(chat.hashCode(), it) }
    }
}
