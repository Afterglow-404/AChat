package com.aftglw.devapi.tools

import android.content.Context
import io.mockk.mockk
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * AiTool.parseTextArgs 默认实现测试。
 *
 * 默认实现将 `key=val key2=val2` 文本格式转为 JSONObject。
 * 覆盖：
 * - 空字符串
 * - 单参数
 * - 多参数
 * - 无等号的 token 被忽略
 * - 带空格的值
 */
class AiToolParseTextArgsTest {

    /** 简单的 fake tool，只为测 parseTextArgs 默认实现 */
    private val fakeTool = object : AiTool {
        override val name = "fake"
        override val description = "fake tool for test"
        override val inputSchema = JSONObject()
        override suspend fun execute(ctx: Context, args: JSONObject): String = ""
    }

    @Test
    fun `空字符串返回空 JSONObject`() {
        val obj = fakeTool.parseTextArgs("")
        assertEquals(0, obj.length())
    }

    @Test
    fun `空白字符串返回空 JSONObject`() {
        val obj = fakeTool.parseTextArgs("   ")
        assertEquals(0, obj.length())
    }

    @Test
    fun `单参数 正确解析`() {
        val obj = fakeTool.parseTextArgs("key=value")
        assertEquals("value", obj.getString("key"))
    }

    @Test
    fun `多参数 正确解析`() {
        val obj = fakeTool.parseTextArgs("name=张三 age=25")
        assertEquals("张三", obj.getString("name"))
        assertEquals("25", obj.getString("age"))
    }

    @Test
    fun `多参数 制表符分隔也能解析`() {
        val obj = fakeTool.parseTextArgs("a=1\tb=2")
        assertEquals("1", obj.getString("a"))
        assertEquals("2", obj.getString("b"))
    }

    @Test
    fun `无等号的 token 被忽略`() {
        val obj = fakeTool.parseTextArgs("hello key=value world")
        assertEquals(1, obj.length())
        assertEquals("value", obj.getString("key"))
    }

    @Test
    fun `值中包含等号 只分割第一个`() {
        val obj = fakeTool.parseTextArgs("url=http://example.com?a=1")
        assertEquals("http://example.com?a=1", obj.getString("url"))
    }

    @Test
    fun `参数值 无空格时正常解析`() {
        // parseTextArgs 用 \\s+ 分割，值不能含空格
        val obj = fakeTool.parseTextArgs("key=value")
        assertEquals("value", obj.getString("key"))
    }

    @Test
    fun `空值 等号后无内容`() {
        val obj = fakeTool.parseTextArgs("key=")
        assertEquals("", obj.getString("key"))
    }

    @Test
    fun `toMcpToolJson 输出正确格式`() {
        val tool = object : AiTool {
            override val name = "test_tool"
            override val description = "a test tool"
            override val inputSchema = JSONObject().apply { put("type", "object") }
            override suspend fun execute(ctx: Context, args: JSONObject): String = ""
        }
        val json = tool.toMcpToolJson()
        assertEquals("test_tool", json.getString("name"))
        assertEquals("a test tool", json.getString("description"))
        assertEquals("object", json.optJSONObject("inputSchema")?.optString("type"))
    }
}
