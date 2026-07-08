package com.aftglw.devapi

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream

/**
 * 7 类中文情绪分类器（基于 Chinese_sentiment 微调，83% 准确率）
 *
 * 模型: chinese-roberta-wwm-ext 微调 → ONNX 导出
 * 输入: input_ids + attention_mask (max_len=128)
 * 输出: 7 类情绪 logits
 */
object MoodModel {
    private const val MODEL_NAME = "model_quant.onnx"
    private const val VOCAB_FILE = "bert_vocab.txt"
    private const val MAX_LEN = 128
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    var lastError = ""

    // 7 类情绪（中文情感数据集微调，83% accuracy）
    val labels = listOf(
        "开心", "悲伤", "愤怒", "害怕",
        "惊讶", "厌恶", "中性",
    )

    fun isDownloaded(context: Context) = true
    fun getModelFile(context: Context): File? = null

    private var vocab: Map<String, Int>? = null
    private var extracted = false

    /** 加载 bert-base-chinese 词表 */
    private fun loadVocab(context: Context): Map<String, Int>? {
        if (vocab != null) return vocab
        return try {
            val lines = context.assets.open(VOCAB_FILE).bufferedReader().readLines()
            val map = mutableMapOf<String, Int>()
            lines.forEachIndexed { i, word -> map[word.trim()] = i }
            vocab = map
            lastError = ""
            map
        } catch (e: Exception) { lastError = "vocab:${e.message}"; null }
    }

    /** 分词：character-level（中文 BERT 字符级映射） */
    fun tokenize(text: String, ctx: Context): Pair<LongArray, LongArray>? {
        val v = loadVocab(ctx) ?: return null
        val ids = LongArray(MAX_LEN) { 0 }
        val mask = LongArray(MAX_LEN) { 0 }
        ids[0] = v.getOrDefault("[CLS]", 101).toLong()
        mask[0] = 1
        var pos = 1
        // 逐字符映射
        for (c in text.take(MAX_LEN - 2)) {
            if (pos >= MAX_LEN - 1) break
            val token = c.toString()
            // 处理空格：BERT 使用 [UNK] 代替连续空格
            val id = if (c == ' ') v.getOrDefault("[UNK]", 100)
                     else v[token] ?: v.getOrDefault("[UNK]", 100)
            ids[pos] = id.toLong()
            mask[pos] = 1
            pos++
        }
        if (pos < MAX_LEN) {
            ids[pos] = v.getOrDefault("[SEP]", 102).toLong()
            mask[pos] = 1
        }
        return Pair(ids, mask)
    }

    private fun ensureAssets(context: Context): Boolean {
        val modelFile = File(context.filesDir, MODEL_NAME)
        if (modelFile.exists() && extracted) return true
        return try {
            context.assets.open(MODEL_NAME).use { src ->
                FileOutputStream(modelFile).use { dst -> src.copyTo(dst) }
            }
            extracted = true; lastError = ""
            true
        } catch (e: Exception) { lastError = "assets:${e.message}"; false }
    }

    fun load(context: Context): Boolean {
        if (session != null) return true
        if (!ensureAssets(context)) return false
        return try {
            session = env.createSession(File(context.filesDir, MODEL_NAME).absolutePath)
            lastError = ""
            true
        } catch (e: Exception) { lastError = e.message ?: "unknown"; false }
    }

    fun unload() { session?.close(); session = null; extracted = false; vocab = null }

    var lastConfidence = 0f

    fun classify(inputText: String, context: Context): Int? {
        val s = session ?: run { lastError = "no_session"; return null }
        val (ids, mask) = tokenize(inputText, context) ?: return null
        return try {
            val input = OnnxTensor.createTensor(env, arrayOf(ids))
            val attn = OnnxTensor.createTensor(env, arrayOf(mask))
            val r = s.run(mapOf("input_ids" to input, "attention_mask" to attn))
            r.use { result ->
                val raw = result.get("logits")
                @Suppress("CAST_NEVER_SUCCEEDS")
                val tensor = if (raw is java.util.Optional<*>) (raw.get() as OnnxTensor) else raw as OnnxTensor
                val scores = tensor.floatBuffer.array()
                val idx = scores.indices.maxByOrNull { scores[it] } ?: return@use null
                val maxScore = scores[idx]
                val sumExp = scores.sumOf { kotlin.math.exp(it.toDouble()) }
                lastConfidence = (kotlin.math.exp(maxScore.toDouble()) / sumExp).toFloat()
                lastError = ""
                idx
            }
        } catch (e: Exception) { lastError = e.message ?: "unknown"; null }
    }
}
