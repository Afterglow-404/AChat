package com.aftglw.devapi.core.mood

import android.content.Context

data class MoodInfo(val mood: String?, val hint: String?)

/**
 * 情绪检测器 — 本地 ONNX 模型优先 + LLM API 兜底。
 *
 * 推理路径：
 *   1. MoodModel 已加载 → 本地 ONNX 推理（19 类）
 *   2. MoodModel 不可用或返回 null → LLM API 检测（7 类）
 */
object MoodDetector {
    var lastMood: String? = null
    var lastHint: String? = null
    var lastSource = "none"
    var feedCount = 0
    private var currentChatName = ""

    /** 7 类映射（用于 API 模式和前端显示） */
    private val apiLabels = listOf(
        "开心", "悲伤", "愤怒", "害怕",
        "惊讶", "厌恶", "中性"
    )

    /** 19 类 → 7 类映射（本地模型结果归一化） */
    private val moodNormalize = mapOf(
        "高兴" to "开心", "兴奋" to "开心",
        "平静" to "中性",
        "惊讶" to "惊讶",
        "厌恶" to "厌恶",
        "哭泣" to "悲伤",
        "害怕" to "害怕",
        "生气" to "愤怒",
        "害羞" to "中性", "紧张" to "害怕",
        "担心" to "害怕", "无奈" to "悲伤",
        "疑惑" to "惊讶", "慌张" to "害怕",
        "心动" to "开心", "调皮" to "开心",
        "认真" to "中性", "自信" to "开心",
        "难为情" to "中性",
    )

    fun init(context: Context, chatName: String = "") {
        currentChatName = chatName
        // 后台线程加载本地模型
        Thread {
            MoodTokenizer.load(context)
            MoodModel.load(context)
            if (MoodModel.isLoaded) {
                android.util.Log.i("MoodDetector", "Local emotion model loaded")
            }
        }.start()
    }

    /**
     * 分析用户消息中的情绪。
     *
     * 路径：本地 ONNX → LLM API → null
     */
    suspend fun feed(userMessage: String, history: List<String> = emptyList()): MoodInfo {
        feedCount++

        // 1. 尝试本地模型
        if (MoodModel.isLoaded) {
            val localLabel = MoodModel.predict(userMessage)
            if (localLabel != null) {
                val normalized = moodNormalize[localLabel] ?: "中性"
                val hint = hintFor(normalized)
                val result = MoodInfo(normalized, hint)
                lastMood = result.mood
                lastHint = result.hint
                lastSource = "local"
                return result
            }
        }

        // 2. 降级到 LLM API
        val label = detectEmotion(userMessage, history)
        if (label.isNotBlank()) {
            val hint = hintFor(label)
            val result = MoodInfo(label, hint)
            lastMood = result.mood
            lastHint = result.hint
            lastSource = "api"
            return result
        }

        lastMood = null
        lastHint = null
        lastSource = "failed"
        return MoodInfo(null, null)
    }

    private fun hintFor(mood: String): String? = when (mood) {
        "开心" -> "ta很开心，可以一起开心地聊"
        "悲伤" -> "ta好像不太开心，用温暖安慰的语气回复"
        "愤怒" -> "ta情绪可能不佳，请用耐心温柔的语气回复"
        "惊讶" -> "ta有些惊讶，语气可以配合一下"
        "害怕" -> "ta似乎有些不安，请用让人安心的语气"
        "厌恶" -> "ta情绪不太好，注意语气"
        else -> null
    }

    /**
     * 调用 LLM API 检测情绪。
     */
    private suspend fun detectEmotion(userMessage: String, history: List<String>): String {
        val contextText = history.takeLast(4).joinToString("\n")
        val inputText = if (contextText.isNotEmpty()) {
            "[上文]\n$contextText\n[当前]\n$userMessage"
        } else {
            userMessage
        }

        val prompt = buildString {
            appendLine("分析以下对话中说话者的情绪。")
            appendLine("只输出一个词，不要解释，不要标点：")
            appendLine(apiLabels.joinToString("、"))
            appendLine()
            appendLine(inputText)
            append("情绪：")
        }

        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aftglw.devapi.network.AiServiceFactory.getService()
                    .sendMessage(emptyList(), inputText, prompt)
            }?.trim()?.let { raw ->
                for (label in apiLabels) {
                    if (raw.contains(label)) return@let label
                }
                when {
                    raw.contains("好") || raw.contains("乐") -> "开心"
                    raw.contains("伤") || raw.contains("哭") -> "悲伤"
                    raw.contains("怒") || raw.contains("气") -> "愤怒"
                    raw.contains("怕") || raw.contains("恐") -> "害怕"
                    raw.contains("惊") -> "惊讶"
                    raw.contains("厌") || raw.contains("恶") || raw.contains("烦") -> "厌恶"
                    else -> "中性"
                }
            } ?: ""
        } catch (e: Exception) {
            android.util.Log.w("MoodDetector", "emotion API failed", e)
            ""
        }
    }
}
