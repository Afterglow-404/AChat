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
        return if (url.isNotEmpty() && key.isNotEmpty()) RealAiService(ctx) else MockAiService(ctx)
    }

    fun unloadLocal() {
        localService?.unload()
        localService = null
    }
}
