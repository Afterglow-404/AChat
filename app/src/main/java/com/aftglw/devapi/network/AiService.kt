package com.aftglw.devapi.network

import com.aftglw.devapi.model.ChatMessage

interface AiService {
    fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String = ""): String?
}
