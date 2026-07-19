package com.aftglw.devapi.tools

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ToolRegistry 单元测试 — 注册/查询/描述生成/工具调用。
 *
 * 用 fake tool 隔离测试，不依赖真实工具的 Android 调用。
 */
class ToolRegistryTest {

    private val ctx: Context = mockk()

    @Before
    fun reset() {
        ToolRegistry.resetForTest()
    }

    // ============ 注册和查询 ============

    @Test
    fun `注册 单个工具后可按名查询`() {
        val tool = fakeTool("note", "记笔记")
        ToolRegistry.register(tool)
        assertSame(tool, ToolRegistry.get("note"))
    }

    @Test
    fun `注册 同名工具被覆盖`() {
        ToolRegistry.register(fakeTool("note", "旧描述"))
        ToolRegistry.register(fakeTool("note", "新描述"))
        assertEquals("新描述", ToolRegistry.get("note")?.description)
    }

    @Test
    fun `查询 未注册的工具返回 null`() {
        assertNull(ToolRegistry.get("nonexistent"))
    }

    @Test
    fun `getAll 注册多个工具后全部返回`() {
        ToolRegistry.register(fakeTool("a", "tool a"))
        ToolRegistry.register(fakeTool("b", "tool b"))
        ToolRegistry.register(fakeTool("c", "tool c"))
        assertEquals(3, ToolRegistry.getAll().size)
    }

    @Test
    fun `getAll 空注册表返回空列表`() {
        assertTrue(ToolRegistry.getAll().isEmpty())
    }

    // ============ getDescriptions ============

    @Test
    fun `getDescriptions 空注册表返回空字符串`() {
        assertEquals("", ToolRegistry.getDescriptions())
    }

    @Test
    fun `getDescriptions 包含工具名和描述`() {
        ToolRegistry.register(fakeTool("note", "记笔记工具"))
        val desc = ToolRegistry.getDescriptions()
        assertTrue(desc.contains("【tool:note】"))
        assertTrue(desc.contains("记笔记工具"))
    }

