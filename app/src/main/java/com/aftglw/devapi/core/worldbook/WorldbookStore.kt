package com.aftglw.devapi.core.worldbook

import android.content.Context
import org.json.JSONArray

/**
 * 世界书存储与匹配。
 *
 * 存储：SharedPreferences JSON，key = "worldbook_$chatName"，值为 JSONArray。
 * 仿 wechat_chats 的存储模式，避免 SQLite schema 迁移。
 *
 * 匹配：常驻条目（constant=true）始终注入；非常驻条目需要 keywords 中
 * 任一关键词出现在最近用户消息中（大小写不敏感，中文直接包含匹配）。
 */
object WorldbookStore {

    private const val PREFS_NAME = "wechat_worldbook"
    private const val KEY_PREFIX = "worldbook_"

    /** SharedPreferences 中存储某角色的全部条目 */
    fun load(ctx: Context, chatName: String): List<WorldbookEntry> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PREFIX + chatName, "[]") ?: "[]"
        return parseEntries(raw)
    }

    /** 全量保存（覆盖写） */
    fun save(ctx: Context, chatName: String, entries: List<WorldbookEntry>) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PREFIX + chatName, arr.toString()).apply()
    }

    /** 新增条目，返回新列表（id 自动分配） */
    fun add(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> {
        val current = load(ctx, chatName)
        val newId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        val withId = entry.copy(id = newId)
        val updated = current + withId
        save(ctx, chatName, updated)
        return updated
    }

    /** 更新指定 id 的条目，返回新列表；未找到则原样返回 */
    fun update(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> {
        val current = load(ctx, chatName)
        val updated = current.map { if (it.id == entry.id) entry else it }
        if (updated != current) save(ctx, chatName, updated)
        return updated
    }

    /** 删除指定 id 的条目，返回新列表 */
    fun delete(ctx: Context, chatName: String, id: Long): List<WorldbookEntry> {
        val current = load(ctx, chatName)
        val updated = current.filterNot { it.id == id }
        if (updated.size != current.size) save(ctx, chatName, updated)
        return updated
    }

    /**
     * 纯函数：根据最近用户消息匹配应当注入的条目。
     * - constant=true 且 enabled=true → 始终命中
     * - enabled=true 且 keywords 任一出现在 text 中 → 命中
     * - 其余不命中
     * 按 priority 降序排序；条目数上限 [maxEntries]。
     */
    internal fun matchEntries(
        entries: List<WorldbookEntry>,
        text: String,
        maxEntries: Int = 8
    ): List<WorldbookEntry> {
        val lowerText = text.lowercase()
        val matched = entries.filter { e ->
            if (!e.enabled) return@filter false
            if (e.constant) return@filter true
            if (e.keywords.isEmpty()) return@filter false
            e.keywords.any { kw -> kw.isNotBlank() && lowerText.contains(kw.lowercase()) }
        }
        return matched.sortedByDescending { it.priority }.take(maxEntries)
    }

    /**
     * 给 PromptBuilder 用：返回拼装好的世界书文本块（已去重、按优先级排序）。
     * 若无任何匹配则返回空串。
     */
    fun matchForPrompt(ctx: Context, chatName: String, recentUserText: String): String {
        val entries = load(ctx, chatName)
        val matched = matchEntries(entries, recentUserText)
        if (matched.isEmpty()) return ""
        return matched.joinToString("\n\n") { e ->
            buildString {
                if (e.keywords.isNotEmpty()) append("[${e.keywords.joinToString("/")}] ")
                append(e.content)
            }
        }
    }

    /** 解析 JSON 字符串为条目列表 */
    internal fun parseEntries(raw: String): List<WorldbookEntry> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { WorldbookEntry.fromJson(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
