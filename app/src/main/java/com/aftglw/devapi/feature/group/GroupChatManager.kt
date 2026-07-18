package com.aftglw.devapi.feature.group

import android.content.Context
import android.content.SharedPreferences
import com.aftglw.devapi.core.character.BuiltInCharacterLoader
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMessage
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
 * 数据存储在两组 SharedPreferences 中：
 * - `group_chats`   → key `"groups"` → JSONArray<GroupChat>
 * - `group_histories` → key `groupId`  → JSONArray<GroupChatMessage>
 */
object GroupChatManager {

    private const val TAG = "GroupChatManager"
    private const val GROUP_PREFS = "group_chats"
    private const val GROUP_KEY = "groups"
    private const val HIST_PREFS = "group_histories"
    private const val MEMBER_PREFS = "wechat_chats"
    private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ── 群聊列表 ──

    fun loadGroups(ctx: Context): List<GroupChat> {
        val prefs = ctx.getSharedPreferences(GROUP_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(GROUP_KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<GroupChat>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val members = obj.getJSONArray("members").let { arr2 ->
                (0 until arr2.length()).map { arr2.getString(it) }
            }
            result.add(GroupChat(
                id = id,
                name = obj.optString("name", "群聊"),
                members = members,
                lastMessage = obj.optString("lastMessage", ""),
                time = obj.optString("time", ""),
                avatarUri = obj.optString("avatarUri", "")
            ))
        }
        return result
    }

    fun saveGroups(ctx: Context, groups: List<GroupChat>) {
        val prefs = ctx.getSharedPreferences(GROUP_PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (g in groups) {
            val obj = JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("members", JSONArray(g.members))
                put("lastMessage", g.lastMessage)
                put("time", g.time)
                put("avatarUri", g.avatarUri)
            }
            arr.put(obj)
        }
        prefs.edit().putString(GROUP_KEY, arr.toString()).apply()
    }

    fun saveGroup(ctx: Context, group: GroupChat) {
        val groups = loadGroups(ctx).toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else { groups.add(group); android.util.Log.i(TAG, "Created group: id=${group.id}, name=${group.name}, members=${group.members}") }
        saveGroups(ctx, groups)
    }

    fun deleteGroup(ctx: Context, groupId: String) {
        android.util.Log.i(TAG, "Deleting group: $groupId")
        val groups = loadGroups(ctx).filter { it.id != groupId }
        saveGroups(ctx, groups)
        ctx.getSharedPreferences(HIST_PREFS, Context.MODE_PRIVATE)
            .edit().remove(groupId).apply()
    }

    // ── 群信息编辑 ──

    /** 重命名群聊 */
    fun renameGroup(ctx: Context, groupId: String, newName: String) {
        val groups = loadGroups(ctx).toMutableList()
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            groups[idx] = groups[idx].copy(name = newName)
            saveGroups(ctx, groups)
        }
    }

    /** 添加成员；若已在群则返回 false */
    fun addMember(ctx: Context, groupId: String, memberName: String): Boolean {
        val groups = loadGroups(ctx).toMutableList()
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return false
        val g = groups[idx]
        if (g.members.contains(memberName)) return false
        groups[idx] = g.copy(members = g.members + memberName)
        saveGroups(ctx, groups)
        return true
    }

    /** 移除成员；若不在群则返回 false。成员数 < 2 时建议解散群 */
    fun removeMember(ctx: Context, groupId: String, memberName: String): Boolean {
        val groups = loadGroups(ctx).toMutableList()
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return false
        val g = groups[idx]
        if (!g.members.contains(memberName)) return false
        groups[idx] = g.copy(members = g.members.filter { it != memberName })
        saveGroups(ctx, groups)
        return true
    }

    // ── 历史消息 ──

