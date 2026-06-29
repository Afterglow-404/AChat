package com.aftglw.devapi.network

import android.content.Context

object AiServiceFactory {
    private var appContext: Context? = null
    private var localService: LocalAiService? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getService(): AiService {
        val ctx = appContext ?: return MockAiService(null)
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val localMode = prefs.getBoolean("local_mode", false)

        // 本地模式优先
        if (localMode) {
            if (localService == null) localService = LocalAiService(ctx)
            return localService!!
        }

        val url = prefs.getString("ai_api_url", "")?.trim() ?: ""
        val key = prefs.getString("ai_api_key", "")?.trim() ?: ""
        if (url.isEmpty() || key.isEmpty()) return MockAiService(ctx)
        // 检测协议：URL 含 claude/anthropic → Claude，其余 → OpenAI
        return if (url.contains("claude", ignoreCase = true) || url.contains("anthropic", ignoreCase = true))
            ClaudeAiService(ctx) else OpenAiService(ctx)
    }

    fun getProtocolName(): String {
        val ctx = appContext ?: return "未初始化"
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val url = prefs.getString("ai_api_url", "")?.trim() ?: ""
        return when {
            url.contains("claude", ignoreCase = true) || url.contains("anthropic", ignoreCase = true) -> "Claude"
            url.contains("deepseek", ignoreCase = true) -> "DeepSeek"
            url.contains("openrouter", ignoreCase = true) -> "OpenRouter"
            url.contains("openai", ignoreCase = true) -> "OpenAI"
            url.isNotEmpty() -> "OpenAI 兼容"
            else -> "未配置"
        }
    }

    fun unloadLocal() {
        localService?.unload()
        localService = null
    }
}
