package com.aftglw.devapi.core.storage.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单聊会话条目 — 取代 wechat_chats SharedPreferences 中的 JSON 列表项。
 *
 * 与 [com.aftglw.devapi.model.ChatItem] 字段一一对应；avatarColor 以 int 形式存储
 * （旧实现序列化为 "#RRGGBB" 字符串，迁移时 parse 回 int）。
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "last_message") val lastMessage: String = "",
    @ColumnInfo(name = "time") val time: String = "",
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
    @ColumnInfo(name = "avatar_color") val avatarColor: Int = 0xFF07C160.toInt(),
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    @ColumnInfo(name = "persona") val persona: String = "",
    @ColumnInfo(name = "avatar_uri") val avatarUri: String = "",
    @ColumnInfo(name = "character_folder") val characterFolder: String = "",
    @ColumnInfo(name = "thinking_message") val thinkingMessage: String = ""
)

/**
 * 聊天消息条目 — 取代 chat_histories / group_histories 两个 SharedPreferences 中的 JSON 数组。
 *
 * 用 [isGroup] 区分单聊/群聊：
 * - 单聊：chatId = chatKey（ChatItem.id 或 name），fromName = null
 * - 群聊：chatId = groupId，fromName = 发言成员名（"user" 表示玩家）
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["chat_id", "is_group", "row_id"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "is_group") val isGroup: Boolean = false,
    @ColumnInfo(name = "is_me") val isMe: Boolean,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "time") val time: String = "",
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "voice_path") val voicePath: String? = null,
    @ColumnInfo(name = "voice_duration") val voiceDuration: Int = 0,
    @ColumnInfo(name = "voice_transcript") val voiceTranscript: String? = null,
    /** 群聊发言人名；单聊恒为 null */
    @ColumnInfo(name = "from_name") val fromName: String? = null,
    @ColumnInfo(name = "is_error") val isError: Boolean = false,
    @ColumnInfo(name = "retry_prompt") val retryPrompt: String? = null,
    /** 贴纸消息路径（assets 内相对路径）；非贴纸消息为 null */
    @ColumnInfo(name = "sticker_path") val stickerPath: String? = null,
    /** 外部事件来源 ID，例如主动消息审批队列的 PendingMessage.id。 */
    @ColumnInfo(name = "source_event_id") val sourceEventId: String? = null
)

/**
 * 群聊会话条目 — 取代 group_chats SharedPreferences 中的 JSON 列表项。
 *
 * members 以 JSON 数组字符串保存（与原格式一致），避免引入关联表。
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "members") val members: String = "[]",
    @ColumnInfo(name = "last_message") val lastMessage: String = "",
    @ColumnInfo(name = "time") val time: String = "",
    @ColumnInfo(name = "avatar_uri") val avatarUri: String = "",
    @ColumnInfo(name = "mode") val mode: String = "free",
    @ColumnInfo(name = "member_settings") val memberSettings: String = "{}"
)

/**
 * 世界书条目 — 取代 wechat_worldbook SharedPreferences 中以 "worldbook_$chatName" 为 key 的 JSON。
 *
 * originalId 保留旧实现中的条目 id（同 chatName 下唯一）；新条目创建时由 DAO 用 max+1 分配。
 */
@Entity(
    tableName = "worldbook",
    indices = [Index(value = ["chat_name"])]
)
data class WorldbookEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "chat_name") val chatName: String,
    @ColumnInfo(name = "original_id") val originalId: Long,
    @ColumnInfo(name = "keywords") val keywords: String = "",
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "constant") val constant: Boolean = false,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true
)

/**
 * 未完成事件 — AffectiveField 体系的 PendingEvents 持久化（设计文档 2.3.7）。
 *
 * 当用户问句被 AI 漏答、AI 提了话题用户没接等场景生成一条，
 * 由后续对话自然跟进，收尾后标记 resolved=true。
 *
 * 索引：(chat_name, resolved, archived) 用于快速查询某会话下未收尾、未归档的事件。
 */
@Entity(
    tableName = "pending_events",
    indices = [Index(value = ["chat_name", "resolved", "archived"])]
)
data class PendingEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "chat_name") val chatName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "trigger_text") val triggerText: String,
    @ColumnInfo(name = "weight") val weight: Float = 0.5f,
    @ColumnInfo(name = "closure_type") val closureType: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "resolved") val resolved: Boolean = false,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

/**
 * 已处理事件记录 — 幂等键（设计文档 14.8）。
 *
 * 每条 RelationshipEvent 的 eventId 被处理一次后写入此表。
 * 手机端 / Desktop / 服务器重复处理同一条消息时，第二次直接跳过。
 *
 * 定期清理 7 天前的记录（由 PendingEventStore 维护）。
 */
@Entity(
    tableName = "processed_events",
    indices = [Index(value = ["processed_at"])]
)
data class ProcessedEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "processed_at") val processedAt: Long,
)
