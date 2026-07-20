package com.aftglw.devapi.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 风格工具注册中心。
 *
 * - 注册内置工具
 * - 注册外部 MCP Server 工具（通过 MCPBridge）
 * - 输出 MCP tools/list 兼容的工具描述
 * - 执行工具调用
 */
object ToolRegistry {

    private val tools = mutableMapOf<String, AiTool>()

    /** OpenAI tools 数组缓存；register 时 invalidate */
    @Volatile private var toolsJsonCache: JSONArray? = null

    /** 注册一个工具 */
    fun register(tool: AiTool) {
        tools[tool.name] = tool
        invalidateToolsCache()
    }

    /** 按名称移除已注册的工具 */
    fun unregister(name: String) {
        synchronized(tools) {
            tools.remove(name)
        }
        invalidateToolsCache()
    }

    /** 按谓词批量移除工具（用于 MCP 刷新时批量移除旧工具） */
    fun unregisterByOwner(predicate: (AiTool) -> Boolean) {
        synchronized(tools) {
            val it = tools.entries.iterator()
            while (it.hasNext()) {
                if (predicate(it.next().value)) {
                    it.remove()
                }
            }
        }
        invalidateToolsCache()
    }

    /** 按名称获取工具 */
    fun get(name: String): AiTool? = tools[name]

    /** 获取所有已注册工具 */
    fun getAll(): List<AiTool> = tools.values.toList()

    /**
     * 返回 OpenAI 兼容的 tools JSON 数组（带缓存）。
     * 命中 cache 零开销；register/unregister 后自动 invalidate。
     */
    fun getToolsJson(): JSONArray {
        toolsJsonCache?.let { return it }
        val arr = JSONArray()
        for (tool in tools.values) {
            arr.put(org.json.JSONObject().apply {
                put("type", "function")
                put("function", org.json.JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.inputSchema)
                })
            })
        }
        toolsJsonCache = arr
        return arr
    }

    /** 失效 tools JSON 缓存；register / unregister / resetForTest 后调用 */
    fun invalidateToolsCache() {
        toolsJsonCache = null
    }

    /** 测试用：清空所有注册的工具 */
    fun resetForTest() {
        tools.clear()
        invalidateToolsCache()
    }

    /** 输出 MCP tools/list 格式的 JSON 数组 */
    fun listToolsJson(): JSONArray = JSONArray().apply {
        for (tool in tools.values) {
            put(tool.toMcpToolJson())
        }
    }

    /**
     * 输出给 AI 使用的纯文本工具描述。
     * 格式：工具名: 描述 | 参数名(类型,必填): 说明
     */
    fun getDescriptions(): String {
        return tools.values.joinToString("\n") { tool ->
            val params = tool.inputSchema.optJSONObject("properties") ?: JSONObject()
            val required = buildSet {
                val arr = tool.inputSchema.optJSONArray("required") ?: return@buildSet
                for (i in 0 until arr.length()) { add(arr.getString(i)) }
            }
            val paramLines = params.keys().asSequence().map { key ->
                val p = params.getJSONObject(key)
                val type = p.optString("type", "string")
                val desc = p.optString("description", "")
                val req = if (key in required) "必填" else "可选"
                "  ${key}(${type},${req}): ${desc}"
            }.toList()
            buildString {
                appendLine("【tool:${tool.name}】${tool.description}")
                if (paramLines.isNotEmpty()) {
                    paramLines.forEach { appendLine(it) }
                }
            }.trimEnd()
        }
    }

    /** 执行工具调用，从 【tool:name args】 文本格式 */
    suspend fun executeText(ctx: android.content.Context, callText: String): String? {
        val m = Regex("""【tool:(\w+)\s*(.*?)】""").find(callText) ?: return null
        val toolName = m.groupValues[1]
        val rawArgs = m.groupValues[2].trim()
        val tool = tools[toolName] ?: return "未知工具：$toolName"
        val args = tool.parseTextArgs(rawArgs)
        return try {
            tool.execute(ctx, args)
        } catch (e: Exception) {
            "工具 $toolName 执行失败：${e.message}"
        }
    }

    /** 执行 JSON 格式的工具调用 */
    suspend fun executeJson(ctx: android.content.Context, name: String, args: JSONObject): String? {
        val tool = tools[name] ?: return "未知工具：$name"
        return try {
            tool.execute(ctx, args)
        } catch (e: Exception) {
            "工具 $name 执行失败：${e.message}"
        }
    }

    /** 初始化（自动注册内置工具） */
    fun init(ctx: android.content.Context) {
        if (tools.isNotEmpty()) return
        register(TimeTool())
        register(NoteTool())
        register(RecallTool())
        register(SendMessageTool())
        register(WebSearchTool())
        register(LocationTool())
        register(ReadNotificationsTool())
        register(ReadAppUsageTool())
        register(BatteryTool())
        register(ScreenTool())
        register(CalculatorTool())
        // 扫描并注册动态工具（.wsptool 描述文件）
        com.aftglw.devapi.core.tools.ToolScanner.scan(ctx)
    }
}
