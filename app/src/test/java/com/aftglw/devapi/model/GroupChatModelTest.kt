package com.aftglw.devapi.model

import org.junit.Assert.*
import org.junit.Test

/**
 * 数据模型纯逻辑测试 — 无 Android 依赖。
 */
class GroupChatModelTest {

    @Test
    fun `isMe 正确区分用户和 AI 消息`() {
        val user = GroupChatMessage("你好", "user", "14:00", isMe = true)
        val ai = GroupChatMessage("你好啊", "钦灵", "14:01", isMe = false)

        assertTrue(user.isMe)
        assertFalse(ai.isMe)
        assertEquals("钦灵", ai.from)
    }

    @Test
    fun `GroupChat copy 不改变原始对象`() {
        val g = GroupChat("g1", "测试群", listOf("A", "B"))
        val updated = g.copy(lastMessage = "A: 嗨", time = "14:30")

        assertEquals("", g.lastMessage)
        assertEquals("A: 嗨", updated.lastMessage)
        assertEquals("测试群", updated.name)
    }

    @Test
    fun `ChatMessage role 映射正确`() {
        assertEquals("user", ChatMessage("user", "你好").role)
        assertEquals("assistant", ChatMessage("assistant", "回复").role)
    }
}
