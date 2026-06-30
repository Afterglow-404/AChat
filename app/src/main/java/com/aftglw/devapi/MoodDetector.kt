package com.aftglw.devapi

import android.content.Context
import kotlinx.coroutines.withContext

data class MoodInfo(val mood: String?, val hint: String?)

object MoodDetector {
    var lastMood: String? = null
    var lastHint: String? = null
    var lastSource = ""
    var lastModelError = ""
    var feedCount = 0
    private var appCtx: Context? = null
    private var currentChatName = ""

    fun init(context: Context, chatName: String = "") { appCtx = context.applicationContext; currentChatName = chatName }

    suspend fun feed(userMessage: String, history: List<String> = emptyList()): MoodInfo {
        feedCount++
        val ctx = appCtx
        if (ctx != null) {
            if (MoodModel.isDownloaded(ctx)) MoodModel.load(ctx)
        }
        lastModelError = MoodModel.lastError

        // 构造上文 + 当前
        val contextText = history.takeLast(6).joinToString("\n") { it }
        val inputText = if (contextText.isNotEmpty()) "[上文]\n$contextText\n[当前]\n$userMessage" else userMessage

        val contextForModel = appCtx ?: return MoodInfo(null, null)
        val modelIdx = withContext(kotlinx.coroutines.Dispatchers.IO) { MoodModel.classify(inputText, contextForModel) }
        if (modelIdx != null) {
            val conf = MoodModel.lastConfidence
            val label = MoodModel.labels.getOrNull(modelIdx) ?: "中性"
            // 更新好感度
            val prefs = appCtx?.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
            if (prefs != null && AffinityManager.isAutoMode(prefs, currentChatName)) {
                // 情绪倾诉（悲伤/害怕）→ 加好感（信任 AI）
                // 生气（对外）→ 微加
                // 厌恶 → 减好感
                val moodType = when (label) {
                    "开心", "悲伤", "害怕" -> "positive"
                    else -> "neutral"
                }
                val isDisgust = label == "厌恶"
                if (isDisgust) AffinityManager.update("negative", conf, prefs, currentChatName)
                else AffinityManager.update(moodType, conf, prefs, currentChatName)
            }
            val hint = when (label) {
                "开心" -> "ta很开心，可以一起开心地聊"
                "悲伤" -> "ta好像不太开心，用温暖安慰的语气回复"
                "愤怒" -> "ta情绪可能不佳，请用耐心温柔的语气回复"
                "惊讶" -> "ta有些惊讶，语气可以配合一下"
                "害怕" -> "ta似乎有些不安，请用让人安心的语气"
                "厌恶" -> "ta情绪不太好，注意语气"
                else -> null
            }
            val result = MoodInfo(label, hint)
            lastMood = result.mood; lastHint = result.hint; lastSource = "model"
            appCtx?.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString("last_mood_$currentChatName", label ?: "")?.apply()
            return result
        }
        lastMood = null; lastHint = null; lastSource = "model_unavailable"
        return MoodInfo(null, null)
    }
}
