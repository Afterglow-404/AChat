package com.aftglw.devapi.model

/**
 * 群聊数据模型 — 与 ChatItem 平行，用于群聊列表展示。
 */
data class GroupChat(
    val id: String,
    val name: String,
    /** 群成员名列表（对应单聊的 chatName，每个成员需有独立的 persona） */
    val members: List<String>,
    val lastMessage: String = "",
    val time: String = "",
    val avatarUri: String = ""
)

/**
 * 群聊气泡消息 — 比单聊多了 from 字段标识发言人。
 */
data class GroupChatMessage(
    val text: String,
    /** 发言人名字，"user" 表示玩家自己，其他为群成员名 */
    val from: String,
    val time: String = "",
    val isMe: Boolean = false
)

class ChatItem(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    var unreadCount: Int,
    val avatarColor: Int,
    val pinned: Boolean = false,
    val persona: String = "",
    val avatarUri: String = ""
)
