package com.aftglw.devapi.core.worldbook

import android.content.Context
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.WorldbookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书存储与匹配。
 *
 * 底层已迁移到 Room 的 worldbook 表（每行一个条目，按 chat_name 索引）。
 * 公共 API 为 suspend 函数，内部通过 `withContext(Dispatchers.IO)` 切到 IO 线程调用 Room；
 * 调用方应在协程或 LaunchedEffect 中调用。
 *
 * 匹配：常驻条目（constant=true）始终注入；非常驻条目需要 keywords 中
 * 任一关键词出现在最近用户消息中（大小写不敏感，中文直接包含匹配）。
 */
object WorldbookStore {

    /** 读取某角色的全部条目（按 original_id 升序） */
    suspend fun load(ctx: Context, chatName: String): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        AppDatabase.get(ctx).worldbookDao()
            .getForChat(chatName)
            .map { it.toEntry() }
    }

    /** 全量保存（覆盖写） */
    suspend fun save(ctx: Context, chatName: String, entries: List<WorldbookEntry>) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        dao.deleteForChat(chatName)
        if (entries.isNotEmpty()) {
            dao.insertAll(entries.map { it.toEntity(chatName) })
        }
    }

    /** 新增条目，返回新列表（id 自动分配） */
    suspend fun add(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        val maxId = dao.getMaxOriginalId(chatName) ?: 0L
        val newId = maxId + 1L
        dao.insert(
            WorldbookEntity(
                chatName = chatName,
                originalId = newId,
                keywords = entry.keywords.joinToString(","),
                content = entry.content,
                priority = entry.priority,
                constant = entry.constant,
                enabled = entry.enabled
            )
        )
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /** 更新指定 id 的条目，返回新列表；未找到则原样返回 */
    suspend fun update(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        val existing = dao.getByChatAndOriginalId(chatName, entry.id)
        if (existing != null) {
            dao.update(
                existing.copy(
                    keywords = entry.keywords.joinToString(","),
                    content = entry.content,
                    priority = entry.priority,
                    constant = entry.constant,
                    enabled = entry.enabled
                )
            )
        }
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /** 删除指定 id 的条目，返回新列表 */
    suspend fun delete(ctx: Context, chatName: String, id: Long): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        val existing = dao.getByChatAndOriginalId(chatName, id)
        if (existing != null) {
            dao.deleteByRowId(existing.rowId)
        }
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /** 批量更新启用状态：将某角色全部条目的 enabled 字段统一设置，返回新列表 */
    suspend fun setAllEnabled(ctx: Context, chatName: String, enabled: Boolean): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        val all = dao.getForChat(chatName)
        all.forEach { e -> dao.update(e.copy(enabled = enabled)) }
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /** 删除某角色全部条目，返回新列表（应为空） */
    suspend fun deleteAll(ctx: Context, chatName: String): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        dao.deleteForChat(chatName)
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /**
     * 导出某角色全部条目为 JSON 字符串（兼容 SillyTavern character_book 结构）。
     * 字段：spec/version/name/description/entries[]
     * 每条 entry 含 keys/content/constant/priority/enabled/id/extensions
     */
    suspend fun exportJson(ctx: Context, chatName: String): String = withContext(Dispatchers.IO) {
        val entries = AppDatabase.get(ctx).worldbookDao().getForChat(chatName).map { it.toEntry() }
        val obj = JSONObject()
        obj.put("spec", "chara_book_v1")
        obj.put("name", chatName)
        obj.put("description", "Exported from Wisp")
        val arr = JSONArray()
        entries.forEach { e ->
            val item = JSONObject()
            item.put("id", e.id)
            item.put("keys", JSONArray(e.keywords))
            item.put("content", e.content)
            item.put("constant", e.constant)
            item.put("priority", e.priority)
            item.put("enabled", e.enabled)
            item.put("insertion_order", e.priority) // SillyTavern 兼容字段
            arr.put(item)
        }
        obj.put("entries", arr)
        obj.toString(2)
    }

    /**
     * 从 JSON 字符串导入条目（兼容 SillyTavern character_book 结构）。
     * 支持两种结构：
     *   1) {"entries": [entry, ...]} — 标准 character_book
     *   2) [entry, entry, ...] — 纯数组
     * 每条 entry 字段：keys(数组或逗号字符串)/content/constant/priority/enabled/id
     * 导入策略：merge=true 追加；merge=false 先清空再插入
     */
    suspend fun importJson(ctx: Context, chatName: String, json: String, merge: Boolean = false): List<WorldbookEntry> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).worldbookDao()
        val trimmed = json.trim()
        val entries = mutableListOf<WorldbookEntry>()

        try {
            val root = JSONObject(trimmed)
            val arr = if (root.has("entries")) root.optJSONArray("entries") else null
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    entries.add(parseSillyEntry(o))
                }
            }
        } catch (_: Exception) {
            // 尝试纯数组
            try {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    entries.add(parseSillyEntry(o))
                }
            } catch (_: Exception) { /* 解析失败，留空 */ }
        }

        if (entries.isNotEmpty()) {
            if (!merge) dao.deleteForChat(chatName)
            // 重新分配 id（merge 模式下接续现有最大 id）
            val maxId = if (merge) (dao.getMaxOriginalId(chatName) ?: 0L) else 0L
            entries.forEachIndexed { i, e ->
                dao.insert(WorldbookEntity(
                    chatName = chatName,
                    originalId = maxId + i + 1L,
                    keywords = e.keywords.joinToString(","),
                    content = e.content,
                    priority = e.priority,
                    constant = e.constant,
                    enabled = e.enabled
                ))
            }
        }
        dao.getForChat(chatName).map { it.toEntry() }
    }

    /** 解析 SillyTavern character_book entry，兼容 V2 字段 */
    private fun parseSillyEntry(o: JSONObject): WorldbookEntry {
        val keywords = mutableListOf<String>()
        when (val keysVal = o.opt("keys")) {
            is JSONArray -> {
                for (i in 0 until keysVal.length()) {
                    val s = keysVal.optString(i).trim()
                    if (s.isNotEmpty()) keywords.add(s)
                }
            }
            is String -> {
                keywords.addAll(keysVal.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        // 兼容 secondary_keys
        o.optJSONArray("secondary_keys")?.let { sk ->
            for (i in 0 until sk.length()) {
                val s = sk.optString(i).trim()
                if (s.isNotEmpty()) keywords.add(s)
            }
        }
        val priority = o.optInt("priority", o.optInt("insertion_order", 0))
        return WorldbookEntry(
            id = o.optLong("id", 0L),
            keywords = keywords.distinct(),
            content = o.optString("content", ""),
            priority = priority,
            constant = o.optBoolean("constant", false),
            enabled = o.optBoolean("enabled", true)
        )
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
    suspend fun matchForPrompt(ctx: Context, chatName: String, recentUserText: String): String {
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
}

private fun WorldbookEntity.toEntry(): WorldbookEntry {
    val kwRaw = keywords
    val kws = if (kwRaw.isBlank()) emptyList()
    else kwRaw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }
    return WorldbookEntry(
        id = originalId,
        keywords = kws,
        content = content,
        priority = priority,
        constant = constant,
        enabled = enabled
    )
}

private fun WorldbookEntry.toEntity(chatName: String): WorldbookEntity = WorldbookEntity(
    chatName = chatName,
    originalId = id,
    keywords = keywords.joinToString(","),
    content = content,
    priority = priority,
    constant = constant,
    enabled = enabled
)
