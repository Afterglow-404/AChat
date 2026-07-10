package com.aftglw.devapi

import android.content.Context

data class MoodInfo(val mood: String?, val hint: String?)

/**
 * 情绪检测器 — 纯 Prompt 方案。
 *
 * 不再依赖 ONNX 本地模型，全部通过 LLM API 判断情绪。
 * 比 ONNX 小模型更准（利用了大模型的语义理解能力），
 * 且避免了 ONNX Runtime 在 iOS 上的移植问题。
 */
object MoodDetector {
    var lastMood: String? = null
    var lastHint: String? = null
    var lastSource = "api"
    var feedCount = 0
    private var currentChatName = ""

    private val labels = listOf(
        "开心", "悲伤", "愤怒", "害怕",
        "惊讶", "厌恶", "中性"
    )

    fun init(context: Context, chatName: String = "") { currentChatName = chatName }

    /**
     * 分析用户消息中的情绪。
     *
     * @param userMessage 当前用户消息
     * @param history 历史消息列表
     * @return MoodInfo(mood, hint)，mood 为空表示分析失败
     */
    suspend fun feed(userMessage: String, history: List<String> = emptyList()): MoodInfo {
        feedCount++

        val label = detectEmotion(userMessage, history)

        if (label.isNotBlank()) {
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

    /**
     * 调用 LLM API 检测情绪。
     * 使用极简 prompt 追求快速响应。
     */
    private suspend fun detectEmotion(userMessage: String, history: List<String>): String {
        val contextText = history.takeLast(4).joinToString("\n")
        val inputText = if (contextText.isNotEmpty()) {
            "[上文]\n$contextText\n[当前]\n$userMessage"
        } else {
            userMessage
        }

        // 极简 prompt：要求只输出一个词，降低延迟
        val prompt = buildString {
            appendLine("分析以下对话中说话者的情绪。")
            appendLine("只输出一个词，不要解释，不要标点：")
            appendLine(labels.joinToString("、"))
            appendLine()
            appendLine(inputText)
            append("情绪：")
        }

        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aftglw.devapi.network.AiServiceFactory.getService()
                    .sendMessage(emptyList(), inputText, prompt)
            }?.trim()?.let { raw ->
                // 精确匹配
                for (label in labels) {
                    if (raw.contains(label)) return@let label
                }
                // 模糊备选
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
