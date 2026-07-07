package com.aftglw.devapi.tools

interface AiTool {
    val name: String
    val description: String
    suspend fun execute(ctx: android.content.Context, args: Map<String, String>): String
}

object ToolRegistry {
    private val tools = mutableMapOf<String, AiTool>()

    fun register(tool: AiTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AiTool? = tools[name]

    fun getAll(): List<AiTool> = tools.values.toList()

    fun getDescriptions(): String {
        return tools.values.joinToString("\n") { "【tool:${it.name}】${it.description}" }
    }

    fun init(ctx: android.content.Context) {
        if (tools.isNotEmpty()) return
        register(TimeTool())
        register(NoteTool())
        register(RecallTool())
        register(SendMessageTool())
    }
}
