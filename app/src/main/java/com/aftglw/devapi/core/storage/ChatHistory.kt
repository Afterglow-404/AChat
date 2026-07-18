package com.aftglw.devapi.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条聊天历史的持久化表示。
 * @param text 文本内容（图片消息可为占位符如 "[图片]"）
 * @param isMe 是否本人发送
 * @param time HH:mm 时间戳
 * @param imagePath 本地图片绝对路径（仅图片消息有值，重启后仍有效）
 */
data class ChatHistoryEntry(
    val text: String,
    val isMe: Boolean,
    val time: String,
    val imagePath: String? = null
)

object ChatHistory {
    private const val PREFS = "chat_histories"

    /** 旧 API 保留：纯文本三元组。内部转发到新 API。 */
    fun load(context: Context, chatName: String): List<Triple<String, Boolean, String>> {
        return loadEntries(context, chatName).map { Triple(it.text, it.isMe, it.time) }
    }

    fun loadEntries(context: Context, chatName: String): List<ChatHistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(chatName, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<ChatHistoryEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val imgPath = obj.optString("imagePath", "").ifEmpty { null }
            result.add(ChatHistoryEntry(
                text = obj.getString("text"),
                isMe = obj.getBoolean("isMe"),
                time = obj.optString("time", ""),
                imagePath = imgPath
            ))
        }
        return result
    }

    /** 旧 API 保留：纯文本三元组。内部转发到新 API。 */
    fun save(context: Context, chatName: String, messages: List<Triple<String, Boolean, String>>) {
        saveEntries(context, chatName, messages.map { ChatHistoryEntry(it.first, it.second, it.third) })
    }

    fun saveEntries(context: Context, chatName: String, messages: List<ChatHistoryEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().apply {
                put("text", m.text)
                put("isMe", m.isMe)
                put("time", m.time)
                if (!m.imagePath.isNullOrEmpty()) put("imagePath", m.imagePath)
            })
        }
        prefs.edit().putString(chatName, arr.toString()).apply()
    }
}
