package com.aftglw.devapi.core.mood

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * MoodDetector 规则层单元测试。
 *
 * 只测规则路径（punctuation / emoji / slang / polite / shortOverride），
 * 这些路径在 feed() 中早返回，不触及 MoodModel (ONNX) 或 LLM API。
 *
 * 规范化映射 (moodNormalize) 也会被验证：
 * - "兴奋" → "开心"
 * - "疑惑" → "惊讶"
 * - "无奈" → "悲伤"
 * - "生气" → "愤怒"
 * - "平静" → "中性"
 */
class MoodDetectorRuleTest {

    @Before
    fun reset() {
        MoodDetector.resetForTest()
    }

    // ============ 1. 标点符号规则 (rule_punct) ============

    @Test
    fun `标点 问号映射为惊讶（疑惑归一化）`() = runBlocking {
        val result = MoodDetector.feed("？")
        assertEquals("惊讶", result.mood)
        assertEquals("rule_punct", MoodDetector.lastSource)
    }

    @Test
    fun `标点 感叹号映射为惊讶`() = runBlocking {
        val result = MoodDetector.feed("！")
        assertEquals("惊讶", result.mood)
        assertEquals("rule_punct", MoodDetector.lastSource)
    }

    @Test
    fun `标点 句号映射为悲伤（无奈归一化）`() = runBlocking {
        val result = MoodDetector.feed("。")
        assertEquals("悲伤", result.mood)
    }

    @Test
    fun `标点 波浪号映射为中性（平静归一化）`() = runBlocking {
        val result = MoodDetector.feed("～")
        assertEquals("中性", result.mood)
    }

    @Test
    fun `标点 问号加感叹号映射为惊讶`() = runBlocking {
        val result = MoodDetector.feed("？！")
        assertEquals("惊讶", result.mood)
    }

    // ============ 2. Emoji 规则 (rule_emoji) ============

    @Test
    fun `emoji 笑哭映射为开心`() = runBlocking {
        val result = MoodDetector.feed("😂")
        assertEquals("开心", result.mood)
        assertEquals("rule_emoji", MoodDetector.lastSource)
    }

    @Test
    fun `emoji 大哭映射为悲伤`() = runBlocking {
        val result = MoodDetector.feed("😭")
        assertEquals("悲伤", result.mood)
    }

    @Test
    fun `emoji 愤怒映射为愤怒`() = runBlocking {
        val result = MoodDetector.feed("😡")
        assertEquals("愤怒", result.mood)
    }

    @Test
    fun `emoji 合十映射为中性`() = runBlocking {
        val result = MoodDetector.feed("🙏")
        assertEquals("中性", result.mood)
    }

    @Test
    fun `emoji 在句子中也能命中`() = runBlocking {
        val result = MoodDetector.feed("今天好开心啊😂")
        assertEquals("开心", result.mood)
        assertEquals("rule_emoji", MoodDetector.lastSource)
    }

    // ============ 3. 网络用语规则 (rule_slang) ============

    @Test
    fun `网络语 yyds映射为开心（兴奋归一化）`() = runBlocking {
        val result = MoodDetector.feed("yyds")
        assertEquals("开心", result.mood)
        assertEquals("rule_slang", MoodDetector.lastSource)
    }

    @Test
    fun `网络语 emo映射为悲伤`() = runBlocking {
        val result = MoodDetector.feed("emo了")
        assertEquals("悲伤", result.mood)
    }

    @Test
    fun `网络语 破防了映射为惊讶`() = runBlocking {
        val result = MoodDetector.feed("破防了")
        assertEquals("惊讶", result.mood)
    }

    @Test
    fun `网络语 躺平映射为悲伤（无奈归一化）`() = runBlocking {
        val result = MoodDetector.feed("躺平")
        assertEquals("悲伤", result.mood)
    }

    @Test
    fun `网络语 大写YYDS也能命中`() = runBlocking {
        val result = MoodDetector.feed("YYDS")
        assertEquals("开心", result.mood)
    }

