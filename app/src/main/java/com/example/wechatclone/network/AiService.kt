package com.example.wechatclone.network

import com.example.wechatclone.model.ChatMessage

interface AiService {
    fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String = ""): String?
}
