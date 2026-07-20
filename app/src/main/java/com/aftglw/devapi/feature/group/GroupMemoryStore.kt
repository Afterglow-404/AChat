package com.aftglw.devapi.feature.group

import android.content.Context
import com.aftglw.devapi.core.memory.MemoryItem
import com.aftglw.devapi.core.memory.MemoryStore

/**
 * 群聊记忆存储 —— 复用 [MemoryStore] 但用 topic 字段做命名空间隔离。
 *
 * 双层记忆结构：
 * 1. **成员个人记忆** `group_<groupId>_<memberName>`
 *    - 用户 @某成员 时，额外保存到该成员的个人记忆（强化对该成员的专属印象）
 *    - 检索时按 (群, 成员) 命名空间过滤
 * 2. **群级共享记忆** `group_<groupId>_shared`
 *    - 所有用户输入都保存到群级共享记忆（所有成员可见）
 *    - 让任何成员接话时都能看到用户在群里说过的话，避免"失忆"
 *
 * 设计原则：
 * - 只记录用户输入，不记录 AI 发言（避免记忆膨胀）
 * - @某成员时，同时保存到群级共享 + 该成员个人记忆
 * - 检索时合并两层结果，按相似度排序
 */
object GroupMemoryStore {

    /**
     * 群级共享记忆的 topic 后缀。用双下划线 `__shared` 与成员名 "shared" 的个人 topic 区分：
     * - `topicFor("g1", "shared")` → `group_g1_shared`
     * - `sharedTopicFor("g1")` → `group_g1__shared`
     * LIKE 查询时各自精确匹配，不会误匹配。
     */
    private const val SHARED_SUFFIX = "__shared"

    /** 成员个人记忆 topic：`group_<groupId>_<memberName>` */
    fun topicFor(groupId: String, memberName: String): String =
        "group_${groupId}_$memberName"

    /** 群级共享记忆 topic：`group_<groupId>__shared`（双下划线避免与成员名 "shared" 冲突） */
    fun sharedTopicFor(groupId: String): String =
        "group_${groupId}$SHARED_SUFFIX"

    // ── 成员个人记忆 ──

    /** 保存一条记忆到 (groupId, memberName) 的个人命名空间 */
    suspend fun save(ctx: Context, groupId: String, memberName: String, text: String) {
        if (text.isBlank()) return
        MemoryStore.save(ctx, text, topicFor(groupId, memberName))
    }

    /** 检索 (groupId, memberName) 命名空间下与 [query] 最相关的 topK 条个人记忆 */
    suspend fun search(
        ctx: Context,
        groupId: String,
        memberName: String,
        query: String,
        topK: Int = 3
    ): List<MemoryItem> {
        if (query.isBlank()) return emptyList()
        return MemoryStore.search(ctx, query, topK, topicFilter = topicFor(groupId, memberName))
    }

    // ── 群级共享记忆 ──

    /** 保存一条记忆到群级共享命名空间（所有成员可见） */
    suspend fun saveShared(ctx: Context, groupId: String, text: String) {
        if (text.isBlank()) return
        MemoryStore.save(ctx, text, sharedTopicFor(groupId))
    }

    /** 检索群级共享记忆 */
    suspend fun searchShared(
        ctx: Context,
        groupId: String,
        query: String,
        topK: Int = 3
    ): List<MemoryItem> {
        if (query.isBlank()) return emptyList()
        return MemoryStore.search(ctx, query, topK, topicFilter = sharedTopicFor(groupId))
    }

    // ── 统一入口 ──

    /**
     * 保存用户消息：同时写入群级共享记忆，若 [mentionedMember] 非空则额外写入该成员个人记忆。
     *
     * @param groupId         群 ID
     * @param mentionedMember 被用户 @ 的成员名（可为空）
     * @param text            用户输入文本
     */
    suspend fun saveUserMessage(ctx: Context, groupId: String, mentionedMember: String?, text: String) {
        if (text.isBlank()) return
        // 1. 群级共享：所有成员都能检索到
        saveShared(ctx, groupId, text)
        // 2. 个人记忆：被 @ 的成员额外强化印象
        if (!mentionedMember.isNullOrBlank()) {
            save(ctx, groupId, mentionedMember, text)
        }
    }

    /**
     * 合并检索成员个人记忆 + 群级共享记忆，去重后返回 topK 条。
     *
     * 用于 callMember 时给当前发言成员注入相关历史上下文。
     */
    suspend fun searchForMember(
        ctx: Context,
        groupId: String,
        memberName: String,
        query: String,
        topK: Int = 3
    ): List<MemoryItem> {
        if (query.isBlank()) return emptyList()
        // 个人记忆 + 群级共享各取 topK，合并去重后截断
        val personal = search(ctx, groupId, memberName, query, topK)
        val shared = searchShared(ctx, groupId, query, topK)
        val seen = mutableSetOf<String>()
        return (personal + shared)
            .filter { seen.add(it.text) }
            .take(topK)
    }
}
