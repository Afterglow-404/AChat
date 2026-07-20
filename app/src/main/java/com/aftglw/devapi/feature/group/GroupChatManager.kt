package com.aftglw.devapi.feature.group

import android.content.Context
import android.util.Log
import com.aftglw.devapi.core.character.BuiltInCharacterLoader
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.GroupEntity
import com.aftglw.devapi.core.storage.room.MessageEntity
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMessage
import com.aftglw.devapi.model.GroupChatMode
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群聊持久化管理器。
 *
 * 底层已迁移到 Room：
 * - 群列表 → [GroupDao]（groups 表）
 * - 群历史 → [MessageDao]（messages 表，is_group=1）
 *
 * 公共 API 均为 `suspend fun`，调用方需在协程中调用。
 * Room 的同步 DAO 方法内部用 `withContext(Dispatchers.IO)` 切到 IO 线程。
 *
 * 注：成员角色（persona / avatarUri）查询仍读自 `chats` 表（单聊列表），因为群成员
 * 复用单聊角色定义；未命中则回退到内置角色。
 */
object GroupChatManager {

    private const val TAG = "GroupChatManager"

    // ── 群聊列表 ──

    suspend fun loadGroups(ctx: Context): List<GroupChat> = withContext(Dispatchers.IO) {
        AppDatabase.get(ctx).groupDao().getAll().map { it.toModel() }
    }

