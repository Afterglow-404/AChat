package com.aftglw.devapi.feature.group

import com.aftglw.devapi.model.GroupChatMessage
import org.junit.Assert.*
import org.junit.Test

/**
 * [GroupProactiveScheduler] 纯逻辑测试。
 */
class GroupProactiveSchedulerTest {

    private val members = listOf("钦灵", "Alice", "Bob")
    private val now = 1_000_000L

    @Test
    fun `消息为空时不触发`() {
        assertFalse(GroupProactiveScheduler.shouldTrigger(emptyList(), 0L, now, probability = 1f))
    }

    @Test
    fun `probability 为 0 时不触发`() {
        val msgs = listOf(GroupChatMessage("hi", "user", "10:00", isMe = true))
        assertFalse(GroupProactiveScheduler.shouldTrigger(msgs, now - 60_000, now, probability = 0f))
    }

    @Test
    fun `用户发言后短于最小空闲时间不触发`() {
        val msgs = listOf(GroupChatMessage("hi", "user", "10:00", isMe = true))
        // 距上次活动 10s < 30s
        assertFalse(GroupProactiveScheduler.shouldTrigger(msgs, now - 10_000, now, probability = 1f))
    }

    @Test
    fun `用户发言后达到最小空闲时间且概率 1 触发`() {
        val msgs = listOf(GroupChatMessage("hi", "user", "10:00", isMe = true))
        assertTrue(GroupProactiveScheduler.shouldTrigger(msgs, now - 31_000, now, probability = 1f))
    }

    @Test
    fun `AI 发言后短于长空闲时间不触发`() {
        val msgs = listOf(GroupChatMessage("ok", "Alice", "10:00", isMe = false))
        // 距上次活动 60s < 90s
        assertFalse(GroupProactiveScheduler.shouldTrigger(msgs, now - 60_000, now, probability = 1f))
    }

    @Test
    fun `AI 发言后达到长空闲时间触发`() {
        val msgs = listOf(GroupChatMessage("ok", "Alice", "10:00", isMe = false))
        assertTrue(GroupProactiveScheduler.shouldTrigger(msgs, now - 91_000, now, probability = 1f))
    }

    @Test
    fun `未来时间戳不触发`() {
        val msgs = listOf(GroupChatMessage("hi", "user", "10:00", isMe = true))
        assertFalse(GroupProactiveScheduler.shouldTrigger(msgs, now + 10_000, now, probability = 1f))
    }

    @Test
    fun `pickSpeaker 排除最近发言人`() {
        val picked = GroupProactiveScheduler.pickSpeaker(members, lastSpeaker = "Alice")
        assertNotNull(picked)
        assertTrue(picked in members)
        assertNotEquals("Alice", picked)
    }

    @Test
    fun `pickSpeaker lastSpeaker 为 null 时返回任意成员`() {
        val picked = GroupProactiveScheduler.pickSpeaker(members, lastSpeaker = null)
        assertTrue(picked in members)
    }

    @Test
    fun `pickSpeaker 群只有 1 人且就是 lastSpeaker 返回 null`() {
        val solo = listOf("Only")
        assertNull(GroupProactiveScheduler.pickSpeaker(solo, lastSpeaker = "Only"))
    }

    @Test
    fun `pickSpeaker 空成员列表返回 null`() {
        assertNull(GroupProactiveScheduler.pickSpeaker(emptyList(), lastSpeaker = null))
    }

    @Test
    fun `buildSpontaneousPrompt 包含群名与角色名`() {
        val prompt = GroupProactiveScheduler.buildSpontaneousPrompt("测试群", "钦灵", members)
        assertTrue(prompt.contains("测试群"))
        assertTrue(prompt.contains("钦灵"))
        assertTrue(prompt.contains("Alice"))
    }
}
