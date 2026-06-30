package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage

/**
 * MNN 本地 LLM 推理引擎（框架代码，MNN 库和模型就位后激活）
 *
 * MNN 依赖需要在 build.gradle 中添加:
 *   implementation("com.alibaba.android:mnn_kit:2.9.0")
 * 或通过 CMake 链接 libMNN.so
 *
 * 模型文件放置在 assets/ 下:
 *   qwen_q4.onnx     → Qwen3-0.6B MNN 格式 ~380MB
 *   qwen_vocab.json  → tiktoken 词表
 */
class LocalAiService(private val ctx: Context) : AiService {

    private var engine: MnnEngine? = null
    private var tokenizer: QwenTokenizer? = null
    private val modeSelector = ThinkingModeSelector()

    private fun ensureModel(): Boolean {
        if (engine != null) return true
        try {
            val modelPath = "${ctx.filesDir}/qwen_q4.onnx"
            // 首次使用时从 assets 复制模型文件（后续替换为直接读路径）
            if (!java.io.File(modelPath).exists()) {
                ctx.assets.open("qwen_q4.onnx").use { src ->
                    java.io.FileOutputStream(modelPath).use { dst -> src.copyTo(dst) }
                }
            }
            engine = MnnEngine(modelPath)
            tokenizer = QwenTokenizer(ctx)
            return true
        } catch (_: Exception) {
            // 模型文件不存在，本地模式不可用
            return false
        }
    }

    override fun sendMessage(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String
    ): String? {
        if (!ensureModel() || tokenizer == null || engine == null) return null

        val tok = tokenizer!!
        val eng = engine!!

        // 思考模式自动选择
        val mode = modeSelector.select(history, userMessage)
        val maxTokens = when (mode) {
            ThinkingMode.NO_THINKING -> 128
            ThinkingMode.THINKING -> 512
            ThinkingMode.DEEP_THINKING -> 1024
        }

        // 构建 Qwen 格式 prompt
        val prompt = buildString {
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

        val inputIds = tok.encode(prompt)
        val reply = eng.generate(inputIds, maxTokens, mode)
        return reply.ifEmpty { null }
    }

    fun unload() {
        engine?.close()
        engine = null
        tokenizer = null
    }
}

/**
 * MNN 推理引擎，封装 session 生命周期和文本生成循环
 */
class MnnEngine(private val modelPath: String) {
    // TODO: 实际加载 MNN Interpreter
    // val interpreter = MNN.Interpreter(InterpreterConfig(modelPath))

    fun generate(inputIds: IntArray, maxTokens: Int, mode: ThinkingMode): String {
        // TODO: MNN 推理循环
        // 1. 构造 input tensor (1, seq_len)
        // 2. 循环: interpreter.run() → 从输出 logits 采样 → decode → 拼接
        // 3. 检测 <|im_end|> 或 <|endoftext|> 停止
        // 4. 提取 <think>...</think> 中的思考内容（根据 mode 处理）

        return "[本地模型未加载]"
    }

    fun close() {
        // TODO: interpreter.close()
    }
}

/**
 * 思考模式选择器，基于情绪 + 好感度 + 消息类型自动决策
 */
class ThinkingModeSelector {
    fun select(history: List<ChatMessage>, userMessage: String): ThinkingMode {
        // TODO: 接入 MoodDetector + AffinityManager
        // 开心/中性 → NO_THINKING（快）
        // 悲伤/害怕 → THINKING（走心）
        // 代码/数学 → DEEP_THINKING（深度推理）
        // 好感度越高 → 允许越深的 thinking budget

        return when {
            userMessage.contains("```") || userMessage.contains("代码") ->
                ThinkingMode.DEEP_THINKING
            userMessage.any { it in "悲伤难过害怕" } ->
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

/**
 * Qwen3 tiktoken/BPE 分词器
 * 从 assets/qwen_vocab.json 加载词表
 */
class QwenTokenizer(private val ctx: Context) {
    private val vocab = mutableMapOf<ByteArray, Int>()
    private val idToToken = mutableMapOf<Int, ByteArray>()

    init {
        // TODO: 从 assets/qwen_vocab.json 加载完整 tiktoken 词表
        // 格式: {"vocab": {"token_str": 12345, ...}}
        // 对于 Qwen3-0.6B, 词表大小约 151936
    }

    fun encode(text: String): IntArray {
        // TODO: BPE 编码
        // 1. UTF-8 encode text → bytes
        // 2. BPE merge → token IDs
        // 简化返回
        return intArrayOf(1) + text.take(512).map { (it.code % 100000 + 100).toInt() }.toIntArray()
    }

    fun decode(tokenIds: IntArray): String {
        // TODO: BPE decode
        return tokenIds.filter { it > 10 }.map { Char(it - 100).toString() }.joinToString("")
    }
}