    suspend fun saveGroups(ctx: Context, groups: List<GroupChat>) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).groupDao()
            dao.deleteAll()
            if (groups.isNotEmpty()) dao.insertAll(groups.map { it.toEntity() })
        }
    }

    suspend fun saveGroup(ctx: Context, group: GroupChat) {
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).groupDao().upsert(group.toEntity())
        }
        android.util.Log.i(TAG, "Saved group: id=${group.id}, name=${group.name}, members=${group.members}")
    }

    suspend fun deleteGroup(ctx: Context, groupId: String) {
        withContext(Dispatchers.IO) {
            android.util.Log.i(TAG, "Deleting group: $groupId")
            val db = AppDatabase.get(ctx)
            db.withTransaction {
                db.groupDao().deleteById(groupId)
                db.messageDao().deleteForChat(groupId, isGroup = true)
            }
        }
    }

    // ── 群信息编辑 ──

    /** 重命名群聊 */
    suspend fun renameGroup(ctx: Context, groupId: String, newName: String) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).groupDao()
            val e = dao.getById(groupId) ?: return@withContext
            dao.upsert(e.copy(name = newName))
        }
    }

    /** 添加成员；若已在群则返回 false */
    suspend fun addMember(ctx: Context, groupId: String, memberName: String): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).groupDao()
        val e = dao.getById(groupId) ?: return@withContext false
        val members = parseMembers(e.members)
        if (members.contains(memberName)) return@withContext false
        dao.upsert(e.copy(members = JSONArray(members + memberName).toString()))
        true
    }

    /** 移除成员；若不在群则返回 false。成员数 < 2 时建议解散群 */
    suspend fun removeMember(ctx: Context, groupId: String, memberName: String): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).groupDao()
        val e = dao.getById(groupId) ?: return@withContext false
        val members = parseMembers(e.members)
        if (!members.contains(memberName)) return@withContext false
        dao.upsert(e.copy(members = JSONArray(members.filter { it != memberName }).toString()))
        true
    }

    // ── 历史消息 ──

    suspend fun loadMessages(ctx: Context, groupId: String): List<GroupChatMessage> = withContext(Dispatchers.IO) {
        AppDatabase.get(ctx).messageDao()
            .getMessages(groupId, isGroup = true)
            .map { it.toGroupMessage() }
    }

    suspend fun saveMessages(ctx: Context, groupId: String, messages: List<GroupChatMessage>) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(ctx)
            db.withTransaction {
                val mDao = db.messageDao()
                mDao.deleteForChat(groupId, isGroup = true)
                if (messages.isNotEmpty()) {
                    mDao.insertAll(messages.map { it.toEntity(groupId) })
                }
                // 同步更新 groups 中的 lastMessage 和 time，避免 loadGroups 时扫历史
                val lastMsg = messages.lastOrNull()
                val g = db.groupDao().getById(groupId)
                if (g != null && lastMsg != null) {
                    val displayText = if (lastMsg.from.isNotEmpty() && lastMsg.from != "user")
                        "${lastMsg.from}: ${lastMsg.text}" else lastMsg.text
                    db.groupDao().upsert(g.copy(lastMessage = displayText, time = lastMsg.time))
                }
            }
        }
    }

    /** Append one message without rewriting the entire group history. */
    suspend fun appendMessage(ctx: Context, groupId: String, message: GroupChatMessage) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(ctx)
            db.withTransaction {
                db.messageDao().insert(message.toEntity(groupId))
                val displayText = if (message.from.isNotEmpty() && message.from != "user")
                    "${message.from}: ${message.text}" else message.text
                db.groupDao().getById(groupId)?.let { group ->
                    db.groupDao().upsert(group.copy(lastMessage = displayText, time = message.time))
                }
            }
        }
    }

    /** Remove one persisted failed reply before retrying it. */
    suspend fun deleteFailedMessage(ctx: Context, groupId: String, message: GroupChatMessage) {
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).messageDao().deleteGroupMessage(
                chatId = groupId,
                fromName = message.from,
                time = message.time,
                text = message.text,
                isError = true
            )
        }
    }

    // ── 辅助 ──

    /** 读取指定成员的角色人设；先查 chats 表，未命中则查内置角色 */
    suspend fun getMemberPersona(ctx: Context, memberName: String): String = withContext(Dispatchers.IO) {
        val e = AppDatabase.get(ctx).chatDao().getAll().firstOrNull { it.name == memberName }
        e?.persona?.takeIf { it.isNotEmpty() }
            ?: BuiltInCharacterLoader.listAll(ctx)
                .firstOrNull { it.name == memberName }
                ?.persona ?: ""
    }

    /** 读取指定成员的头像路径；先查 chats 表，未命中则查内置角色 */
    suspend fun getMemberAvatarUri(ctx: Context, memberName: String): String = withContext(Dispatchers.IO) {
        val e = AppDatabase.get(ctx).chatDao().getAll().firstOrNull { it.name == memberName }
        e?.avatarUri?.takeIf { it.isNotEmpty() }
            ?: BuiltInCharacterLoader.listAll(ctx)
                .firstOrNull { it.name == memberName }
                ?.avatarUri ?: ""
    }

    /** 获取所有可用作群成员的角色（单聊角色 + 内置角色，按 name 去重） */
    suspend fun getAvailableMembers(ctx: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        // 1) chats 表中的自定义角色
        for (c in AppDatabase.get(ctx).chatDao().getAll()) {
            if (c.name.isNotEmpty() && c.persona.isNotEmpty() && seen.add(c.name)) {
                result.add(c.name to c.persona)
            }
        }
        // 2) 内置角色
        for (item in BuiltInCharacterLoader.listAll(ctx)) {
            if (item.name.isNotEmpty() && item.persona.isNotEmpty() && seen.add(item.name)) {
                result.add(item.name to item.persona)
            }
        }
        result
    }

    fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // ── 内部转换 ──

    private fun parseMembers(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { Log.w("GroupChatManager", "parseMembers failed", e); emptyList() }
    }
}

