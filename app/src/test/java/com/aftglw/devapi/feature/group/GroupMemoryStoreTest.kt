package com.aftglw.devapi.feature.group

import org.junit.Assert.*
import org.junit.Test

/**
 * [GroupMemoryStore] 命名空间格式测试 —— 纯逻辑部分。
 *
 * 注意：[GroupMemoryStore.save] / [search] 走 Android SQLite + 网络 embedding，
 * 无法在纯 JVM 单元测试中验证，需依赖真机/模拟器手动验证。
 */
class GroupMemoryStoreTest {

    @Test
    fun `topicFor 格式正确`() {
        assertEquals("group_g1_钦灵", GroupMemoryStore.topicFor("g1", "钦灵"))
        assertEquals("group_chat42_Alice", GroupMemoryStore.topicFor("chat42", "Alice"))
    }

    @Test
    fun `不同群相同成员名隔离`() {
        val t1 = GroupMemoryStore.topicFor("groupA", "钦灵")
        val t2 = GroupMemoryStore.topicFor("groupB", "钦灵")
        assertNotEquals(t1, t2)
    }

    @Test
    fun `同群不同成员名隔离`() {
        val t1 = GroupMemoryStore.topicFor("g1", "钦灵")
        val t2 = GroupMemoryStore.topicFor("g1", "Alice")
        assertNotEquals(t1, t2)
    }

    @Test
    fun `topicFor 含特殊字符的群 ID 也按字面拼接`() {
        // 群 ID 含下划线时仍可拼接（数据库 topic 字段为 TEXT，无格式限制）
        val t = GroupMemoryStore.topicFor("grp_2026_07", "Bob")
        assertEquals("group_grp_2026_07_Bob", t)
    }

    @Test
    fun `sharedTopicFor 格式正确`() {
        assertEquals("group_g1__shared", GroupMemoryStore.sharedTopicFor("g1"))
        assertEquals("group_chat42__shared", GroupMemoryStore.sharedTopicFor("chat42"))
    }

    @Test
    fun `sharedTopicFor 与个人 topic 不冲突`() {
        // 群级共享 topic 用双下划线后缀，与同名成员 "shared" 的个人 topic 字面不同
        // sharedTopicFor("g1") = "group_g1__shared"
        // topicFor("g1", "shared") = "group_g1_shared"
        val shared = GroupMemoryStore.sharedTopicFor("g1")
        val personalOfSharedMember = GroupMemoryStore.topicFor("g1", "shared")
        assertNotEquals(shared, personalOfSharedMember)
    }

    @Test
    fun `sharedTopicFor 与其他群隔离`() {
        assertNotEquals(
            GroupMemoryStore.sharedTopicFor("groupA"),
            GroupMemoryStore.sharedTopicFor("groupB")
        )
    }
}
