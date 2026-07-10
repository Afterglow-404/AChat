package com.aftglw.devapi.network

import com.aftglw.devapi.model.ChatMessage

/**
 * 粗略估算一段文本的 token 数量。
 * 英文约 4 字符/token，CJK 约 1.5 字符/token，混合取加权。
 */
fun estimateTokenCount(text: String): Int {
    var ascii = 0; var other = 0
    for (c in text) { if (c.code <= 0x7F) ascii++ else other++ }
    return (ascii / 4) + (other * 2 / 3) + 1
}

interface AiService {
    fun sendMessage(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String = "",
        onError: ((String) -> Unit)? = null
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
        onError: ((String) -> Unit)? = null
    ) {
        val reply = sendMessage(history, userMessage, systemPrompt) ?: ""
        if (reply.isNotEmpty()) onChunk(reply)
        onDone(reply)
    }
}
