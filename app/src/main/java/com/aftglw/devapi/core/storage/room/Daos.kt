package com.aftglw.devapi.core.storage.room

import androidx.room.Dao
import androidx.room.Delete
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

// ── 消息 DAO（单聊 + 群聊共享） ──

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND is_group = :isGroup ORDER BY row_id ASC")
    fun getMessages(chatId: String, isGroup: Boolean = false): List<MessageEntity>

    @Query("SELECT text FROM messages WHERE chat_id = :chatId AND is_group = :isGroup ORDER BY row_id DESC LIMIT 1")
    fun getLastMessageText(chatId: String, isGroup: Boolean = false): String?

    @Insert
    fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chat_id = :chatId AND is_group = :isGroup")
    fun deleteForChat(chatId: String, isGroup: Boolean = false)

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
