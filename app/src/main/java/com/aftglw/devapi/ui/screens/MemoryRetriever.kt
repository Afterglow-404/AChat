package com.aftglw.devapi.ui.screens

import android.content.Context
import com.aftglw.devapi.MemoryStore
import com.aftglw.devapi.MoodDetector

object MemoryRetriever {
    suspend fun retrieve(ctx: Context, name: String, lastMessage: String): String {
        val mood = MoodDetector.lastMood
        val query = when (mood) {
            "悲伤" -> "$lastMessage 需要安慰关心"
            "害怕" -> "$lastMessage 安全感安抚"
            "愤怒" -> "$lastMessage 冷静积极解决问题"
            "开心" -> "$lastMessage 有趣好笑共同回忆"
            "厌恶" -> null
            else -> lastMessage
        }
        return if (query != null) {
            MemoryStore.search(ctx, query, 1).joinToString("\n") { "- ${it.text}" }
        } else ""
    }
}
