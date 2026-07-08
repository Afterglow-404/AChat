package com.aftglw.devapi.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * MCP JSON-RPC 2.0 客户端。
 *
 * 通过 HTTP/HTTPS 和外部 MCP Server 通信，符合标准 MCP 协议。
 *
 * 调用流程：
 *   1. listTools() → 获取服务端工具列表
 *   2. callTool(name, args) → 调用指定工具
 */
class MCPClient(val baseUrl: String) {

    data class ToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    /** 调用 MCP 的 tools/list，返回工具列表 */
    fun listTools(): Result<List<ToolInfo>> = runCatching {
        val resp = jsonRpcCall("tools/list", JSONObject())
        val tools = resp.optJSONArray("result") ?: resp.optJSONArray("tools") ?: JSONArray()
        (0 until tools.length()).map { i ->
            val t = tools.getJSONObject(i)
            ToolInfo(
                name = t.getString("name"),
                description = t.optString("description", ""),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
            )
        }
    }

    /** 调用 MCP 的 tools/call，返回工具执行结果 */
    fun callTool(name: String, arguments: JSONObject): Result<String> = runCatching {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val resp = jsonRpcCall("tools/call", params)
        val result = resp.optJSONObject("result")
        val content = result?.optJSONArray("content")
        if (content != null && content.length() > 0) {
            content.getJSONObject(0).optString("text", "")
        } else {
            result?.optString("text", "") ?: ""
        }
    }

    /** 发送 JSON-RPC 2.0 请求 */
    private fun jsonRpcCall(method: String, params: JSONObject): JSONObject {
        val requestId = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            put("params", params)
        }

        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000

        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 从环境/配置解析 MCP Server URL */
        fun fromConfig(ctx: android.content.Context): List<MCPClient> {
            val raw = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                .getString("mcp_servers", "[]") ?: "[]"
            val urls = org.json.JSONArray(raw)
            return (0 until urls.length()).map { i ->
                MCPClient(urls.getString(i))
            }
        }

        /** 保存 MCP Server 配置 */
        fun saveConfig(ctx: android.content.Context, urls: List<String>) {
            val arr = JSONArray(urls)
            ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                .edit().putString("mcp_servers", arr.toString()).apply()
        }
    }
}
