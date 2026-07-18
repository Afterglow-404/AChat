package com.aftglw.devapi.feature.group

import android.content.Context
import android.content.SharedPreferences
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMessage
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

    // ── 群聊列表 ──

    fun loadGroups(ctx: Context): List<GroupChat> {
        val prefs = ctx.getSharedPreferences(GROUP_PREFS, Context.MODE_PRIVATE)
        val histPrefs = ctx.getSharedPreferences(HIST_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(GROUP_KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<GroupChat>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val members = obj.getJSONArray("members").let { arr2 ->
                (0 until arr2.length()).map { arr2.getString(it) }
            }
            // 取最后一条消息作为摘要
            val histJson = histPrefs.getString(id, "[]") ?: "[]"
            val histArr = JSONArray(histJson)
            val lastMsg = if (histArr.length() > 0) {
                val last = histArr.getJSONObject(histArr.length() - 1)
                val from = last.optString("from", "")
                val text = last.optString("text", "")
                if (from.isNotEmpty() && from != "user") "$from: $text" else text
            } else ""

            result.add(GroupChat(
                id = id,
                name = obj.optString("name", "群聊"),
                members = members,
                lastMessage = lastMsg,
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
                isMe = obj.optBoolean("isMe", false)
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
            }
            arr.put(obj)
        }
        prefs.edit().putString(groupId, arr.toString()).apply()
    }

    // ── 辅助 ──

    /** 从 wechat_chats 读取指定成员的角色人设 */
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
        return ""
    }

    /** 从 wechat_chats 读取指定成员的头像路径 */
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
        return ""
    }

    /** 获取所有可用作群成员的单聊角色（有 persona 的） */
    fun getAvailableMembers(ctx: Context): List<Pair<String, String>> {
        val prefs = ctx.getSharedPreferences(MEMBER_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "")
            val persona = obj.optString("persona", "")
            if (name.isNotEmpty() && persona.isNotEmpty()) name to persona else null
        }
    }

    fun now(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date())
}

/**
 * 轻量判断器：在群聊对话中，谁最应该接话。
 * 返回成员名或 null 表示无人需要接话。
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
    fun decide(
        memberNames: List<String>,
        repliedNames: Set<String>,
        lastSpeaker: String,
        lastMessage: String,
        conversation: String
    ): String? {
        val unreplied = memberNames.filter { it !in repliedNames }
        if (unreplied.isEmpty()) return null

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

        return try {
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
