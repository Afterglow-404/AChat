package com.aftglw.devapi.core.worldbook

import android.content.Context
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.WorldbookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 世界书存储与匹配。
 *
 * 底层已迁移到 Room 的 worldbook 表（每行一个条目，按 chat_name 索引）。
 * 公共 API 保持同步签名，内部通过 `runBlocking(Dispatchers.IO)` 切到 IO 线程调用 Room。
 *
 * 匹配：常驻条目（constant=true）始终注入；非常驻条目需要 keywords 中
 * 任一关键词出现在最近用户消息中（大小写不敏感，中文直接包含匹配）。
 */
object WorldbookStore {

    /** 读取某角色的全部条目（按 original_id 升序） */
    fun load(ctx: Context, chatName: String): List<WorldbookEntry> = runBlocking {
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).worldbookDao()
                .getForChat(chatName)
                .map { it.toEntry() }
        }
    }

    /** 全量保存（覆盖写） */
    fun save(ctx: Context, chatName: String, entries: List<WorldbookEntry>) = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).worldbookDao()
            dao.deleteForChat(chatName)
            if (entries.isNotEmpty()) {
                dao.insertAll(entries.map { it.toEntity(chatName) })
            }
        }
    }

    /** 新增条目，返回新列表（id 自动分配） */
    fun add(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> = runBlocking {
        withContext(Dispatchers.IO) {
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
    }

    /** 更新指定 id 的条目，返回新列表；未找到则原样返回 */
    fun update(ctx: Context, chatName: String, entry: WorldbookEntry): List<WorldbookEntry> = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).worldbookDao()
            val existing = dao.getForChat(chatName).firstOrNull { it.originalId == entry.id }
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
    }

    /** 删除指定 id 的条目，返回新列表 */
    fun delete(ctx: Context, chatName: String, id: Long): List<WorldbookEntry> = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).worldbookDao()
            val existing = dao.getForChat(chatName).firstOrNull { it.originalId == id }
            if (existing != null) {
                dao.deleteByRowId(existing.rowId)
            }
            dao.getForChat(chatName).map { it.toEntry() }
        }
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
