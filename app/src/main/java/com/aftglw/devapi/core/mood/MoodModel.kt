package com.aftglw.devapi.core.mood

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ONNX 本地情绪模型推理引擎。
 *
 * 加载 ONNX 模型做前向推理，返回 19 类情绪标签。
 * 当模型加载失败或推理异常时返回 null，由 MoodDetector 走 LLM 兜底。
 *
 * 模型加载优先级（自上而下，找到即用）：
 * 1. filesDir/models/ 下的 .onnx 文件（SAF 导入位置）
 * 2. getExternalFilesDir("models")/ 下的 .onnx 文件
 * 3. assets/model_quant.onnx（仅 debug 构建包含；release 已剥离以减小 APK 体积）
 *
 * label_mapping.json / bert_vocab.txt / vocab.txt 仍从 assets 读取（体积小、tokenizer 必需）。
 */
object MoodModel {
    private const val TAG = "MoodModel"
    private const val PREFS = "wechat_mood_model"
    private const val KEY_MODEL_FILE = "mood_model_file"

    private var session: ai.onnxruntime.OrtSession? = null
    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var labels: List<String> = emptyList()
    var isLoaded = false
        private set

    /**
     * 加载 ONNX 模型 + label_mapping.json。
     * 在 Application.onCreate() 或首次使用前调用。
     * 找不到模型文件时静默返回（isLoaded 保持 false，调用方走 LLM 兜底）。
     */
    fun load(context: Context) {
        try {
            // 1. 定位模型文件：外部导入 → assets 兜底
            val modelFile = findModelFile(context)
                ?: copyAssetToCache(context, "model_quant.onnx")
                ?: run {
                    Log.w(TAG, "ONNX model not found; please import via 设置 → 情绪模型")
                    return
                }

            // 2. 加载标签映射（始终从 assets 读取，体积小）
            val labelFile = copyAssetToCache(context, "label_mapping.json")
            if (labelFile != null) {
                val json = labelFile.readText()
                val parsed = org.json.JSONObject(json)
                val id2label = parsed.getJSONObject("id2label")
                labels = (0 until id2label.length()).map { id2label.getString(it.toString()) }
            } else {
                // 默认 19 类（与训练一致）
                labels = listOf(
                    "高兴", "兴奋", "平静", "惊讶", "厌恶",
                    "哭泣", "害怕", "生气", "害羞", "紧张",
                    "担心", "无奈", "疑惑", "慌张", "心动",
                    "调皮", "认真", "自信", "难为情",
                )
            }

            // 3. 创建 ONNX Runtime session
            env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val options = ai.onnxruntime.OrtSession.SessionOptions()
            session = env!!.createSession(modelFile.absolutePath, options)
            isLoaded = true
            Log.i(TAG, "ONNX model loaded from ${modelFile.absolutePath}: ${labels.size} labels")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            isLoaded = false
        }
    }

    /**
     * 推理：输入文本 → 情绪标签。
     * 返回 null 表示推理失败（走 LLM 兜底）。
     */
    fun predict(text: String): String? {
        if (!isLoaded || session == null || env == null) return null
        return try {
            val tokenizer = com.aftglw.devapi.core.mood.MoodTokenizer
            val (inputIds, attentionMask) = tokenizer.tokenize(text)

            val inputName = session!!.inputNames.iterator().next()
            val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(env!!, inputIds)
            val maskTensor = ai.onnxruntime.OnnxTensor.createTensor(env!!, attentionMask)

            val result = session!!.run(
                mapOf(
                    "input_ids" to inputTensor,
                    "attention_mask" to maskTensor
                )
            )

            // ONNX Runtime Android API
            val output = result.iterator().next().value
            if (output !is ai.onnxruntime.OnnxTensor) {
                Log.w(TAG, "expected OnnxTensor, got ${output?.javaClass?.simpleName}")
                return null
            }
            val buffer = output.floatBuffer
            val scores = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val predId = scores.indices.maxByOrNull { scores[it] } ?: return null
            labels.getOrNull(predId)
        } catch (e: Exception) {
            Log.w(TAG, "ONNX inference failed", e)
            null
        }
    }