private fun GroupEntity.toModel(): GroupChat = GroupChat(
    id = id,
    name = name,
    members = try {
        val arr = JSONArray(members)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { Log.w("GroupChatManager", "parse group failed", e); emptyList() },
    lastMessage = lastMessage,
    time = time,
    avatarUri = avatarUri,
    mode = GroupChatMode.fromKey(mode),
    memberEnabled = parseMemberEnabled(memberSettings)
)

private fun GroupChat.toEntity(): GroupEntity {
    val membersJson = JSONArray(members).toString()
    return GroupEntity(
        id = id,
        name = name,
        members = membersJson,
        lastMessage = lastMessage,
        time = time,
        avatarUri = avatarUri,
        mode = mode.key,
        memberSettings = JSONObject().apply {
            memberEnabled.forEach { (member, enabled) -> put(member, enabled) }
        }.toString()
    )
}

private fun parseMemberEnabled(raw: String): Map<String, Boolean> {
    return try {
        val obj = JSONObject(raw)
        val result = mutableMapOf<String, Boolean>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.optBoolean(key, true)
        }
        result
    } catch (e: Exception) {
        Log.w("GroupChatManager", "parseMemberEnabled failed", e)
        emptyMap()
    }
}

private fun GroupChatMessage.toEntity(groupId: String): MessageEntity = MessageEntity(
    chatId = groupId,
    isGroup = true,
    isMe = isMe,
    text = text,
    time = time,
    imagePath = imagePath,
    voicePath = voicePath,
    voiceDuration = voiceDuration,
    voiceTranscript = voiceTranscript,
    fromName = from,
    isError = isError,
    retryPrompt = retryPrompt
)

private fun MessageEntity.toGroupMessage(): GroupChatMessage = GroupChatMessage(
    text = text,
    from = fromName ?: "",
    time = time,
    isMe = isMe,
    imagePath = imagePath,
    voicePath = voicePath,
    voiceDuration = voiceDuration,
    voiceTranscript = voiceTranscript,
    isError = isError,
    retryPrompt = retryPrompt
)

/**
 * 接话判断结果。
 *
 * @param name        被选中的成员名；null 表示应停止链式接话
 * @param willingness 接话意愿 0.0-1.0；低于阈值（默认 0.4）时调用方应停止推进
 * @param reason      判断理由（用于 debug 与日志）
 */
data class SpeakerDecision(
    val name: String?,
    val willingness: Float,
    val reason: String = ""
) {
    /** 语义快捷：是否建议停止 */
    fun shouldStop(threshold: Float = DEFAULT_WILLINGNESS_THRESHOLD): Boolean =
        name == null || willingness < threshold

    companion object {
        const val DEFAULT_WILLINGNESS_THRESHOLD = 0.4f
    }
}

/**
 * 轻量判断器：在群聊对话中，谁最应该接话。
 *
 * 两段式：
 * - [decideFast]：纯规则（@提及 > 仅剩1人 > 不可判断），可单元测试
 * - [decide]：在 [decideFast] 基础上叠加 LLM 兜底，返回带意愿评分的 [SpeakerDecision]
 *
 * LLM 协议：返回 `成员名|0.0-1.0|理由` 或 `STOP|0.0-1.0|理由`。
 */
object NextSpeakerJudge {

    /**
     * 纯规则判断（不调 LLM，可在 JVM 单元测试中验证）。
     *
     * 规则优先级：
     * 1. 全员已发言 → 停止（willingness=0）
     * 2. @提及某未发言成员 → 强意愿（1.0）
     * 3. 仅剩 1 个未发言 → 中等意愿（0.6）—— 仍可能话题已结束，由调用方按阈值裁决
     * 4. 多个未发言且无 @提及 → 返回 need_llm 标记，调用方应转 [decide]
     */
    fun decideFast(
        memberNames: List<String>,
        repliedNames: Set<String>,
        lastMessage: String
    ): SpeakerDecision {
        val unreplied = memberNames.filter { it !in repliedNames }
        if (unreplied.isEmpty()) return SpeakerDecision(null, 0f, "全员已发言")

        // 规则 1：@提及 → 复用 MentionParser 精确匹配（避免 take(2) 误匹配"小王" vs "王小明"）
        val mentioned = MentionParser.firstMention(lastMessage, unreplied)
        if (mentioned != null) {
            return SpeakerDecision(mentioned, 1.0f, "@提及")
        }

        // 规则 2：仅剩 1 个未发言，直接返回（意愿中等，可能话题已结束）
        if (unreplied.size == 1) {
            return SpeakerDecision(unreplied[0], 0.6f, "仅剩1人未发言")
        }

        // 多个未发言且无 @提及 → 交由 LLM
        return SpeakerDecision(null, 0f, "need_llm")
    }

