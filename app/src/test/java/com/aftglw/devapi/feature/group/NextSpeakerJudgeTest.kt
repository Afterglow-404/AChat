package com.aftglw.devapi.feature.group

import org.junit.Assert.*
import org.junit.Test

/**
 * [NextSpeakerJudge.decideFast] 纯规则测试。
 *
 * 注：[NextSpeakerJudge.decide] 含 LLM 调用，无法在纯 JVM 单测中验证，
 * 仅通过 [decideFast] 覆盖可测的规则分支；LLM 解析逻辑依赖真机/模拟器手动验证。
 */
class NextSpeakerJudgeTest {

    private val members = listOf("钦灵", "Alice", "Bob", "王小明")

    @Test
    fun `全员已发言 返回 null 且 willingness 为 0`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = members.toSet(),
            lastMessage = "随便说点什么"
        )
        assertNull(d.name)
        assertEquals(0f, d.willingness, 0.001f)
        assertTrue(d.shouldStop())
    }

    @Test
    fun `at 提及某未发言成员 返回该成员且意愿为 1`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = setOf("钦灵"),  // Alice/Bob/王小明 未发言
            lastMessage = "这件事 @Alice 你怎么看？"
        )
        assertEquals("Alice", d.name)
        assertEquals(1.0f, d.willingness, 0.001f)
        assertFalse(d.shouldStop())
    }

    @Test
    fun `at 提及中文成员名 返回该成员`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = emptySet(),
            lastMessage = "@王小明 快来"
        )
        assertEquals("王小明", d.name)
        assertEquals(1.0f, d.willingness, 0.001f)
    }

    @Test
    fun `at 提及已发言成员 不命中该成员 进入 need_llm`() {
        // @钦灵 已发言，不应被选中；其他成员无 @提及 → need_llm
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = setOf("钦灵"),
            lastMessage = "@钦灵 谢谢"
        )
        assertNull(d.name)
        assertEquals("need_llm", d.reason)
    }

    @Test
    fun `仅剩 1 个未发言成员 直接选他 意愿 0_6 不过阈值`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = setOf("钦灵", "Alice", "王小明"),  // 仅 Bob 未发言
            lastMessage = "今天天气不错"
        )
        assertEquals("Bob", d.name)
        assertEquals(0.6f, d.willingness, 0.001f)
        // 0.6 > 0.4 默认阈值 → 不停止
        assertFalse(d.shouldStop())
        // 但若调用方使用更严格阈值 0.7 → 应停止
        assertTrue(d.shouldStop(threshold = 0.7f))
    }

    @Test
    fun `多个未发言且无 at 提及 返回 need_llm 标记`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = setOf("钦灵"),  // Alice/Bob/王小明 未发言
            lastMessage = "今天天气不错"
        )
        assertNull(d.name)
        assertEquals("need_llm", d.reason)
        assertEquals(0f, d.willingness, 0.001f)
        assertTrue(d.shouldStop())  // need_llm 默认 shouldStop=true，调用方应转 decide
    }

    @Test
    fun `空成员列表 全员已发言分支`() {
        val d = NextSpeakerJudge.decideFast(
            memberNames = emptyList(),
            repliedNames = emptySet(),
            lastMessage = "hi"
        )
        assertNull(d.name)
        assertEquals(0f, d.willingness, 0.001f)
    }

    @Test
    fun `at 提及非候选成员名 不误匹配`() {
        // 文本里 @张三 但张三不是群成员 → 不应误命中"王小明"或其他成员
        val d = NextSpeakerJudge.decideFast(
            memberNames = members,
            repliedNames = emptySet(),
            lastMessage = "@张三 你在哪"
        )
        assertNull(d.name)
        assertEquals("need_llm", d.reason)
    }

    @Test
    fun `shouldStop 阈值边界`() {
        // 边界值 willingness = threshold 不应停止（< 才停）
        val boundary = SpeakerDecision("Alice", 0.4f, "边界")
        assertFalse(boundary.shouldStop())  // 0.4 不 < 0.4 → 不停

        val below = SpeakerDecision("Alice", 0.39f, "低于")
        assertTrue(below.shouldStop())

        val nullName = SpeakerDecision(null, 1.0f, "无名字")
        assertTrue(nullName.shouldStop())
    }

    @Test
    fun `decideFast 不依赖 lastSpeaker 参数`() {
        // decideFast 签名已精简：不接收 lastSpeaker（仅 LLM 兜底分支才需要）
        // 这里验证：相同 lastMessage + repliedNames 下，结果稳定
        val d1 = NextSpeakerJudge.decideFast(members, setOf("钦灵"), "@Bob 来一下")
        val d2 = NextSpeakerJudge.decideFast(members, setOf("钦灵"), "@Bob 来一下")
        assertEquals(d1.name, d2.name)
        assertEquals(d1.willingness, d2.willingness, 0.001f)
    }
}
