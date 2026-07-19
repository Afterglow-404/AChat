package com.aftglw.devapi.network

import com.aftglw.devapi.core.mood.MoodDetector
import com.aftglw.devapi.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [ThinkingModeSelector] 的纯逻辑测试。
 *
 * 验证：
 * - 代码/数学关键词 → DEEP_THINKING
 * - 负面情绪（悲伤/害怕/愤怒）→ THINKING
 * - 长消息（>200 字符）→ THINKING
 * - 普通短消息 → NO_THINKING
 */
class ThinkingModeSelectorTest {

    private val selector = ThinkingModeSelector()

    @Before
    fun resetMood() {
        MoodDetector.resetForTest()
    }

    @After
    fun cleanup() {
        MoodDetector.resetForTest()
    }

    @Test
    fun `代码关键词 触发深度思考`() {
        val mode = selector.select(emptyList(), "帮我写一段代码")
        assertEquals(ThinkingMode.DEEP_THINKING, mode)
    }

    @Test
    fun `代码块标记 触发深度思考`() {
        val mode = selector.select(emptyList(), "```kotlin\nfun main() {}\n```")
        assertEquals(ThinkingMode.DEEP_THINKING, mode)
    }

    @Test
    fun `数学关键词 触发深度思考`() {
        val mode = selector.select(emptyList(), "帮我算一道数学题")
        assertEquals(ThinkingMode.DEEP_THINKING, mode)
    }

    @Test
    fun `悲伤情绪 触发思考模式`() {
        MoodDetector.lastMood = "悲伤"
        val mode = selector.select(emptyList(), "我今天好难过")
        assertEquals(ThinkingMode.THINKING, mode)
    }

    @Test
    fun `害怕情绪 触发思考模式`() {
        MoodDetector.lastMood = "害怕"
        val mode = selector.select(emptyList(), "好可怕")
        assertEquals(ThinkingMode.THINKING, mode)
    }

    @Test
    fun `愤怒情绪 触发思考模式`() {
        MoodDetector.lastMood = "愤怒"
        val mode = selector.select(emptyList(), "气死我了")
        assertEquals(ThinkingMode.THINKING, mode)
    }

    @Test
    fun `长消息 触发思考模式`() {
        val longMsg = "a".repeat(201)
        val mode = selector.select(emptyList(), longMsg)
        assertEquals(ThinkingMode.THINKING, mode)
    }

    @Test
    fun `恰好200字符 触发快速模式`() {
        // 边界：>200 才是 THINKING，==200 仍是 NO_THINKING
        val mode = selector.select(emptyList(), "a".repeat(200))
        assertEquals(ThinkingMode.NO_THINKING, mode)
    }

    @Test
    fun `普通短消息 触发快速模式`() {
        val mode = selector.select(emptyList(), "你好呀")
        assertEquals(ThinkingMode.NO_THINKING, mode)
    }

    @Test
    fun `中性情绪 触发快速模式`() {
        MoodDetector.lastMood = "高兴"
        val mode = selector.select(emptyList(), "今天天气真好")
        assertEquals(ThinkingMode.NO_THINKING, mode)
    }

    @Test
    fun `无情绪状态 触发快速模式`() {
        MoodDetector.lastMood = null
        val mode = selector.select(emptyList(), "在干嘛？")
        assertEquals(ThinkingMode.NO_THINKING, mode)
    }

    @Test
    fun `代码关键词优先级高于情绪`() {
        // 即使是负面情绪，代码关键词也应触发 DEEP_THINKING
        MoodDetector.lastMood = "悲伤"
        val mode = selector.select(emptyList(), "帮我写一段代码")
        assertEquals(ThinkingMode.DEEP_THINKING, mode)
    }

    @Test
    fun `历史记录参数不影响选择逻辑`() {
        // ThinkingModeSelector 当前只看 userMessage 与 lastMood，history 不参与决策
        val history = listOf(
            ChatMessage("user", "上次说的代码"),
            ChatMessage("assistant", "好的")
        )
        val mode = selector.select(history, "你好")
        assertEquals(ThinkingMode.NO_THINKING, mode)
    }
}
