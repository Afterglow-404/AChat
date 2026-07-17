package com.aftglw.devapi.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MCP Bridge：连接外部 MCP Server 到 Wisp 的工具系统。
 *
 * 1. 从配置读取 MCP Server URL 列表
 * 2. 每个 Server 调用 tools/list 获取可用工具
 * 3. 将外部工具包装成 AiTool 注册到 ToolRegistry
 * 4. 定期刷新工具列表
 */
object MCPBridge {

    private val externalTools = mutableMapOf<String, MCPClient>()

    /** 是否已初始化 */
    var initialized = false
        private set

    /** 刷新所有已配置的外部 MCP 工具 */
    suspend fun refresh(ctx: Context) {
        withContext(Dispatchers.IO) {
            val clients = MCPClient.fromConfig(ctx)
            // 清空旧的外部工具
            val oldTools = externalTools.keys.toSet()
            oldTools.forEach { name ->
                externalTools.remove(name)
                val old = ToolRegistry.get(name)
                // 只移除由 MCP 注册的工具
                if (old is MCPToolProxy) {
                    // ToolRegistry 没有 remove 方法，只能跳过注册
                }
            }
            externalTools.clear()

            // 注册新的
            for (client in clients) {
                val result = client.listTools()
                if (result.isFailure) continue
                for (info in result.getOrThrow()) {
                    val proxy = MCPToolProxy(info.name, info.description, info.inputSchema, client)
                    externalTools[info.name] = client
                    ToolRegistry.register(proxy)
                }
            }
            initialized = true
        }
    }

    /** 初始化：从配置加载并注册外部工具 */
    suspend fun init(ctx: Context) {
        if (initialized) return
        refresh(ctx)
    }
}

/**
 * 外部 MCP 工具的代理包装。
 * 调用 execute 时通过 MCPClient 发 JSON-RPC 请求到远端。
 */
private class MCPToolProxy(
    override val name: String,
    override val description: String,
    override val inputSchema: JSONObject,
    private val client: MCPClient
) : AiTool {

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val result = client.callTool(name, args)
        return result.getOrElse { e -> "MCP 工具调用失败：${e.message}" }
    }
}