    fun loadMessages(ctx: Context, groupId: String): List<GroupChatMessage> {
        val prefs = ctx.getSharedPreferences(HIST_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(groupId, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            GroupChatMessage(
                text = obj.optString("text", ""),
                from = obj.optString("from", ""),
                time = obj.optString("time", ""),
                isMe = obj.optBoolean("isMe", false),
                imagePath = obj.optString("imagePath", "").ifEmpty { null }
            )
        }
    }

    fun saveMessages(ctx: Context, groupId: String, messages: List<GroupChatMessage>) {
        val prefs = ctx.getSharedPreferences(HIST_PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (m in messages) {
            val obj = JSONObject().apply {
                put("text", m.text)
                put("from", m.from)
                put("time", m.time)
                put("isMe", m.isMe)
                if (!m.imagePath.isNullOrEmpty()) put("imagePath", m.imagePath)
            }
            arr.put(obj)
        }
        prefs.edit().putString(groupId, arr.toString()).apply()

        // 同步更新 groups 中的 lastMessage 和 time，避免 loadGroups 时扫历史
        val lastMsg = messages.lastOrNull() ?: return
        val groupPrefs = ctx.getSharedPreferences(GROUP_PREFS, Context.MODE_PRIVATE)
        val json = groupPrefs.getString(GROUP_KEY, "[]") ?: "[]"
        val groupsArr = JSONArray(json)
        for (i in 0 until groupsArr.length()) {
            val obj = groupsArr.getJSONObject(i)
            if (obj.getString("id") == groupId) {
                val displayText = if (lastMsg.from.isNotEmpty() && lastMsg.from != "user")
                    "${lastMsg.from}: ${lastMsg.text}" else lastMsg.text
                obj.put("lastMessage", displayText)
                obj.put("time", lastMsg.time)
                break
            }
        }
        groupPrefs.edit().putString(GROUP_KEY, groupsArr.toString()).apply()
    }

    // ── 辅助 ──

    /** 读取指定成员的角色人设；先查 wechat_chats，未命中则查内置角色 */
    fun getMemberPersona(ctx: Context, memberName: String): String {
        val prefs = ctx.getSharedPreferences(MEMBER_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("name", "") == memberName) {
                return obj.optString("persona", "")
            }
        }
        // 兜底：内置角色
        return BuiltInCharacterLoader.listAll(ctx)
            .firstOrNull { it.name == memberName }
            ?.persona ?: ""
    }

    /** 读取指定成员的头像路径；先查 wechat_chats，未命中则查内置角色 */
    fun getMemberAvatarUri(ctx: Context, memberName: String): String {
        val prefs = ctx.getSharedPreferences(MEMBER_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("name", "") == memberName) {
                return obj.optString("avatarUri", "")
            }
        }
        // 兜底：内置角色
        return BuiltInCharacterLoader.listAll(ctx)
            .firstOrNull { it.name == memberName }
            ?.avatarUri ?: ""
    }

    /** 获取所有可用作群成员的角色（单聊角色 + 内置角色，按 name 去重） */
    fun getAvailableMembers(ctx: Context): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        // 1) wechat_chats 自定义角色
        val prefs = ctx.getSharedPreferences(MEMBER_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "")
            val persona = obj.optString("persona", "")
            if (name.isNotEmpty() && persona.isNotEmpty() && seen.add(name)) {
                result.add(name to persona)
            }
        }
        // 2) 内置角色
        for (item in BuiltInCharacterLoader.listAll(ctx)) {
            if (item.name.isNotEmpty() && item.persona.isNotEmpty() && seen.add(item.name)) {
                result.add(item.name to item.persona)
            }
        }
        return result
    }

    fun now(): String = TIME_FMT.format(Date())
}

/**
 * 轻量判断器：在群聊对话中，谁最应该接话。
 * 先走快速规则（@提及 > 随机），LLM 兜底。
 */
object NextSpeakerJudge {

    /**
     * @param memberNames   所有成员名
     * @param repliedNames  本轮已发言成员（不再被选）
     * @param lastSpeaker   刚刚发言的人
     * @param lastMessage   刚刚发的消息内容
     * @param conversation  最近对话摘要（每行 "名字: 内容"）
     * @return 应接话的成员名，或 null 表示停止
     */
    suspend fun decide(
        memberNames: List<String>,
        repliedNames: Set<String>,
        lastSpeaker: String,
        lastMessage: String,
        conversation: String
    ): String? {
        val unreplied = memberNames.filter { it !in repliedNames }
        if (unreplied.isEmpty()) return null

        // 快速规则 1：@提及 → 优先让被提及的人接话
        for (name in unreplied) {
            if (lastMessage.contains("@$name") || lastMessage.contains("@${name.take(2)}")) {
                return name
            }
        }

        // 快速规则 2：如果只剩 1 个未发言，直接选
        if (unreplied.size == 1) return unreplied[0]

        // LLM 兜底：用协程在 IO 线程执行阻塞调用
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
                appendLine("2) 如果有，只回复那个成员的名字（一个字不要多说）。")
                appendLine("3) 如果没有或话题已结束，回复 none。")
                appendLine()
                append("回答：")
            }

            try {
                val t = System.currentTimeMillis()
                val result = com.aftglw.devapi.network.AiServiceFactory.getService().sendMessage(
                    history = emptyList(),
                    userMessage = prompt,
                    systemPrompt = "你是群聊对话分析器。只回复一个成员名或 none，不多说。"
                )
                val name = result?.trim()?.take(20) ?: ""
                android.util.Log.d("NextSpeakerJudge", "Decided: $name (${unreplied.size} unreplied, ${System.currentTimeMillis() - t}ms)")
                if (name.equals("none", ignoreCase = true)) null
                else memberNames.firstOrNull { it.equals(name, ignoreCase = true) || it.contains(name, ignoreCase = true) }
            } catch (_: Exception) { null }
        }
    }
}
