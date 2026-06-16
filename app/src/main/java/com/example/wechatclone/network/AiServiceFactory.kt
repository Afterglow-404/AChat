package com.example.wechatclone.network

import android.content.Context

object AiServiceFactory {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getService(): AiService {
        val ctx = appContext ?: return MockAiService(null)
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val url = prefs.getString("ai_api_url", "")?.trim() ?: ""
        val key = prefs.getString("ai_api_key", "")?.trim() ?: ""
        return if (url.isNotEmpty() && key.isNotEmpty()) RealAiService(ctx) else MockAiService(ctx)
    }
}
