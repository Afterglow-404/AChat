package com.aftglw.devapi.core.storage.room.migration

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.ChatEntity
import com.aftglw.devapi.core.storage.room.GroupEntity
import com.aftglw.devapi.core.storage.room.MessageEntity
import com.aftglw.devapi.core.storage.room.WorldbookEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从旧 SharedPreferences/JSON 一次性迁移到 Room。
 *
 * 触发条件：`wechat_settings.room_migrated_v1` 标记为 false（默认）。
 * 迁移完成后立即写入标记，避免重复执行。
 *
 * 迁移源：
 * - `wechat_chats` ("chats" key, JSONArray) → chats 表
 * - `chat_histories` (每个 chatName key, JSONArray) → messages 表 (is_group=0)
 * - `group_chats` ("groups" key, JSONArray) → groups 表
 * - `group_histories` (每个 groupId key, JSONArray) → messages 表 (is_group=1)
 * - `wechat_worldbook` (worldbook_* keys, JSONArray) → worldbook 表
 *
 * 迁移是幂等的：旧数据并不删除，重复执行会因主键冲突跳过（chats/groups 用 REPLACE）；
 * messages 表无主键约束（autoGenerate），所以仅在首次执行。
 */
object LegacyMigrator {

    private const val TAG = "LegacyMigrator"
    private const val MIGRATION_FLAG = "room_migrated_v1"

    private const val PREFS_SETTINGS = "wechat_settings"
    private const val PREFS_CHATS = "wechat_chats"
    private const val PREFS_CHAT_HISTORIES = "chat_histories"
    private const val PREFS_GROUPS = "group_chats"
    private const val PREFS_GROUP_HISTORIES = "group_histories"
    private const val PREFS_WORLDBOOK = "wechat_worldbook"

    @Synchronized
    fun migrateIfFirstRun(ctx: Context, db: AppDatabase) {
        val settings = ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        if (settings.getBoolean(MIGRATION_FLAG, false)) return

        runBlocking(Dispatchers.IO) {
            try {
                db.withTransaction {
                    migrateChats(ctx, db)
                    migrateChatHistories(ctx, db)
                    migrateGroups(ctx, db)
                    migrateGroupHistories(ctx, db)
                    migrateWorldbook(ctx, db)
                }
                settings.edit().putBoolean(MIGRATION_FLAG, true).apply()
                Log.i(TAG, "Legacy data migrated to Room successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Legacy migration failed; will retry next launch", e)
                // 不写标记，下次启动重试
            }
        }
    }

    private fun migrateChats(ctx: Context, db: AppDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) return
        val entities = (0 until arr.length()).mapNotNull { i ->
            try {
                val obj = arr.getJSONObject(i)
                ChatEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    lastMessage = obj.optString("lastMessage", ""),
                    time = obj.optString("time", ""),
                    unreadCount = obj.optInt("unreadCount", 0),
                    avatarColor = parseColor(obj.optString("avatarColor", "#07C160")),
                    pinned = obj.optBoolean("pinned", false),
                    persona = obj.optString("persona", ""),
                    avatarUri = obj.optString("avatarUri", ""),
                    characterFolder = obj.optString("characterFolder", ""),
                    thinkingMessage = obj.optString("thinkingMessage", "")
                )
            } catch (_: Exception) { null }
        }
        if (entities.isNotEmpty()) {
            db.chatDao().insertAll(entities)
            Log.i(TAG, "Migrated ${entities.size} chats")
        }
    }

    private fun migrateChatHistories(ctx: Context, db: AppDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS_CHAT_HISTORIES, Context.MODE_PRIVATE)
        val all = prefs.all
        var total = 0
        for ((chatName, json) in all) {
            val arr = try { JSONArray(json as? String ?: "[]") } catch (_: Exception) { continue }
            if (arr.length() == 0) continue
            val msgs = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MessageEntity(
                    chatId = chatName,
                    isGroup = false,
                    isMe = obj.optBoolean("isMe", false),
                    text = obj.optString("text", ""),
                    time = obj.optString("time", ""),
                    imagePath = obj.optString("imagePath", "").ifEmpty { null },
                    fromName = null
                )
            }
            db.messageDao().insertAll(msgs)
            total += msgs.size
        }
        if (total > 0) Log.i(TAG, "Migrated $total chat messages")
    }

    private fun migrateGroups(ctx: Context, db: AppDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS_GROUPS, Context.MODE_PRIVATE)
        val json = prefs.getString("groups", "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) return
        val entities = (0 until arr.length()).mapNotNull { i ->
            try {
                val obj = arr.getJSONObject(i)
                GroupEntity(
                    id = obj.getString("id"),
                    name = obj.optString("name", "群聊"),
                    members = obj.optJSONArray("members")?.toString() ?: "[]",
                    lastMessage = obj.optString("lastMessage", ""),
                    time = obj.optString("time", ""),
                    avatarUri = obj.optString("avatarUri", "")
                )
            } catch (_: Exception) { null }
        }
        if (entities.isNotEmpty()) {
            db.groupDao().insertAll(entities)
            Log.i(TAG, "Migrated ${entities.size} groups")
        }
    }

    private fun migrateGroupHistories(ctx: Context, db: AppDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS_GROUP_HISTORIES, Context.MODE_PRIVATE)
        val all = prefs.all
        var total = 0
        for ((groupId, json) in all) {
            val arr = try { JSONArray(json as? String ?: "[]") } catch (_: Exception) { continue }
            if (arr.length() == 0) continue
            val msgs = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MessageEntity(
                    chatId = groupId,
                    isGroup = true,
                    isMe = obj.optBoolean("isMe", false),
                    text = obj.optString("text", ""),
                    time = obj.optString("time", ""),
                    imagePath = obj.optString("imagePath", "").ifEmpty { null },
                    fromName = obj.optString("from", "")
                )
            }
            db.messageDao().insertAll(msgs)
            total += msgs.size
        }
        if (total > 0) Log.i(TAG, "Migrated $total group messages")
    }

    private fun migrateWorldbook(ctx: Context, db: AppDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS_WORLDBOOK, Context.MODE_PRIVATE)
        val all = prefs.all
        var total = 0
        for ((key, json) in all) {
            if (!key.startsWith("worldbook_")) continue
            val chatName = key.removePrefix("worldbook_")
            val arr = try { JSONArray(json as? String ?: "[]") } catch (_: Exception) { continue }
            if (arr.length() == 0) continue
            val entries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                WorldbookEntity(
                    chatName = chatName,
                    originalId = obj.optLong("id", 0L),
                    keywords = obj.optString("keywords", ""),
                    content = obj.optString("content", ""),
                    priority = obj.optInt("priority", 0),
                    constant = obj.optBoolean("constant", false),
                    enabled = obj.optBoolean("enabled", true)
                )
            }
            db.worldbookDao().insertAll(entries)
            total += entries.size
        }
        if (total > 0) Log.i(TAG, "Migrated $total worldbook entries")
    }

    /** "#07C160" → 0xFF07C160 (Int) */
    internal fun parseColor(hex: String): Int {
        return try {
            if (hex.startsWith("#")) {
                val v = hex.removePrefix("#").toLong(16)
                if (hex.length == 7) (0xFF000000 or v).toInt() else v.toInt()
            } else {
                0xFF07C160.toInt()
            }
        } catch (_: Exception) {
            0xFF07C160.toInt()
        }
    }
}
