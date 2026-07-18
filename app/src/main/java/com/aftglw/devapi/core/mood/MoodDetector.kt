package com.aftglw.devapi.core.mood

import android.content.Context
import android.util.Log

data class MoodInfo(val mood: String?, val hint: String?)

/**
 * 情绪检测器 — 三层混合架构：
 *
 * 1. 规则层（预规则）— 标点符号 / 网络用语 / 礼貌用语 直接命中
 * 2. ONNX 模型层 — v8 19 类本地推理
 * 3. 规则层（后处理）— 短文本修正 / 置信度兜底
 * 4. LLM API 兜底 — 反讽检测 / 模型失败时
 */
object MoodDetector {
    var lastMood: String? = null
    var lastHint: String? = null
    var lastSource = "none"
    var feedCount = 0
    private var currentChatName = ""

    /** 测试/重置用：清除所有状态 */
    fun resetForTest() {
        lastMood = null; lastHint = null; lastSource = "none"
        feedCount = 0; currentChatName = ""
    }

    private val apiLabels = listOf(
        "开心", "悲伤", "愤怒", "害怕",
        "惊讶", "厌恶", "中性"
    )

    private val moodNormalize = mapOf(
        "高兴" to "开心", "兴奋" to "开心",
        "平静" to "中性", "惊讶" to "惊讶",
        "厌恶" to "厌恶", "哭泣" to "悲伤",
        "害怕" to "害怕", "生气" to "愤怒",
        "害羞" to "中性", "紧张" to "害怕",
        "担心" to "害怕", "无奈" to "悲伤",
        "疑惑" to "惊讶", "慌张" to "害怕",
        "心动" to "开心", "调皮" to "开心",
        "认真" to "中性", "自信" to "开心",
        "难为情" to "中性",
    )

    // ============ 规则层：预规则 ============

    /** 标点符号消息 */
    private val punctuationPattern = Regex("^[。，、！？…～\\.\\,\\?\\!\\~\\s]+$")

    /** 表情/语气词 → 情绪 快速映射 */
    private val emojiMap = mapOf(
        "😂" to "开心", "😭" to "悲伤", "😡" to "愤怒",
        "😱" to "惊讶", "😨" to "害怕", "😒" to "厌恶",
        "🥰" to "开心", "😤" to "愤怒", "😢" to "悲伤",
        "😊" to "开心", "🙏" to "中性",
    )

    /** 网络用语 / 流行语 → 情绪 */
    private val slangMap = mapOf(
        "yyyds" to "兴奋", "YYDS" to "兴奋", "yyds" to "兴奋",
        "绝绝子" to "兴奋", "emo了" to "悲伤", "emo" to "悲伤",
        "破防了" to "惊讶", "破防" to "惊讶",
        "蚌埠住了" to "无奈", "蚌埠住" to "无奈",
        "6" to "中性", "躺平" to "无奈", "内卷" to "无奈",
        "上头" to "兴奋", "下头" to "厌恶", "社死" to "难为情",
        "我麻了" to "无奈", "麻了" to "无奈",
        "绷不住了" to "悲伤", "家人们" to "无奈",
        "泰酷辣" to "兴奋",
    )

    /** 礼貌用语 → 平静 */
    private val politeSet = setOf(
        "谢谢", "谢谢啦", "谢谢你", "多谢", "感谢",
        "辛苦了", "辛苦啦", "麻烦了", "麻烦您",
        "抱歉", "不好意思", "打扰了", "对不起",
        "没关系", "没事", "不客气", "不用谢",
        "好的谢谢", "谢谢好的", "收到谢谢",
        "费心了", "劳烦", "有劳了",
    )

    /** 短文本黑名单（模型容易翻车的） */
    private val shortOverride = mapOf(
        "切" to "厌恶", "呵" to "厌恶", "哼" to "生气",
        "呸" to "厌恶", "靠" to "生气", "淦" to "生气",
        "啧" to "无奈", "唉" to "无奈", "哎呀" to "无奈",
        "嗨" to "高兴", "喂" to "平静",
    )

    /** 高兴 vs 兴奋 区分（只有这些词才明显是兴奋） */
    private val excitedHints = setOf(
        "激动", "兴奋", "好激动", "好兴奋", "好耶",
        "爽", "太爽了", "啊啊啊", "太棒了",
    )

    // ============ 初始化 ============

