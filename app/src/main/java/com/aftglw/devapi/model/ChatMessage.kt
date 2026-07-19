package com.aftglw.devapi.model

data class ChatMessage(
    val role: String,
    val content: String,
    // 多模态：本地图片绝对路径列表。OpenAiService/ClaudeAiService 会将其编码为 base64
    // 内容块发送给支持 vision 的模型；images 为空时走原纯文本路径。
    val images: List<String> = emptyList()
)
