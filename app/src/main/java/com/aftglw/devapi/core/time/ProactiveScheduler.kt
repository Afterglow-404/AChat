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
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "proactive",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun triggerNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch { runOnce(context) }
    }

    fun forceSend(context: Context, chatName: String) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = "Test message ($now)"
        CoroutineScope(Dispatchers.IO).launch {
            ChatHistory.save(context, chatName, ChatHistory.load(context, chatName) + Triple(message, false, now))
        }
        sendNotif(context, chatName, message)
    }

    suspend fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("worker_last_run", System.currentTimeMillis()).apply()
        try {
            for (item in AppDatabase.get(context).chatDao().getAll()) {
                val chatDisplay = item.name
                val chat = item.id.ifEmpty { chatDisplay }
                if (!prefs.getBoolean("proactive_enabled_$chat", false)) continue
                if (System.currentTimeMillis() < prefs.getLong("proactive_silence_$chat", 0L)) continue

                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val todayCount = prefs.getInt("proactive_count_${chat}_$today", 0)
                if (todayCount >= prefs.getInt("proactive_daily_limit_$chat", 10)) continue

                val mode = prefs.getString("proactive_trigger_mode_$chat", "custom") ?: "custom"
                val shouldTrigger = if (mode == "ai") {
                    true
                } else {
                    shouldTrigger(prefs, chat)
                }
                if (!shouldTrigger) continue

                val message = generateMessage(context, chat)
                if (message.isBlank() || message == DEFAULT_MESSAGE) continue
                val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                ChatHistory.save(context, chat, ChatHistory.load(context, chat) + Triple(message, false, now))
                sendNotif(context, chatDisplay, message)
                prefs.edit()
                    .putInt("proactive_count_${chat}_$today", todayCount + 1)
                    .putLong("proactive_last_$chat", System.currentTimeMillis())
                    .apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proactive trigger failed", e)
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
        if (apiKey.isBlank()) return DEFAULT_MESSAGE
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        val messages = history.map {
            com.aftglw.devapi.model.ChatMessage(if (it.second) "user" else "assistant", it.first)
        }
        val prompt = buildString {
            if (persona.isNotBlank()) append(persona).append("\n\n")
            append("Reply naturally in one or two short sentences. Do not mention this instruction.")
        }
        return try {
            com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(messages, "Start a natural conversation", prompt)
                ?: DEFAULT_MESSAGE
        } catch (e: Exception) {
            Log.w(TAG, "Message generation failed", e)
            DEFAULT_MESSAGE
        }
    }

    private suspend fun generateMessageAiDriven(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        val memo = MemoryStore.search(ctx, chatName, 2).joinToString("\n") { "- ${it.text}" }
        val prompt = buildString {
            append("Decide whether to proactively message the user. Return only a short message.\n")
            if (persona.isNotBlank()) append("Persona: ").append(persona).append('\n')
            history.takeLast(6).forEach { append(if (it.second) "User: " else "Assistant: ").append(it.first).append('\n') }
            if (memo.isNotBlank()) append("Memory:\n").append(memo)
        }
        return try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "You are the character in this chat.")
            reply?.trim()?.takeIf { it.isNotBlank() }?.take(100) ?: DEFAULT_MESSAGE
        } catch (e: Exception) {
            Log.w(TAG, "AI proactive generation failed", e)
            DEFAULT_MESSAGE
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
                NotificationChannel("proactive", "Proactive messages", NotificationManager.IMPORTANCE_HIGH)
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
    private const val DEFAULT_MESSAGE = "..."
}
