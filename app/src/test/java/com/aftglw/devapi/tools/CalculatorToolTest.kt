package com.aftglw.devapi.tools

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * CalculatorTool 单元测试 — 纯 Kotlin 数学求值，无 Android 依赖。
 *
 * 覆盖：
 * - 基础运算 + - * / %
 * - 幂运算 ^
 * - 括号优先级
 * - 数学函数 sin/cos/sqrt/abs/floor/ceil/round/log/ln/exp
 * - 一元负号
 * - 错误处理（除以零、未知函数、缺少括号、空表达式）
 */
class CalculatorToolTest {

    private lateinit var tool: CalculatorTool
    private val ctx: Context = mockk()

    @Before
    fun setup() {
        tool = CalculatorTool()
    }

    // ============ 元信息 ============

    @Test
    fun `元信息 name 为 calculator`() {
        assertEquals("calculator", tool.name)
    }

    @Test
    fun `元信息 inputSchema 包含 expr 必填字段`() {
        val schema = tool.inputSchema
        assertEquals("object", schema.optString("type"))
        val props = schema.optJSONObject("properties")
        assertNotNull(props?.optJSONObject("expr"))
        val required = schema.optJSONArray("required")
        val requiredList = required?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        assertTrue("expr" in requiredList)
    }

    // ============ 基础运算 ============

    @Test
    fun `基础 加法`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "3 + 5"))
        assertTrue(result.contains("8"))
    }

    @Test
    fun `基础 减法`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "10 - 4"))
        assertTrue(result.contains("6"))
    }

    @Test
    fun `基础 乘法`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "6 * 7"))
        assertTrue(result.contains("42"))
    }

    @Test
    fun `基础 除法`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "15 / 4"))
        assertTrue(result.contains("3.75"))
    }

    @Test
    fun `基础 取模`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "17 % 5"))
        assertTrue(result.contains("2"))
    }

    // ============ 优先级和括号 ============

    @Test
    fun `优先级 乘法优先于加法`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "2 + 3 * 4"))
        assertTrue(result.contains("14"))
    }

    @Test
    fun `优先级 括号改变优先级`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "(2 + 3) * 4"))
        assertTrue(result.contains("20"))
    }

    @Test
    fun `优先级 嵌套括号`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "((1 + 2) * (3 + 4))"))
        assertTrue(result.contains("21"))
    }

    // ============ 幂运算 ============

    @Test
    fun `幂 2的10次方`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "2 ^ 10"))
        assertTrue(result.contains("1024"))
    }

    // ============ 一元负号 ============

    @Test
    fun `一元负号 负数`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "-5 + 3"))
        assertTrue(result.contains("-2"))
    }

    @Test
    fun `一元负号 负负得正`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "-(-5)"))
        assertTrue(result.contains("5"))
    }

    // ============ 数学函数 ============

    @Test
    fun `函数 sqrt 144 开方为 12`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "sqrt(144)"))
        assertTrue(result.contains("12"))
    }

    @Test
    fun `函数 abs 负数绝对值`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "abs(-42)"))
        assertTrue(result.contains("42"))
    }

    @Test
    fun `函数 floor 向下取整`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "floor(3.7)"))
        assertTrue(result.contains("3"))
    }

    @Test
    fun `函数 ceil 向上取整`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "ceil(3.2)"))
        assertTrue(result.contains("4"))
    }

    @Test
    fun `函数 round 四舍五入`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "round(3.5)"))
        assertTrue(result.contains("4"))
    }

    @Test
    fun `函数 sin sin0 为 0`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "sin(0)"))
        assertTrue(result.contains("0"))
    }

    @Test
    fun `函数 cos cos0 为 1`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "cos(0)"))
        assertTrue(result.contains("1"))
    }

    @Test
    fun `函数 exp exp0 为 1`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "exp(0)"))
        assertTrue(result.contains("1"))
    }

    @Test
    fun `函数 log log1000 为 3`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "log(1000)"))
        assertTrue(result.contains("3"))
    }

    @Test
    fun `函数 ln ln1 为 0`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "ln(1)"))
        assertTrue(result.contains("0"))
    }

    @Test
    fun `函数嵌套 sqrt 加 abs`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "sqrt(abs(-16))"))
        assertTrue(result.contains("4"))
    }

    // ============ 错误处理 ============

    @Test
    fun `错误 空表达式返回提示`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", ""))
        assertEquals("请提供数学表达式", result)
    }

    @Test
    fun `错误 空白表达式返回提示`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "   "))
        assertEquals("请提供数学表达式", result)
    }

    @Test
    fun `错误 缺少 expr 参数返回提示`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject())
        assertEquals("请提供数学表达式", result)
    }

    @Test
    fun `错误 除以零返回错误信息`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "1 / 0"))
        assertTrue(result.contains("错误") || result.contains("除以零"))
    }

    @Test
    fun `错误 未知函数返回错误信息`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "foobar(1)"))
        assertTrue(result.contains("错误"))
    }

    @Test
    fun `错误 缺少右括号返回错误信息`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "(1 + 2"))
        assertTrue(result.contains("错误"))
    }

    @Test
    fun `错误 不支持的字符返回错误信息`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "1 @ 2"))
        assertTrue(result.contains("错误"))
    }

    // ============ 复合表达式 ============

    @Test
    fun `复合 混合运算`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "2 + 3 * 4 - 6 / 2"))
        // 2 + 12 - 3 = 11
        assertTrue(result.contains("11"))
    }

    @Test
    fun `复合 带函数的复杂表达式`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "sqrt(16) + abs(-3) * 2"))
        // 4 + 6 = 10
        assertTrue(result.contains("10"))
    }

    @Test
    fun `复合 浮点运算`() = runBlocking {
        val result = tool.execute(ctx, org.json.JSONObject().put("expr", "3.14 * 2"))
        assertTrue(result.contains("6.28"))
    }
}
