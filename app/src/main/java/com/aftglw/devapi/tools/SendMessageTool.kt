package com.aftglw.devapi.tools

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aftglw.devapi.ChatHistory
import com.aftglw.devapi.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class SendMessageTool : AiTool {
    override val name = "send_message"
    override val description = "主动给用户发一条通知栏消息，或者保存到特定角色的对话记录"

    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "消息内容")
            })
            put("chat", JSONObject().apply {
                put("type", "string")
                put("description", "以哪个角色/对话的名义发送（可选，默认 系统通知）")
            })
        })
        val req = JSONArray()
        req.put("text")
        put("required", req)
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val text = args.optString("text", "").ifEmpty { return "缺少消息内容" }
        val chatName = args.optString("chat", "系统通知")
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        ChatHistory.save(ctx, chatName,
            ChatHistory.load(ctx, chatName) + Triple(text, false, time))

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                android.app.NotificationChannel("mcp_tool", "工具消息", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, chatName.hashCode(),
            Intent(ctx, MainActivity::class.java).apply { putExtra("open_chat", chatName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(chatName.hashCode(),
            android.app.Notification.Builder(ctx, "mcp_tool")
                .setContentTitle(chatName).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setAutoCancel(true).build())

        return "消息已发送给「${chatName}」：${text}"
    }
}
