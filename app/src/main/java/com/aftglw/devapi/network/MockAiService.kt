package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage

class MockAiService(private val context: Context? = null) : AiService {

    private val defaultReplies = arrayOf(
        "好的，收到。", "知道了。", "是这样啊。",
        "稍等，查一下。", "哦。", "回头跟你说。",
        "明白了，谢谢。", "你说得对。"
    )

    override suspend fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?, toolCallsOut: MutableList<ToolCall>?): String? {
        val prefs = context?.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val custom = prefs?.getString("mock_replies", "")?.takeIf { it.isNotBlank() }
        val replies = if (custom != null) custom.split("|").filter { it.isNotBlank() }.toTypedArray()
        else defaultReplies
        if (replies.isEmpty()) return "..."

        val delay = prefs?.getString("mock_delay_ms", "800")?.toIntOrNull() ?: 800
        if (delay > 0) kotlinx.coroutines.delay(delay.toLong())
        return replies.random()
    }

    // sendMessageStream 直接复用 AiService 接口默认实现（runBlocking 包装 sendMessage 一次吐完整文本）。
    // 原 MockAiService 自定义的逐字符流式实现已删除：与 Mock 用途不符，且接口默认实现的 runBlocking
    // 包装足以保证非 suspend 调用方的兼容性。Task 3.2 会把 sendMessageStream 改 Flow。
}
