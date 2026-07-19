package com.aftglw.devapi.model

enum class GroupChatMode(
    val key: String,
    val title: String,
    val description: String
) {
    ROUND_ROBIN("round_robin", "轮流发言", "每次只由下一位成员回复"),
    MENTION_ONLY("mention_only", "仅 @ 回复", "只有明确 @ 某位成员时才回复"),
    FREE("free", "自由讨论", "由系统判断是否让其他成员接话");

    companion object {
        fun fromKey(key: String?): GroupChatMode =
            entries.firstOrNull { it.key == key } ?: FREE
    }
}

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
    val avatarUri: String = "",
    val mode: GroupChatMode = GroupChatMode.FREE,
    /** 群内单个成员是否参与 AI 发言，缺省为 true。 */
    val memberEnabled: Map<String, Boolean> = emptyMap()
)

/**
 * 群聊气泡消息 — 比单聊多了 from 字段标识发言人。
 */
data class GroupChatMessage(
    val text: String,
    /** 发言人名字，"user" 表示玩家自己，其他为群成员名 */
    val from: String,
    val time: String = "",
    val isMe: Boolean = false,
    /** 用户发送的图片本地路径（仅 isMe=true 时可能非空）；持久化到 group_histories */
    val imagePath: String? = null,
    val voicePath: String? = null,
    val voiceDuration: Int = 0,
    val voiceTranscript: String? = null
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
    val avatarUri: String = "",
    /** 内置角色文件夹名（非空表示这是内置角色，从 assets 加载） */
    val characterFolder: String = "",
    /** 思考文案（如"灵灵正在思考中..."） */
    val thinkingMessage: String = ""
) {
    /** 复制并覆盖部分字段，避免位置参数遗漏 */
    fun copy(
        id: String = this.id,
        name: String = this.name,
        lastMessage: String = this.lastMessage,
        time: String = this.time,
        unreadCount: Int = this.unreadCount,
        avatarColor: Int = this.avatarColor,
        pinned: Boolean = this.pinned,
        persona: String = this.persona,
        avatarUri: String = this.avatarUri,
        characterFolder: String = this.characterFolder,
        thinkingMessage: String = this.thinkingMessage
    ): ChatItem = ChatItem(id, name, lastMessage, time, unreadCount, avatarColor, pinned, persona, avatarUri, characterFolder, thinkingMessage)
}
