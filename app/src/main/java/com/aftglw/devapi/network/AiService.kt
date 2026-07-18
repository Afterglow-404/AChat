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
    fun sendMessage(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String = "",
        onError: ((String) -> Unit)? = null,
        /** 输出参数：原生 tool_calls 收集器（为 null 时兼容旧调用方） */
        toolCallsOut: MutableList<ToolCall>? = null
    ): String?

    /**
     * SSE 流式接口。
     * 默认实现回退到 [sendMessage] 一次吐完整文本。
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
        val reply = sendMessage(history, userMessage, systemPrompt, onError, toolCallsOut) ?: ""
        if (reply.isNotEmpty()) onChunk(reply)
        onDone(reply)
    }
}

/** 原生 tool_call 结构（与 Agent.regex 解耦） */
data class ToolCall(
    val name: String,
    val arguments: String,
    val id: String = "",
)
