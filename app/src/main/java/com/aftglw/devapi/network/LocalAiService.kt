package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.core.ai.LlamaEngine
import com.aftglw.devapi.core.mood.AffinityManager
import com.aftglw.devapi.core.mood.MoodDetector
import com.aftglw.devapi.model.ChatMessage
import java.io.File

/**
 * llama.cpp 本地 LLM 推理引擎。
 *
 * 替代原有的 MNN 方案，使用 GGUF 格式模型。
 * 需用户自行下载模型文件放置到 filesDir/models/ 目录。
 *
 * 支持的模型（推荐）：
 * - Qwen2.5-1.5B-Instruct（中文最佳，~1.1GB）
 * - Gemma-3-1B-IT（英文好，128k上下文，~780MB）
 */
class LocalAiService(private val ctx: Context) : AiService {

    private var engine: LlamaEngine? = null
    private val modeSelector = ThinkingModeSelector()

    private fun ensureModel(): Boolean {
        if (engine?.isLoaded == true) return true

        val modelFile = LlamaEngine.findModel(ctx) ?: return false
        val eng = LlamaEngine(modelFile.absolutePath)
        if (!eng.load()) return false
        engine = eng
        return true
    }

    override fun sendMessage(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        onError: ((String) -> Unit)?
    ): String? {
        if (!ensureModel()) return null

        val eng = engine ?: return null

        // 思考模式自动选择
        val mode = modeSelector.select(history, userMessage)
        val maxTokens = when (mode) {
            ThinkingMode.NO_THINKING -> 128
            ThinkingMode.THINKING -> 512
            ThinkingMode.DEEP_THINKING -> 1024
        }

        // 构建 Qwen/ChatML 格式 prompt
        val prompt = buildPrompt(history, userMessage, systemPrompt)

        return try {
            eng.generate(prompt, maxTokens, temperature = 70)
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "本地推理失败")
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: ((String) -> Unit)?
    ) {
        // 本地模型暂不支持流式，退化为非流式
        val reply = sendMessage(history, userMessage, systemPrompt, onError)
        if (reply != null) {
            onChunk(reply)
            onDone(reply)
        } else {
            onDone("")
        }
    }

    private fun buildPrompt(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String
    ): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
            }
            val msgs = history.takeLast(8)
            for (msg in msgs) {
                val role = if (msg.role == "user") "user" else "assistant"
                append("<|im_start|>$role\n${msg.content}<|im_end|>\n")
            }
            append("<|im_start|>user\n$userMessage<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    fun unload() {
        engine?.close()
        engine = null
    }

    /** 检查设备上是否有可用的本地模型 */
    fun hasModel(): Boolean {
        return LlamaEngine.findModel(ctx) != null
    }

    /** 获取可用模型信息 */
    fun getAvailableModels(): List<File> {
        val dir = File(ctx.filesDir, "models")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList()
    }
}

/**
 * 思考模式选择器，基于情绪 + 好感度 + 消息类型自动决策。
 * 与 MNN 时代的思路保持一致。
 */
class ThinkingModeSelector {
    fun select(history: List<ChatMessage>, userMessage: String): ThinkingMode {
        // 接线 MoodDetector + AffinityManager
        val lastMood = MoodDetector.lastMood
        return when {
            userMessage.contains("```") || userMessage.contains("代码") || userMessage.contains("数学") ->
                ThinkingMode.DEEP_THINKING
            lastMood in listOf("悲伤", "害怕", "愤怒") ->
                ThinkingMode.THINKING
            userMessage.length > 200 ->
                ThinkingMode.THINKING
            else -> ThinkingMode.NO_THINKING
        }
    }
}

enum class ThinkingMode {
    NO_THINKING,    // 非思考模式（快速回复）
    THINKING,       // 思考模式（日常深度）
    DEEP_THINKING   // 深度推理（复杂问题）
}
