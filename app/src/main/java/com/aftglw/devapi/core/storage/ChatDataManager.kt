package com.aftglw.devapi.core.storage

import android.content.Context
import com.aftglw.devapi.core.storage.room.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天数据管理：导出 / 清空。
 *
 * 导出格式：JSON
 * ```
 * {
 *   "exported_at": "2026-07-19 12:34:56",
 *   "chats": [
 *     { "id": "...", "name": "...", "persona": "...", "messages": [...] },
 *     ...
 *   ],
 *   "groups": [
 *     { "id": "...", "name": "...", "members": [...], "messages": [...] },
 *     ...
 *   ]
 * }
 * ```
 *
 * 清空：清除 Room 中所有 chats / messages / groups / worldbook 表。
 *      SharedPreferences 中与聊天相关的 key（proactive/mood/affinity）也一并清理。
 */
object ChatDataManager {

    /** 导出全部聊天数据为 JSON 字符串 */
    fun exportAll(ctx: Context): String {
        val db = AppDatabase.get(ctx)
        val chats = db.chatDao().getAll()
        val groups = db.groupDao().getAll()
        val msgDao = db.messageDao()

        val chatsJson = JSONArray()
        for (c in chats) {
            val msgs = msgDao.getMessages(c.id, isGroup = false)
            val msgArray = JSONArray()
            for (m in msgs) {
                msgArray.put(JSONObject().apply {
                    put("is_me", m.isMe)
                    put("text", m.text)
                    put("time", m.time)
                    if (!m.imagePath.isNullOrBlank()) put("image_path", m.imagePath)
                    if (!m.voicePath.isNullOrBlank()) put("voice_path", m.voicePath)
                    if (m.voiceDuration > 0) put("voice_duration", m.voiceDuration)
                    if (!m.voiceTranscript.isNullOrBlank()) put("voice_transcript", m.voiceTranscript)
                })
            }
            chatsJson.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("persona", c.persona)
                put("avatar_uri", c.avatarUri)
                put("character_folder", c.characterFolder)
                put("pinned", c.pinned)
                put("messages", msgArray)
            })
        }

        val groupsJson = JSONArray()
        for (g in groups) {
            val msgs = msgDao.getMessages(g.id, isGroup = true)
            val msgArray = JSONArray()
            for (m in msgs) {
                msgArray.put(JSONObject().apply {
                    put("is_me", m.isMe)
                    put("from_name", m.fromName ?: "")
                    put("text", m.text)
                    put("time", m.time)
                })
            }
            groupsJson.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("members", g.members)
                put("messages", msgArray)
            })
        }

        val export = JSONObject().apply {
            put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("app_version", "Wisp 0.0.1")
            put("chats", chatsJson)
            put("groups", groupsJson)
        }
        return export.toString(2)
    }

    /**
     * 清空所有聊天数据。
     *
     * Room 表：chats / messages / groups / worldbook 全部 delete
     * SharedPreferences：proactive_daily_limit_* / proactive_silence_* / proactive_need_care_*
     *                   affinity_* / mood_last_* 等聊天相关 key
     *
     * @return 被清理的聊天数（不含群聊）
     */
    fun clearAll(ctx: Context): Int {
        val db = AppDatabase.get(ctx)
        val chatCount = db.chatDao().getAll().size
        db.runInTransaction {
            db.chatDao().deleteAll()
            db.groupDao().deleteAll()
            db.messageDao().deleteAll()
            db.worldbookDao().deleteAll()
        }
        // 清理聊天相关的 SharedPreferences 键
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { key ->
            key.startsWith("proactive_daily_limit_") ||
            key.startsWith("proactive_silence_") ||
            key.startsWith("proactive_need_care_") ||
            key.startsWith("affinity_") ||
            key.startsWith("mood_last_") ||
            key.startsWith("auto_archive_") ||
            key.startsWith("last_active_chat") ||
            key.startsWith("builtin_seeded_")
        }
        if (keysToRemove.isNotEmpty()) {
            prefs.edit().apply {
                keysToRemove.forEach { remove(it) }
            }.apply()
        }
        return chatCount
    }

    /** 删除单个聊天的所有数据（Room + SharedPreferences 关联键） */
    fun deleteChat(ctx: Context, chatId: String, chatName: String) {
        val db = AppDatabase.get(ctx)
        db.runInTransaction {
            db.chatDao().deleteById(chatId)
            db.messageDao().deleteForChat(chatId, isGroup = false)
            db.worldbookDao().deleteForChat(chatName)
        }
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { key ->
            key.endsWith("_$chatName") && (
                key.startsWith("proactive_daily_limit_") ||
                key.startsWith("proactive_silence_") ||
                key.startsWith("proactive_need_care_") ||
                key.startsWith("affinity_") ||
                key.startsWith("auto_archive_")
            )
        }
        if (keysToRemove.isNotEmpty()) {
            prefs.edit().apply { keysToRemove.forEach { remove(it) } }.apply()
        }
    }
}
