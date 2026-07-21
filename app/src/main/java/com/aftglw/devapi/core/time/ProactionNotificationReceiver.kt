package com.aftglw.devapi.core.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 处理主动消息通知上的 Action 按钮。
 *  - PROACTIVE_SILENCE_1H: 当前角色静音 1 小时
 */
class ProactionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        when (intent.action) {
            "com.aftglw.devapi.PROACTIVE_SILENCE_1H" -> {
                // 当前角色静音 1h（写入 proactive_silence_$chat，runOnce 顶部会跳过）
                val until = System.currentTimeMillis() + 3600_000L
                context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("proactive_silence_$chat", until)
                    .apply()
                Log.i("ProactiveNotif", "silence 1h for $chat")
                // 取消当前通知
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(chat.hashCode())
            }
        }
    }
}
