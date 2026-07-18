package com.aftglw.devapi.network

import android.content.Context

object AiServiceFactory {
    private var appContext: Context? = null
    private var localService: LocalAiService? = null
    private var cachedService: AiService? = null
    private var cachedConfigHash: Int = 0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getService(): AiService {
        val ctx = appContext ?: return MockAiService(null)
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val protocol = prefs.getString("ai_protocol", "auto") ?: "auto"
        val url = prefs.getString("ai_api_url", "")?.trim() ?: ""
        val key = prefs.getString("ai_api_key", "")?.trim() ?: ""
        // 配置指纹变化时清缓存
        val hash = protocol.hashCode() * 31 + url.hashCode() + key.hashCode()
        if (hash != cachedConfigHash) { cachedService = null; cachedConfigHash = hash }

        // 本地模式
        if (protocol == "local") {
            if (localService == null) localService = LocalAiService(ctx)
            return localService!!
        }
        if (url.isEmpty() || key.isEmpty()) return MockAiService(ctx)

        // 缓存命中
        cachedService?.let { return it }

        // 显式协议选择 > auto URL 猜测
        val svc = when (protocol) {
            "claude" -> ClaudeAiService(ctx)
            "openai" -> OpenAiService(ctx)
            else -> {
                if (url.contains("claude", ignoreCase = true) || url.contains("anthropic", ignoreCase = true))
                    ClaudeAiService(ctx) else OpenAiService(ctx)
            }
        }
        cachedService = svc
        return svc
    }

    fun getProtocolName(): String {
        val ctx = appContext ?: return "未初始化"
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val protocol = prefs.getString("ai_protocol", "auto") ?: "auto"
        if (protocol != "auto") return protocol.replaceFirstChar { it.uppercase() }
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
