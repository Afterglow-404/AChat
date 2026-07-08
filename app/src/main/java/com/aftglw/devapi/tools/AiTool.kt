package com.aftglw.devapi.tools

import org.json.JSONObject

/**
 * MCP 风格工具定义。
 *
 * 所有工具需要提供 JSON Schema 格式的 [inputSchema]，
 * 替代旧的纯文本 [description] 参数说明。
 */
interface AiTool {
    /** 工具名（英文小写+下划线，AI 通过此名调用） */
    val name: String

    /** 工具描述（AI 据此判断何时调用） */
    val description: String

    /**
     * JSON Schema 描述输入参数。
     *
     * 格式示例：
     * ```json
     * {
     *   "type": "object",
     *   "properties": {
     *     "text": { "type": "string", "description": "要记住的内容" }
     *   },
     *   "required": ["text"]
     * }
     * ```
     */
    val inputSchema: JSONObject

    /** 执行工具逻辑 */
    suspend fun execute(ctx: android.content.Context, args: JSONObject): String

    /** 将 key=val 文本参数格式转为 JSONObject（兼容旧 【tool:】 调用格式） */
    fun parseTextArgs(raw: String): JSONObject {
        val obj = JSONObject()
        val pairs = raw.split(Regex("\\s+")).filter { it.contains("=") }
        for (pair in pairs) {
            val (k, v) = pair.split("=", limit = 2)
            obj.put(k.trim(), v.trim())
        }
        return obj
    }

    /** 输出 MCP tools/list 格式的工具描述 JSON */
    fun toMcpToolJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }
}
