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
        val label: String
        val conf: Float
        if (modelIdx != null && MoodModel.lastConfidence >= 0.6f) {
            // ONNX 置信度足够，直接使用
            label = MoodModel.labels.getOrNull(modelIdx) ?: "中性"
            conf = MoodModel.lastConfidence
        } else {
            // ONNX 置信度不足或不可用，走 API 兜底
            label = withContext(kotlinx.coroutines.Dispatchers.IO) {
                apiFallback(ctx, userMessage, history)
            }
            conf = 0.5f
        }
        if (label.isNotBlank()) {
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
            lastMood = result.mood; lastHint = result.hint; lastSource = if (conf >= 0.6f) "model" else "api"
            appCtx?.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString("last_mood_$currentChatName", label ?: "")?.apply()
            return result
        }
        lastMood = null; lastHint = null; lastSource = "model_unavailable"
        return MoodInfo(null, null)
    }

    private suspend fun apiFallback(ctx: android.content.Context?, userMessage: String, history: List<String>): String {
        val c = ctx ?: return ""
        val contextText = history.takeLast(4).joinToString("\n")
        val inputText = if (contextText.isNotEmpty()) "[上文]$contextText\n[当前]$userMessage" else userMessage
        val prompt = "分析这句话中说话者的情绪，只输出一个词：开心、悲伤、愤怒、害怕、惊讶、厌恶、中性"
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aftglw.devapi.network.AiServiceFactory.getService()
                    .sendMessage(emptyList(), inputText, prompt)
            }?.trim()?.take(2)?.let { raw ->
                when {
                    raw.contains("开心") -> "开心"; raw.contains("悲伤") -> "悲伤"
                    raw.contains("愤怒") -> "愤怒"; raw.contains("害怕") -> "害怕"
                    raw.contains("惊讶") -> "惊讶"; raw.contains("厌恶") -> "厌恶"
                    else -> "中性"
                }
            } ?: ""
        } catch (_: Exception) { "" }
    }
}