    // ============ 4. 礼貌用语规则 (rule_polite) ============

    @Test
    fun `礼貌语 谢谢映射为中性`() = runBlocking {
        val result = MoodDetector.feed("谢谢")
        assertEquals("中性", result.mood)
        assertEquals("rule_polite", MoodDetector.lastSource)
    }

    @Test
    fun `礼貌语 辛苦了映射为中性`() = runBlocking {
        val result = MoodDetector.feed("辛苦了")
        assertEquals("中性", result.mood)
    }

    @Test
    fun `礼貌语 好的谢谢映射为中性`() = runBlocking {
        val result = MoodDetector.feed("好的谢谢")
        assertEquals("中性", result.mood)
    }

    @Test
    fun `礼貌语 带空格也能命中`() = runBlocking {
        val result = MoodDetector.feed("谢 谢")
        assertEquals("中性", result.mood)
        assertEquals("rule_polite", MoodDetector.lastSource)
    }

    // ============ 5. 短文本黑名单 (rule_short) ============

    @Test
    fun `短文本 呵映射为厌恶`() = runBlocking {
        val result = MoodDetector.feed("呵")
        assertEquals("厌恶", result.mood)
        assertEquals("rule_short", MoodDetector.lastSource)
    }

    @Test
    fun `短文本 哼映射为愤怒（生气归一化）`() = runBlocking {
        val result = MoodDetector.feed("哼")
        assertEquals("愤怒", result.mood)
    }

    @Test
    fun `短文本 唉映射为悲伤（无奈归一化）`() = runBlocking {
        val result = MoodDetector.feed("唉")
        assertEquals("悲伤", result.mood)
    }

    @Test
    fun `短文本 切映射为厌恶`() = runBlocking {
        val result = MoodDetector.feed("切")
        assertEquals("厌恶", result.mood)
    }

    // ============ 6. 状态追踪 ============

    @Test
    fun `状态 feedCount正确递增`() = runBlocking {
        assertEquals(0, MoodDetector.feedCount)
        MoodDetector.feed("😂")
        assertEquals(1, MoodDetector.feedCount)
        MoodDetector.feed("😭")
        assertEquals(2, MoodDetector.feedCount)
    }

    @Test
    fun `状态 lastMood和lastSource被更新`() = runBlocking {
        assertNull(MoodDetector.lastMood)
        assertEquals("none", MoodDetector.lastSource)

        MoodDetector.feed("😂")
        assertEquals("开心", MoodDetector.lastMood)
        assertEquals("rule_emoji", MoodDetector.lastSource)

        MoodDetector.feed("😭")
        assertEquals("悲伤", MoodDetector.lastMood)
        assertEquals("rule_emoji", MoodDetector.lastSource)
    }

    @Test
    fun `状态 resetForTest清除所有状态`() = runBlocking {
        MoodDetector.feed("😂")
        MoodDetector.feed("😭")
        assertEquals(2, MoodDetector.feedCount)
        assertNotNull(MoodDetector.lastMood)

        MoodDetector.resetForTest()
        assertEquals(0, MoodDetector.feedCount)
        assertNull(MoodDetector.lastMood)
        assertNull(MoodDetector.lastHint)
        assertEquals("none", MoodDetector.lastSource)
    }

    // ============ 7. hint 输出 ============

    @Test
    fun `hint 开心时返回开心提示`() = runBlocking {
        val result = MoodDetector.feed("😂")
        assertNotNull(result.hint)
        assertTrue(result.hint!!.contains("开心"))
    }

    @Test
    fun `hint 悲伤时返回安慰提示`() = runBlocking {
        val result = MoodDetector.feed("😭")
        assertNotNull(result.hint)
        assertTrue(result.hint!!.contains("安慰"))
    }

    @Test
    fun `hint 中性时返回null`() = runBlocking {
        val result = MoodDetector.feed("谢谢")
        assertNull(result.hint)
    }
}
