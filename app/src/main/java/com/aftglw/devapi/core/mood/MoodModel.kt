package com.aftglw.devapi.core.mood

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ONNX 本地情绪模型推理引擎。
 *
 * 加载 assets/model_quant.onnx 做前向推理，返回 19 类情绪标签。
 * 当模型加载失败或推理异常时返回 null，由 MoodDetector 走 LLM 兜底。
 */
object MoodModel {
    private var session: ai.onnxruntime.OrtSession? = null
    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var labels: List<String> = emptyList()
    var isLoaded = false
        private set

    /**
     * 从 assets 加载 ONNX 模型 + label_mapping.json。
     * 在 Application.onCreate() 或首次使用前调用。
     */
    fun load(context: Context) {
        try {
            // 1. 复制 ONNX 模型到缓存目录（OrtSession 需要文件路径）
            val modelFile = copyAssetToCache(context, "model_quant.onnx")
                ?: run {
                    Log.w("MoodModel", "model_quant.onnx not found in assets")
                    return
                }

            // 2. 加载标签映射
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
            Log.i("MoodModel", "ONNX model loaded: ${labels.size} labels")
        } catch (e: Exception) {
            Log.e("MoodModel", "Failed to load ONNX model", e)
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
                Log.w("MoodModel", "expected OnnxTensor, got ${output?.javaClass?.simpleName}")
                return null
            }
            val buffer = output.floatBuffer
            val scores = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val predId = scores.indices.maxByOrNull { scores[it] } ?: return null
            labels.getOrNull(predId)
        } catch (e: Exception) {
            Log.w("MoodModel", "ONNX inference failed", e)
            null
        }
    }

    /** 释放 ONNX Runtime 资源 */
    fun close() {
        session?.close()
        session = null
        isLoaded = false
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
            Log.w("MoodModel", "Asset $fileName not found", e)
            null
        }
    }
}
