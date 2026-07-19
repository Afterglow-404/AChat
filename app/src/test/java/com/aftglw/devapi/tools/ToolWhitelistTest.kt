package com.aftglw.devapi.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 工具安全相关测试。
 *
 * 由于 ToolWhitelist / DialogToolGuard / RestrictedToolGuard 都依赖 Android Context
 * （SharedPreferences），单元测试无法直接覆盖。
 * 这里只验证：
 * - NoOpToolGuard 总是放行
 * - RiskLevel ordinal 顺序符合预期（用于风险阈值比较）
 * - AiTool 默认 riskLevel 是 LOW
 * - 风险阈值比较逻辑（与 RestrictedToolGuard 内部相同）正确
 */
class ToolWhitelistTest {

    @Test
    fun `NoOpToolGuard 总是放行包括 HIGH`() {
        val guard = NoOpToolGuard
        val highRiskTool = FakeTool("dangerous", RiskLevel.HIGH)
        kotlinx.coroutines.runBlocking {
            assertTrue(guard.confirm(highRiskTool, "{}"))
        }
    }

    @Test
    fun `RiskLevel ordinal 顺序 LOW 小于 MEDIUM 小于 HIGH`() {
        assertTrue(RiskLevel.LOW.ordinal < RiskLevel.MEDIUM.ordinal)
        assertTrue(RiskLevel.MEDIUM.ordinal < RiskLevel.HIGH.ordinal)
    }

    @Test
    fun `AiTool 默认 riskLevel 是 LOW`() {
        val tool = object : AiTool {
            override val name = "default_tool"
            override val description = "test"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: android.content.Context, args: JSONObject) = "ok"
        }
        assertTrue(tool.riskLevel == RiskLevel.LOW)
    }

    @Test
    fun `风险阈值 MEDIUM 时 LOW 和 MEDIUM 通过 HIGH 拒绝`() {
        val threshold = RiskLevel.MEDIUM
        assertTrue(RiskLevel.LOW.ordinal <= threshold.ordinal)
        assertTrue(RiskLevel.MEDIUM.ordinal <= threshold.ordinal)
        assertFalse(RiskLevel.HIGH.ordinal <= threshold.ordinal)
    }

    @Test
    fun `风险阈值 LOW 时只允许 LOW`() {
        val threshold = RiskLevel.LOW
        assertTrue(RiskLevel.LOW.ordinal <= threshold.ordinal)
        assertFalse(RiskLevel.MEDIUM.ordinal <= threshold.ordinal)
        assertFalse(RiskLevel.HIGH.ordinal <= threshold.ordinal)
    }

    private class FakeTool(
        override val name: String,
        override val riskLevel: RiskLevel
    ) : AiTool {
        override val description = "fake"
        override val inputSchema = JSONObject()
        override suspend fun execute(ctx: android.content.Context, args: JSONObject) = "ok"
    }
}
