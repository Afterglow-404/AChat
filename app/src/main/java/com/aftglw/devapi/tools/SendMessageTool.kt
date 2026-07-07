package com.aftglw.devapi.tools

import android.content.Context
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.aftglw.devapi.ChatHistory
import com.aftglw.devapi.MainActivity
import java.text.SimpleDateFormat
import java.util.*

class SendMessageTool : AiTool {
    override val name = "send_message"
    override val description = "主动给用户发一条消息（通知栏）。参数：text=消息内容"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        val text = args["text"] ?: return "缺少消息内容"
        val chatName = args["chat"] ?: "系统通知"
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        // 存到 ChatHistory
        ChatHistory.save(ctx, chatName,
            ChatHistory.load(ctx, chatName) + Triple(text, false, time))
        // 发通知
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            nm.createNotificationChannel(android.app.NotificationChannel("tool", "工具消息", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, chatName.hashCode(),
            Intent(ctx, MainActivity::class.java).apply { putExtra("open_chat", chatName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(chatName.hashCode(),
            android.app.Notification.Builder(ctx, "tool")
                .setContentTitle(chatName).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setAutoCancel(true).build())
        return "消息已发送：$text"
    }
}
