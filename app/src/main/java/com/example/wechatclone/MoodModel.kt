package com.example.wechatclone

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MoodModel {
    // 替换为你的 ONNX 文件 URL
    // 来源: Erlangshen-Roberta-110M-Sentiment → ONNX q4f16
    // 下载: https://huggingface.co/onnx-community/Erlangshen-Roberta-110M-Sentiment-ONNX/resolve/main/onnx/model_q4f16.onnx
    private const val MODEL_URL = "YOUR_ONNX_URL_HERE"
    private const val MODEL_NAME = "model_q4f16.onnx"

    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()

    // 标签: 0=消极 1=中性 2=积极 (Erlangshen-Sentiment 输出)
    private val labels = arrayOf("negative", "neutral", "positive")

    fun isDownloaded(context: Context): Boolean {
        return File(context.filesDir, MODEL_NAME).exists()
    }

    suspend fun download(context: Context, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            val total = conn.contentLength
            val input = conn.inputStream
            val file = File(context.filesDir, MODEL_NAME)
            FileOutputStream(file).use { output ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) onProgress(downloaded.toFloat() / total)
                }
            }
            input.close()
            conn.disconnect()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun load(context: Context): Boolean {
        if (session != null) return true
        val file = File(context.filesDir, MODEL_NAME)
        if (!file.exists()) return false
        return try {
            session = env.createSession(file.absolutePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun unload() {
        session?.close()
        session = null
    }

    fun classify(text: String): String? {
        val s = session ?: return null
        // 简单 tokenize: 按字符拆成 int ids（与 BERT tokenizer 不完全一致，但够用）
        val inputIds = IntArray(128) { 0 }
        val chars = text.take(126).map { it.code.coerceIn(0, 21127) }
        chars.forEachIndexed { i, c -> inputIds[i] = c }
        inputIds[0] = 101   // [CLS]
        inputIds[chars.size + 1] = 102 // [SEP]

        return try {
            val tensor = OnnxTensor.createTensor(env, Array(1) { inputIds })
            val result = s.run(mapOf("input_ids" to tensor))
            val output = result.get("logits") as OnnxTensor
            val scores = output.floatBuffer.array()
            val idx = scores.indices.maxByOrNull { scores[it] } ?: return null
            labels.getOrNull(idx) ?: "neutral"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
