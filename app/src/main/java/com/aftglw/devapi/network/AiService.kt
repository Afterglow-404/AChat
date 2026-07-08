package com.aftglw.devapi.network

import com.aftglw.devapi.model.ChatMessage

interface AiService {
    fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String = ""): String?

    /**
     * SSE 流式接口。
     * 默认实现回退到 [sendMessage] 一次吐完整文本。
     */
    fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String = "",
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit
    ) {
        val reply = sendMessage(history, userMessage, systemPrompt) ?: ""
        if (reply.isNotEmpty()) onChunk(reply)
        onDone(reply)
    }
}