    /**
     * 完整判断（含 LLM 兜底）。
     *
     * @param memberNames   所有成员名
     * @param repliedNames  本轮已发言成员（不再被选）
     * @param lastSpeaker   刚刚发言的人
     * @param lastMessage   刚刚发的消息内容
     * @param conversation  最近对话摘要（每行 "名字: 内容"）
     */
    suspend fun decide(
        memberNames: List<String>,
        repliedNames: Set<String>,
        lastSpeaker: String,
        lastMessage: String,
        conversation: String
    ): SpeakerDecision {
        val fast = decideFast(memberNames, repliedNames, lastMessage)
        if (fast.reason != "need_llm") return fast

        val unreplied = memberNames.filter { it !in repliedNames }
        return withContext(Dispatchers.IO) {
            val prompt = buildString {
                appendLine("群聊对话：")
                appendLine(conversation)
                appendLine()
                appendLine("刚才是 $lastSpeaker 说了：「$lastMessage」")
                appendLine("还未发言的成员：${unreplied.joinToString("、")}")
                appendLine()
                appendLine("判：")
                appendLine("1) $lastSpeaker 的话是否提到了未发言成员中的某个人，或话题明显与某个人的性格/领域相关？")
                appendLine("2) 如果是，回复：成员名|0.0-1.0 的接话意愿|一句话理由")
                appendLine("   例：钦灵|0.85|话题直接提到她")
                appendLine("3) 如果话题已自然结束或没必要继续，回复：STOP|0.0-0.4|理由")
                appendLine("   例：STOP|0.2|话题已结束")
                appendLine("只回复一行，不要多说。")
            }

            try {
                val t = System.currentTimeMillis()
                val result = com.aftglw.devapi.network.AiServiceFactory.getService().sendMessage(
                    history = emptyList(),
                    userMessage = prompt,
                    systemPrompt = "你是群聊对话分析器。只回复一行：成员名|意愿分数|理由 或 STOP|分数|理由。"
                )
                val raw = result?.trim()?.take(80) ?: ""
                android.util.Log.d("NextSpeakerJudge", "LLM raw: $raw (${unreplied.size} unreplied, ${System.currentTimeMillis() - t}ms)")

                val parts = raw.split("|").map { it.trim() }
                if (parts.isEmpty()) return@withContext SpeakerDecision(null, 0f, "LLM 空响应")

                val nameOrStop = parts[0]
                val score = parts.getOrNull(1)?.toFloatOrNull()
                    ?.coerceIn(0f, 1f) ?: 0.5f
                val reason = parts.getOrNull(2)?.take(40) ?: ""

                if (nameOrStop.equals("STOP", ignoreCase = true) || nameOrStop.equals("none", ignoreCase = true)) {
                    SpeakerDecision(null, score, reason.ifEmpty { "LLM 判定停止" })
                } else {
                    // 精确匹配成员名（避免 contains 误匹配）
                    val matched = memberNames.firstOrNull { it.equals(nameOrStop, ignoreCase = true) }
                    if (matched != null) SpeakerDecision(matched, score, reason.ifEmpty { "LLM 选择" })
                    else SpeakerDecision(null, score, "LLM 返回未匹配成员: $nameOrStop")
                }
            } catch (e: Exception) {
                SpeakerDecision(null, 0f, "LLM 异常: ${e.message?.take(40)}")
            }
        }
    }
}
