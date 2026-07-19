package com.aftglw.devapi.core.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
        private const val PREFS = "wechat_settings"
        private const val KEY_MODEL_FILE = "local_model_file"

        init {
            try {
                System.loadLibrary("llamawisp")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("LlamaEngine", "libllamawisp.so 未加载: ${e.message}")
            }
        }

        /**
         * 查找设备上的模型文件。
         *
         * 优先级：
         * 1. 用户在设置中显式选择的模型（local_model_file）
         * 2. filesDir/models/ 下第一个 .gguf 文件
         * 3. 外部存储 models/ 下第一个 .gguf 文件
         *
         * @param ctx 上下文
         * @param modelName 显式指定文件名（覆盖设置）；用于测试或特殊场景
         */
        fun findModel(ctx: Context, modelName: String? = null): File? {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val target = modelName ?: prefs.getString(KEY_MODEL_FILE, null)

            // 1. 显式指定的文件名 → 内部 / 外部
            if (!target.isNullOrBlank()) {
                val internal = File(ctx.filesDir, "models/$target")
                if (internal.exists()) return internal
                try {
                    val external = File(ctx.getExternalFilesDir("models"), target)
                    if (external.exists()) return external
                } catch (_: Exception) {}
            }

            // 2. filesDir/models/ 下第一个 .gguf
            val internalDir = File(ctx.filesDir, "models")
            internalDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
                ?.firstOrNull()?.let { return it }

            // 3. 外部存储 models/ 下第一个 .gguf
            try {
                val externalDir = ctx.getExternalFilesDir("models")
                externalDir?.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
                    ?.firstOrNull()?.let { return it }
            } catch (_: Exception) {}

            return null
        }

        /** 列出设备上所有可用的 GGUF 模型文件（内部 + 外部存储） */
        fun listAvailableModels(ctx: Context): List<File> {
            val result = mutableListOf<File>()
            val internalDir = File(ctx.filesDir, "models")
            internalDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
                ?.let { result.addAll(it) }
            try {
                ctx.getExternalFilesDir("models")?.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
                    ?.let { result.addAll(it) }
            } catch (_: Exception) {}
            return result
        }

        /** 读取用户在设置中选择的活动模型文件名（可能为空） */
        fun getSelectedModelName(ctx: Context): String? {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_FILE, null)
        }

        /** 设置活动模型文件名（写回 wechat_settings） */
        fun setSelectedModelName(ctx: Context, fileName: String?) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (fileName.isNullOrBlank()) prefs.edit().remove(KEY_MODEL_FILE).apply()
            else prefs.edit().putString(KEY_MODEL_FILE, fileName).apply()
        }

        /**
         * 通过 SAF URI 导入模型文件到 filesDir/models/。
         *
         * - 从 URI 解析文件名（优先 DISPLAY_NAME，回退到 URI 末段）
         * - 非空且非 .gguf 扩展名时自动追加 .gguf
         * - 流式复制（不一次性读入内存，支持大文件）
         * - 同名时覆盖
         *
         * @return 导入后的本地文件；失败返回 null
         */
        fun importModelFromUri(ctx: Context, uri: Uri): File? {
            return try {
                val fileName = resolveFileName(ctx, uri)?.takeIf { it.isNotBlank() }
                    ?: "imported_${System.currentTimeMillis()}.gguf"
                val finalName = if (fileName.lowercase().endsWith(".gguf")) fileName
                                else "$fileName.gguf"

                val dir = File(ctx.filesDir, "models").apply { mkdirs() }
                val dest = File(dir, finalName)

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: return null

                dest
            } catch (_: Exception) {
                null
            }
        }

        /** 从 SAF URI 提取文件名（DISPLAY_NAME 列） */
        private fun resolveFileName(ctx: Context, uri: Uri): String? {
            return try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment
        }

        /**
         * 推荐的 Android 可用 GGUF 模型（需用户自行下载）
         */
        val RECOMMENDED_MODELS = listOf(
            ModelInfo("Qwen2.5-1.5B-Instruct",    "qwen2.5-1.5b-instruct-q4_k_m.gguf",    "~1.1GB", "中文最佳", "https://hf.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            ModelInfo("Gemma-3-1B-IT",             "gemma-3-1b-it-Q4_K_M.gguf",            "~780MB", "英文好, 128k 上下文", "https://hf.co/google/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
            ModelInfo("Llama-3.2-3B-Instruct",     "llama-3.2-3b-instruct-q4_k_m.gguf",    "~2.0GB", "通用能力", "https://hf.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            ModelInfo("DeepSeek-R1-Distill-Qwen-1.5B", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", "~1.1GB", "推理强", "https://hf.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/deepseek-r1-distill-qwen-1.5b-Q4_K_M.gguf"),
        )

        data class ModelInfo(
            val name: String,
            val fileName: String,
            val size: String,
            val note: String,
            val downloadUrl: String
        )
    }
}