    fun init(context: Context, chatName: String = "") {
        currentChatName = chatName
        Thread {
            MoodTokenizer.load(context)
            MoodModel.load(context)
            if (MoodModel.isLoaded) {
                Log.i("MoodDetector", "Local emotion model loaded (v8)")
            }
        }.start()
    }

    // ============ 主入口 ============

    suspend fun feed(userMessage: String, history: List<String> = emptyList()): MoodInfo {
        feedCount++
        val trimmed = userMessage.trim()

        // ---- 1. 预规则：标点符号 ----
        if (punctuationPattern.matches(trimmed)) {
            val mood = when {
                trimmed.contains("？") && trimmed.contains("！") -> "惊讶"
                trimmed.contains("？") -> "疑惑"
                trimmed.contains("！") -> "惊讶"
                trimmed.contains("。") || trimmed.contains("……") -> "无奈"
                trimmed.contains("～") || trimmed.contains("~") -> "平静"
                else -> "中性"
            }
            return makeResult(mood, "rule_punct")
        }

        // ---- 2. 预规则：表情符号 ----
        for ((emoji, mood) in emojiMap) {
            if (trimmed.contains(emoji)) {
                return makeResult(mood, "rule_emoji")
            }
        }

        // ---- 3. 预规则：网络用语 ----
        val lower = trimmed.lowercase()
        for ((slang, mood) in slangMap) {
            if (lower.contains(slang.lowercase())) {
                return makeResult(mood, "rule_slang")
            }
        }

        // ---- 4. 预规则：礼貌用语 ----
        val trimmedCompact = trimmed.replace(" ", "").replace("　", "")
        if (trimmedCompact in politeSet) {
            return makeResult("中性", "rule_polite")
        }

        // ---- 5. 预规则：短文本黑名单 ----
        if (trimmed in shortOverride) {
            return makeResult(shortOverride[trimmed]!!, "rule_short")
        }

        // ---- 6. ONNX 模型推理 ----
        if (MoodModel.isLoaded) {
            val localLabel = MoodModel.predict(trimmed)
            if (localLabel != null) {
                // 后处理：高兴→兴奋 修正
                val adjusted = postProcess(trimmed, localLabel)
                return makeResult(adjusted, "local")
            }
        }

        // ---- 7. LLM API 兜底 ----
        val label = detectEmotion(trimmed, history)
        if (label.isNotBlank()) {
            return makeResult(label, "api")
        }

        lastMood = null
        lastHint = null
        lastSource = "failed"
        return MoodInfo(null, null)
    }

    // ============ 后处理 ============

    /**
     * 对模型输出的修正。
     * - 高兴→兴奋（如果消息包含明显兴奋词）
     * - 中性→无奈（如果消息包含叹气词）
     */
    private fun postProcess(text: String, modelLabel: String): String {
        val lowered = text.lowercase()

        // 修正：模型常把"开心"类误判，"兴奋"类词修正
        if (modelLabel == "高兴" || modelLabel == "兴奋") {
            for (hint in excitedHints) {
                if (text.contains(hint)) return "兴奋"
            }
        }

        // 修正：短促命令句 → 平静/无奈
        if (modelLabel == "认真" && text.length <= 4) {
            return "中性"
        }

        // 修正：疑惑词
        if ((text.contains("？") || text == "啊" || text == "嗯？") && modelLabel != "疑惑" && modelLabel != "惊讶") {
            return "疑惑"
        }

        return modelLabel
    }

    // ============ 工具方法 ============

    private fun makeResult(mood19: String, source: String): MoodInfo {
        val normalized = moodNormalize[mood19] ?: mood19
        val hint = hintFor(normalized)
        val result = MoodInfo(normalized, hint)
        lastMood = result.mood
        lastHint = result.hint
        lastSource = source
        return result
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
     * LLM API 检测情绪（兜底，包含反讽检测）。
     */
    private suspend fun detectEmotion(userMessage: String, history: List<String>): String {
        val contextText = history.takeLast(4).joinToString("\n")
        val inputText = if (contextText.isNotEmpty()) {
            "[上文]\n$contextText\n[当前]\n$userMessage"
        } else {
            userMessage
        }

        val prompt = buildString {
            appendLine("分析以下对话中说话者的情绪。注意识别反讽/说反话。")
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
            Log.w("MoodDetector", "emotion API failed", e)
            ""
        }
    }
}