    /** 释放 ONNX Runtime 资源 */
    fun close() {
        session?.close()
        session = null
        isLoaded = false
    }

    // ==================== 模型文件管理 ====================

    /**
     * 查找设备上的 ONNX 模型文件。
     *
     * 优先级：
     * 1. 用户在设置中显式选择的模型（mood_model_file）
     * 2. filesDir/models/ 下第一个 .onnx 文件
     * 3. getExternalFilesDir("models")/ 下第一个 .onnx 文件
     *
     * @param ctx 上下文
     * @param modelName 显式指定文件名（覆盖设置）；用于测试或特殊场景
     */
    fun findModelFile(ctx: Context, modelName: String? = null): File? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = modelName ?: prefs.getString(KEY_MODEL_FILE, null)

        // 1. 显式指定的文件名 → 内部 / 外部
        if (!target.isNullOrBlank()) {
            val internal = File(ctx.filesDir, "models/$target")
            if (internal.exists() && internal.extension.equals("onnx", ignoreCase = true)) return internal
            try {
                val external = File(ctx.getExternalFilesDir("models"), target)
                if (external.exists() && external.extension.equals("onnx", ignoreCase = true)) return external
            } catch (_: Exception) {}
        }

        // 2. filesDir/models/ 下第一个 .onnx（model_quant.onnx 优先）
        val internalDir = File(ctx.filesDir, "models")
        internalDir.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
            ?.sortedByDescending { it.name.contains("quant", ignoreCase = true) }
            ?.firstOrNull()?.let { return it }

        // 3. 外部存储 models/ 下第一个 .onnx
        try {
            val externalDir = ctx.getExternalFilesDir("models")
            externalDir?.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
                ?.sortedByDescending { it.name.contains("quant", ignoreCase = true) }
                ?.firstOrNull()?.let { return it }
        } catch (_: Exception) {}

        return null
    }

    /** 列出设备上所有可用的 ONNX 模型文件（内部 + 外部存储） */
    fun listAvailableModels(ctx: Context): List<File> {
        val result = mutableListOf<File>()
        val internalDir = File(ctx.filesDir, "models")
        internalDir.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
            ?.let { result.addAll(it) }
        try {
            ctx.getExternalFilesDir("models")?.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
                ?.let { result.addAll(it) }
        } catch (_: Exception) {}
        return result
    }

    /** 读取用户在设置中选择的活动模型文件名（可能为空） */
    fun getSelectedModelName(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_FILE, null)
    }

    /** 设置活动模型文件名（null 清除选择，自动取第一个） */
    fun setSelectedModelName(ctx: Context, fileName: String?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (fileName.isNullOrBlank()) prefs.edit().remove(KEY_MODEL_FILE).apply()
        else prefs.edit().putString(KEY_MODEL_FILE, fileName).apply()
    }

    /**
     * 通过 SAF URI 导入 ONNX 模型文件到 filesDir/models/。
     *
     * - 从 URI 解析文件名（优先 DISPLAY_NAME，回退到 URI 末段）
     * - 流式复制（64KB buffer，支持大文件）
     * - 同名时覆盖
     *
     * @return 导入后的本地文件；失败返回 null
     */
    fun importModelFromUri(ctx: Context, uri: Uri): File? {
        return try {
            val fileName = resolveFileName(ctx, uri)?.takeIf { it.isNotBlank() }
                ?: "mood_model_${System.currentTimeMillis()}.onnx"
            val finalName = if (fileName.lowercase().endsWith(".onnx")) fileName
                            else "$fileName.onnx"

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

    private fun copyAssetToCache(context: Context, fileName: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            if (!cacheFile.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.w(TAG, "Asset $fileName not found", e)
            null
        }
    }
}
