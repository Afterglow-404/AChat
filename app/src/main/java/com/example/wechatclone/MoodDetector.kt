package com.example.wechatclone

data class MoodInfo(val mood: String?, val hint: String?)

object MoodDetector {
    private val recentLengths = mutableListOf<Int>()
    private val recentNegatives = mutableListOf<Boolean>()

    fun feed(userMessage: String): MoodInfo {
        val text = userMessage.trim()

        // Update sliding window stats
        recentLengths.add(text.length)
        if (recentLengths.size > 10) recentLengths.removeAt(0)

        val isShort = text.length <= 3 && text.replace("。", "").isBlank()
        val isNegative = text.contains("算了") || text.contains("没事") || text.contains("行吧") ||
            text.contains("呵") || text.contains("随便") || text.contains("不想说")
        recentNegatives.add(isNegative)
        if (recentNegatives.size > 5) recentNegatives.removeAt(0)

        // Detect behavioral pattern
        val avgLength = if (recentLengths.isNotEmpty()) recentLengths.average() else 0.0
        val negativeCount = recentNegatives.count { it }

        // 先用 ONNX 模型分类（如果已下载）
        val modelMood = MoodModel.classify(text)
        if (modelMood != null) {
            return when (modelMood) {
                "negative" -> MoodInfo("negative", "用户情绪可能不佳，请用更耐心温柔的语气回复")
                "positive" -> MoodInfo("positive", "用户正在开心，可以一起开心地聊")
                else -> {
                    // 模型说是中性，但规则检测到负面信号时覆盖
                    if (isNegative || negativeCount >= 3)
                        MoodInfo("negative", "用户情绪可能不佳，请用更耐心温柔的语气回复")
                    else if (isShort && avgLength < 5)
                        MoodInfo("quiet", "用户今天话比较少，不必强求长回复")
                    else MoodInfo(null, null)
                }
            }
        }

        // 无模型 → 纯规则兜底
        return when {
            isNegative || negativeCount >= 3 -> MoodInfo("negative", "用户情绪可能不佳，请用更耐心温柔的语气回复")
            isShort && avgLength < 5 -> MoodInfo("quiet", "用户今天话比较少，不必强求长回复")
            text.contains("哈哈") || text.contains("笑") -> MoodInfo("positive", "用户正在开心，可以一起开心地聊")
            else -> MoodInfo(null, null)
        }
    }
}
