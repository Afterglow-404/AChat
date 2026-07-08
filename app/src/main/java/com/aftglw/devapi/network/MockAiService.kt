package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MockAiService(private val context: Context? = null) : AiService {

    private val defaultReplies = arrayOf(
        "好的，收到。", "知道了。", "是这样啊。",
        "稍等，查一下。", "哦。", "回头跟你说。",
        "明白了，谢谢。", "你说得对。"
    )

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String): String {
        val prefs = context?.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val custom = prefs?.getString("mock_replies", "")?.takeIf { it.isNotBlank() }
        val replies = if (custom != null) custom.split("|").filter { it.isNotBlank() }.toTypedArray()
        else defaultReplies
        if (replies.isEmpty()) return "..."

        val delay = prefs?.getString("mock_delay_ms", "800")?.toIntOrNull() ?: 800
        if (delay > 0) Thread.sleep(delay.toLong())
        return replies.random()
    }

    override fun sendMessageStream(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        onChunk: (String) -> Unit, onDone: (String) -> Unit
    ) {
        val reply = sendMessage(history, userMessage, systemPrompt)
        CoroutineScope(Dispatchers.Default).launch {
            for (ch in reply) { onChunk(ch.toString()); delay(40L) }
            onDone(reply)
        }
    }
}
