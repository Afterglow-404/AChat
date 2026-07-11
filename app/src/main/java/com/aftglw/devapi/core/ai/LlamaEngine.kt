package com.aftglw.devapi.core.ai

import android.content.Context
import java.io.File

/**
 * llama.cpp 推理引擎的 Kotlin JNI 桥接。
 *
 * 通过 JNI 调用 libllamawisp.so 中的 C++ 函数，
 * 封装 llama.cpp 的模型加载、推理和资源释放。
 *
 * 使用方式：
 *   val engine = LlamaEngine(modelFile)
 *   if (engine.isLoaded) {
 *       val reply = engine.generate("你好", maxTokens = 512)
 *   }
 *   engine.close()
 */
class LlamaEngine(private val modelPath: String) {

    private var nativeHandle: Long = 0

    /** 模型是否已加载成功 */
    val isLoaded: Boolean
        get() = nativeHandle != 0L && nativeIsLoaded(nativeHandle)

    /** 加载模型文件 */
    fun load(): Boolean {
        if (nativeHandle != 0L) return true
        nativeHandle = nativeLoad(modelPath)
        return nativeHandle != 0L
    }

    /**
     * 执行文本生成。
     *
     * @param prompt 输入提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度 (0-200, 默认 70 对应 0.7)
     * @return 生成的文本
     */
    fun generate(prompt: String, maxTokens: Int = 512, temperature: Int = 70): String {
        if (!isLoaded) return "[模型未加载]"
        return nativeGenerate(nativeHandle, prompt, maxTokens, temperature) ?: "[生成失败]"
    }

    /** 释放模型资源 */
    fun close() {
        if (nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() {
        close()
    }

    // ==================== JNI 本地方法 ====================

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeIsLoaded(handle: Long): Boolean
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Int): String?
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            try {
                System.loadLibrary("llamawisp")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("LlamaEngine", "libllamawisp.so 未加载: ${e.message}")
            }
        }

        /**
         * 查找设备上的模型文件。
         * 搜索顺序：filesDir/models/ → 外部存储 → assets/
         */
        fun findModel(ctx: Context, modelName: String = "qwen2.5-1.5b-instruct-q4_k_m.gguf"): File? {
            // 1. filesDir/models/
            val internal = File(ctx.filesDir, "models/$modelName")
            if (internal.exists()) return internal

            // 2. 外部存储
            try {
                val external = File(ctx.getExternalFilesDir("models"), modelName)
                if (external.exists()) return external
            } catch (_: Exception) {}

            // 3. assets/（首次启动时复制）
            // 模型文件太大不放在 assets 中，建议用户自行下载
            return null
        }

        /**
         * 推荐的 Android 可用 GGUF 模型（需用户自行下载）
         */
        val RECOMMENDED_MODELS = listOf(
            ModelInfo("Qwen2.5-1.5B-Instruct",    "qwen2.5-1.5b-instruct-q4_k_m.gguf",    "~1.1GB", "中文最佳"),
            ModelInfo("Gemma-3-1B-IT",             "gemma-3-1b-it-Q4_K_M.gguf",            "~780MB", "英文好, 128k 上下文"),
            ModelInfo("Llama-3.2-3B-Instruct",     "llama-3.2-3b-instruct-q4_k_m.gguf",    "~2.0GB", "通用能力"),
            ModelInfo("DeepSeek-R1-Distill-Qwen-1.5B", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", "~1.1GB", "推理强"),
        )

        data class ModelInfo(
            val name: String,
            val fileName: String,
            val size: String,
            val note: String
        )
    }
}
