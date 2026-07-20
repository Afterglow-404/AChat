package com.aftglw.devapi.network

import com.aftglw.devapi.model.ChatMessage

/**
 * 粗略估算一段文本的 token 数量。
 * 粗略估算一段文本的 token 数量，会根据模型类型调整中文系数。
 *
 * 模型 tokenizer 差异：
 * - OpenAI/Gemini:  中文 ~1.5 字符/token
 * - Claude:         中文 ~1.8 字符/token
 * - DeepSeek:       中文 ~2.0 字符/token
 * - 默认:           中文 ~1.7 字符/token
 * - 英文统一 ~4 字符/token
 *
 * @param modelHint 模型名，用于推断 tokenizer 类型
 */
fun estimateTokenCount(text: String, modelHint: String = ""): Int {
    var ascii = 0; var other = 0
    for (c in text) { if (c.code <= 0x7F) ascii++ else other++ }
    val otherFactor = when {
        modelHint.contains("claude", ignoreCase = true) -> 5  // /9 * 2 ≈ 1.8
        modelHint.contains("deepseek", ignoreCase = true) -> 2 // /2 = 2.0
        modelHint.contains("gpt", ignoreCase = true) ||
        modelHint.contains("gemini", ignoreCase = true) ||
        modelHint.contains("chatgpt", ignoreCase = true) -> 3  // *2/3 ≈ 1.5
        else -> 17 // /10*17/10 ≈ 1.7
    }
    return (ascii / 4) + (other * otherFactor / 10) + 1
}

interface AiService {
    /**
     * 非流式发送。Phase 3 起改为 `suspend`，调用方需在协程中调用；
     * service 内部已用 OkHttp/HttpRetry 的 suspend 链路，不再 `runBlocking`。
     */
    suspend fun sendMessage(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String = "",
        onError: ((String) -> Unit)? = null,
        /** 输出参数：原生 tool_calls 收集器（为 null 时兼容旧调用方） */
        toolCallsOut: MutableList<ToolCall>? = null
    ): String?

    /**
     * SSE 流式接口（callback 风格）。
     *
     * 默认实现回退到 [sendMessage] 一次吐完整文本；用 `runBlocking` 包装是因为本接口仍是
     * 非 suspend 的回调风格，而 [sendMessage] 自 Phase 3 起改为 `suspend`。
     * Task 3.2 会把本接口改为 `Flow<String>`，届时移除此 `runBlocking`。
     */
    fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String = "",
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: ((String) -> Unit)? = null,
        toolCallsOut: MutableList<ToolCall>? = null
    ) {
        val reply = kotlinx.coroutines.runBlocking { sendMessage(history, userMessage, systemPrompt, onError, toolCallsOut) } ?: ""
        if (reply.isNotEmpty()) onChunk(reply)
        onDone(reply)
    }

    /**
     * 取消正在进行的请求（如有）。
     *
     * 实现侧应：
     * - 取消当前在飞的 OkHttp Call（如有），让其抛出 IOException("Canceled")
     * - 清理内部状态，确保下次调用可正常发送
     *
     * 默认空实现，给不需要取消语义的 service（如 Mock/Local）使用。
     * 在 UI 层挂载"停止生成"按钮时调用。
     */
    fun cancel() { /* default no-op */ }
}

/** 原生 tool_call 结构（与 Agent.regex 解耦） */
data class ToolCall(
    val name: String,
    val arguments: String,
    val id: String = "",
)
