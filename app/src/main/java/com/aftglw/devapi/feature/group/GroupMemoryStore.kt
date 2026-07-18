package com.aftglw.devapi.feature.group

import android.content.Context
import com.aftglw.devapi.core.memory.MemoryItem
import com.aftglw.devapi.core.memory.MemoryStore

/**
 * 群聊记忆存储 —— 复用 [MemoryStore] 但用 topic 字段按 (群ID, 成员名) 命名空间隔离。
 *
 * 设计：
 * - 每个成员对每个群有独立的记忆（不同群、不同成员互不干扰）
 * - 仅记录「用户对该成员说过的话」作为记忆点，避免记录 AI 自身回复造成膨胀
 * - 调用 [MemoryStore.search] 时通过 topicFilter 限定范围
 *
 * topic 命名空间格式：`group_<groupId>_<memberName>`
 */
object GroupMemoryStore {

    /** topic 前缀，纯函数可测试 */
    fun topicFor(groupId: String, memberName: String): String =
        "group_${groupId}_$memberName"

    /** 保存一条记忆到 (groupId, memberName) 的命名空间 */
    fun save(ctx: Context, groupId: String, memberName: String, text: String) {
        if (text.isBlank()) return
        MemoryStore.save(ctx, text, topicFor(groupId, memberName))
    }

    /** 检索 (groupId, memberName) 命名空间下与 [query] 最相关的 topK 条记忆 */
    fun search(
        ctx: Context,
        groupId: String,
        memberName: String,
        query: String,
        topK: Int = 3
    ): List<MemoryItem> {
        if (query.isBlank()) return emptyList()
        return MemoryStore.search(ctx, query, topK, topicFilter = topicFor(groupId, memberName))
    }
}