    @Test
    fun `getDescriptions 包含参数名类型和必填标记`() {
        ToolRegistry.register(fakeToolWithSchema(
            "calc", "计算器",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("expr", JSONObject().apply {
                        put("type", "string")
                        put("description", "表达式")
                    })
                })
                put("required", JSONArray().apply { put("expr") })
            }
        ))
        val desc = ToolRegistry.getDescriptions()
        assertTrue(desc.contains("expr(string,必填)"))
        assertTrue(desc.contains("表达式"))
    }

    @Test
    fun `getDescriptions 可选参数标记为可选`() {
        ToolRegistry.register(fakeToolWithSchema(
            "search", "搜索",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "string"); put("description", "关键词") })
                    put("limit", JSONObject().apply { put("type", "integer"); put("description", "数量") })
                })
                put("required", JSONArray().apply { put("query") })
            }
        ))
        val desc = ToolRegistry.getDescriptions()
        assertTrue(desc.contains("query(string,必填)"))
        assertTrue(desc.contains("limit(integer,可选)"))
    }

    @Test
    fun `getDescriptions 多工具用换行分隔`() {
        ToolRegistry.register(fakeTool("a", "desc a"))
        ToolRegistry.register(fakeTool("b", "desc b"))
        val desc = ToolRegistry.getDescriptions()
        assertTrue(desc.contains("【tool:a】"))
        assertTrue(desc.contains("【tool:b】"))
        assertTrue(desc.contains("\n"))
    }

    // ============ listToolsJson ============

    @Test
    fun `listToolsJson 返回正确的 JSON 数组`() {
        ToolRegistry.register(fakeTool("a", "tool a"))
        ToolRegistry.register(fakeTool("b", "tool b"))
        val json = ToolRegistry.listToolsJson()
        assertEquals(2, json.length())
        assertEquals("a", json.getJSONObject(0).getString("name"))
    }

    @Test
    fun `listToolsJson 空注册表返回空数组`() {
        val json = ToolRegistry.listToolsJson()
        assertEquals(0, json.length())
    }

    // ============ executeText ============

    @Test
    fun `executeText 正确提取工具名和参数`() = runBlocking {
        var receivedArgs: JSONObject? = null
        ToolRegistry.register(object : AiTool {
            override val name = "echo"
            override val description = "echo tool"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: Context, args: JSONObject): String {
                receivedArgs = args
                return "ok:${args.optString("msg")}"
            }
        })
        val result = ToolRegistry.executeText(ctx, "【tool:echo msg=hello】")
        assertEquals("ok:hello", result)
        assertEquals("hello", receivedArgs?.getString("msg"))
    }

    @Test
    fun `executeText 不匹配的文本返回 null`() = runBlocking {
        val result = ToolRegistry.executeText(ctx, "这是一条普通消息")
        assertNull(result)
    }

    @Test
    fun `executeText 未知工具返回提示`() = runBlocking {
        val result = ToolRegistry.executeText(ctx, "【tool:nonexistent】")
        assertEquals("未知工具：nonexistent", result)
    }

    @Test
    fun `executeText 工具抛异常时返回错误信息`() = runBlocking {
        ToolRegistry.register(object : AiTool {
            override val name = "bad"
            override val description = "always fails"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: Context, args: JSONObject): String {
                throw RuntimeException("boom")
            }
        })
        val result = ToolRegistry.executeText(ctx, "【tool:bad】")
        assertNotNull(result)
        assertTrue(result!!.contains("执行失败"))
        assertTrue(result.contains("boom"))
    }

    @Test
    fun `executeText 带多个参数`() = runBlocking {
        var receivedArgs: JSONObject? = null
        ToolRegistry.register(object : AiTool {
            override val name = "multi"
            override val description = "multi param tool"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: Context, args: JSONObject): String {
                receivedArgs = args
                return "${args.optString("a")}-${args.optString("b")}"
            }
        })
        val result = ToolRegistry.executeText(ctx, "【tool:multi a=1 b=2】")
        assertEquals("1-2", result)
        assertEquals("1", receivedArgs?.getString("a"))
        assertEquals("2", receivedArgs?.getString("b"))
    }

    // ============ executeJson ============

    @Test
    fun `executeJson 正确执行并返回结果`() = runBlocking {
        ToolRegistry.register(object : AiTool {
            override val name = "json_tool"
            override val description = "json tool"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: Context, args: JSONObject): String {
                return "got:${args.optString("key")}"
            }
        })
        val args = JSONObject().put("key", "value")
        val result = ToolRegistry.executeJson(ctx, "json_tool", args)
        assertEquals("got:value", result)
    }

    @Test
    fun `executeJson 未知工具返回提示`() = runBlocking {
        val result = ToolRegistry.executeJson(ctx, "nonexistent", JSONObject())
        assertEquals("未知工具：nonexistent", result)
    }

    @Test
    fun `executeJson 工具抛异常时返回错误信息`() = runBlocking {
        ToolRegistry.register(object : AiTool {
            override val name = "crash"
            override val description = "crashes"
            override val inputSchema = JSONObject()
            override suspend fun execute(ctx: Context, args: JSONObject): String {
                throw IllegalStateException("crashed")
            }
        })
        val result = ToolRegistry.executeJson(ctx, "crash", JSONObject())
        assertNotNull(result)
        assertTrue(result!!.contains("执行失败"))
        assertTrue(result.contains("crashed"))
    }

    // ============ resetForTest ============

    @Test
    fun `resetForTest 清空所有已注册工具`() {
        ToolRegistry.register(fakeTool("a", "a"))
        ToolRegistry.register(fakeTool("b", "b"))
        assertEquals(2, ToolRegistry.getAll().size)

        ToolRegistry.resetForTest()
        assertTrue(ToolRegistry.getAll().isEmpty())
        assertNull(ToolRegistry.get("a"))
    }

    // ============ 辅助 ============

    private fun fakeTool(name: String, desc: String): AiTool = object : AiTool {
        override val name = name
        override val description = desc
        override val inputSchema = JSONObject()
        override suspend fun execute(ctx: Context, args: JSONObject): String = ""
    }

    private fun fakeToolWithSchema(name: String, desc: String, schema: JSONObject): AiTool =
        object : AiTool {
            override val name = name
            override val description = desc
            override val inputSchema = schema
            override suspend fun execute(ctx: Context, args: JSONObject): String = ""
        }
}
