package com.aftglw.devapi.core.storage.room

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

// ── 单聊列表 DAO ──

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, name ASC")
    fun getAll(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    fun getById(id: String): ChatEntity?

    /**
     * 一次 JOIN 取所有 chat + 各自最后一条消息文本，替代 [getAll] + 逐条 [MessageDao.getLastMessageText] 的 N+1 查询。
     *
     * 关联键：messages.chat_id = chats.name（与 [MessageDao.getLastMessageText] 一致，单聊用 chat.name 作为 chatId）。
     * is_group = 0 过滤单聊消息。
     */
    @Query(
        """
        SELECT c.*, (
            SELECT m.text FROM messages m
            WHERE m.chat_id = c.name AND m.is_group = 0
            ORDER BY m.row_id DESC LIMIT 1
        ) AS last_text
        FROM chats c
        ORDER BY c.pinned DESC, c.name ASC
        """
    )
    suspend fun getAllWithLastMessage(): List<ChatWithLastMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(chats: List<ChatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(chat: ChatEntity)

    @Update
    fun update(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM chats")
    fun deleteAll()
}

/**
 * Chat 与其最后一条消息文本的 JOIN 结果。
 *
 * [chat] 嵌入完整 ChatEntity；[lastText] 为该 chat 的最后一条消息文本，无消息时为 null。
 */
data class ChatWithLastMessage(
    @Embedded val chat: ChatEntity,
    @ColumnInfo(name = "last_text") val lastText: String?
)

// ── 消息 DAO（单聊 + 群聊共享） ──

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND is_group = :isGroup ORDER BY row_id ASC")
    fun getMessages(chatId: String, isGroup: Boolean = false): List<MessageEntity>

    @Query("SELECT text FROM messages WHERE chat_id = :chatId AND is_group = :isGroup ORDER BY row_id DESC LIMIT 1")
    fun getLastMessageText(chatId: String, isGroup: Boolean = false): String?

    /**
     * 一次查询所有包含文本 [q] 的 chatId，替代 [ChatsViewModel.setSearchQuery] 中
     * 「遍历每个 chat → getMessages → 内存过滤」的 N+1 模式。
     *
     * 返回值是 chatId 集合（与 [MessageEntity.chatId] 一致），调用方据此 filter [ChatDao.getAll] 结果。
     * 通配符由调用方附加：调用时传 `"%$q%"`。
     */
    @Query("SELECT DISTINCT chat_id FROM messages WHERE text LIKE :q")
    suspend fun searchChatIds(q: String): List<String>

    @Insert
    fun insertAll(messages: List<MessageEntity>)

    @Insert
    fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE chat_id = :chatId AND is_group = :isGroup")
    fun deleteForChat(chatId: String, isGroup: Boolean = false)

    @Query("DELETE FROM messages WHERE chat_id = :chatId AND is_group = :isGroup AND from_name = :fromName AND time = :time AND text = :text AND is_error = :isError")
    fun deleteGroupMessage(
        chatId: String,
        fromName: String,
        time: String,
        text: String,
        isError: Boolean = true,
        isGroup: Boolean = true
    )

    @Query("DELETE FROM messages")
    fun deleteAll()
}

// ── 群聊列表 DAO ──

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name ASC")
    fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    fun getById(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(groups: List<GroupEntity>)

    @Query("DELETE FROM groups WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM groups")
    fun deleteAll()
}

// ── 世界书 DAO ──

@Dao
interface WorldbookDao {
    @Query("SELECT * FROM worldbook WHERE chat_name = :chatName ORDER BY original_id ASC")
    fun getForChat(chatName: String): List<WorldbookEntity>

    @Query("SELECT MAX(original_id) FROM worldbook WHERE chat_name = :chatName")
    fun getMaxOriginalId(chatName: String): Long?

    @Query("SELECT * FROM worldbook WHERE chat_name = :cn AND original_id = :oid LIMIT 1")
    suspend fun getByChatAndOriginalId(cn: String, oid: Long): WorldbookEntity?

    @Insert
    fun insertAll(entries: List<WorldbookEntity>)

    @Insert
    fun insert(entry: WorldbookEntity): Long

    @Update
    fun update(entry: WorldbookEntity)

    @Query("DELETE FROM worldbook WHERE row_id = :rowId")
    fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM worldbook WHERE chat_name = :chatName")
    fun deleteForChat(chatName: String)

    @Query("DELETE FROM worldbook")
    fun deleteAll()
}

// ── 未完成事件 DAO（AffectiveField 体系） ──

@Dao
interface PendingEventDao {
    @Query("SELECT * FROM pending_events WHERE chat_name = :chatName AND resolved = 0 AND archived = 0 ORDER BY created_at ASC")
    suspend fun getActiveForChat(chatName: String): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE chat_name = :chatName AND resolved = 0 AND archived = 0 AND attempt_count = 0 ORDER BY created_at ASC")
    suspend fun getUnattemptedForChat(chatName: String): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingEventEntity?

    @Query("SELECT * FROM pending_events WHERE chat_name = :chatName ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentForChat(chatName: String, limit: Int = 20): List<PendingEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: PendingEventEntity)

    @Query("UPDATE pending_events SET attempt_count = :attemptCount, last_attempt_at = :lastAttemptAt WHERE id = :id")
    suspend fun updateAttempt(id: String, attemptCount: Int, lastAttemptAt: Long)

    @Query("UPDATE pending_events SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: String)

    @Query("UPDATE pending_events SET archived = 1 WHERE id = :id")
    suspend fun markArchived(id: String)

    @Query("DELETE FROM pending_events WHERE chat_name = :chatName")
    suspend fun deleteForChat(chatName: String)

    @Query("DELETE FROM pending_events")
    suspend fun deleteAll()
}

// ── 已处理事件 DAO（幂等键，设计文档 14.8） ──

@Dao
interface ProcessedEventDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_events WHERE event_id = :eventId)")
    suspend fun isProcessed(eventId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markProcessed(event: ProcessedEventEntity)

    @Query("DELETE FROM processed_events WHERE processed_at < :before")
    suspend fun cleanupOlderThan(before: Long): Int

    @Query("DELETE FROM processed_events")
    suspend fun deleteAll()
}
