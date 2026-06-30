package com.aftglw.devapi

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object MoodModel {
    private const val MODEL_NAME = "model.onnx"
    private const val VOCAB_PATH = "tokenizer_out/tokenizer.json"
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    var lastError = ""

    // EmotionTalk 7 类: 愤怒 0, 厌恶 1, 害怕 2, 开心 3, 中性 4, 悲伤 5, 惊讶 6
    val labels = listOf("愤怒", "厌恶", "害怕", "开心", "中性", "悲伤", "惊讶")

    private var vocab: Map<String, Long>? = null
    private var extracted = false

    fun isDownloaded(context: Context) = true
    fun getModelFile(context: Context): File? = null

    private fun loadVocab(context: Context): Map<String, Long>? {
        if (vocab != null) return vocab
        return try {
            val json = context.assets.open(VOCAB_PATH).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val model = obj.optJSONObject("model")
            val v = model?.optJSONObject("vocab")
            if (v == null) { lastError = "no_vocab_in_tokenizer"; return null }
            val map = mutableMapOf<String, Long>()
            val keys = v.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = v.getLong(key)
            }
            vocab = map
            lastError = ""
            map
        } catch (e: Exception) { lastError = "vocab:${e.message}"; null }
    }

    fun tokenize(text: String, ctx: Context): Pair<LongArray, LongArray>? {
        val v = loadVocab(ctx) ?: return null
        val ids = LongArray(256) { 0 }; val mask = LongArray(256) { 0 }
        ids[0] = v.getOrDefault("[CLS]", 101L); mask[0] = 1
        var pos = 1
        for (c in text.take(254)) {
            val token = c.toString()
            val id = v[token] ?: v.getOrDefault("[UNK]", 100L)
            if (pos >= 255) break
            ids[pos] = id; mask[pos] = 1; pos++
        }
        if (pos < 256) { ids[pos] = v.getOrDefault("[SEP]", 102L); mask[pos] = 1 }
        return Pair(ids, mask)
    }

    private fun ensureAssets(context: Context): Boolean {
        val modelFile = File(context.filesDir, MODEL_NAME)
        if (modelFile.exists() && extracted) return true
        return try {
            context.assets.open(MODEL_NAME).use { src ->
                FileOutputStream(modelFile).use { dst -> src.copyTo(dst) }
            }
            extracted = true; lastError = ""; true
        } catch (e: Exception) { lastError = "assets:${e.message}"; false }
    }

    fun load(context: Context): Boolean {
        if (session != null) return true
        if (!ensureAssets(context)) return false
        return try {
            session = env.createSession(File(context.filesDir, MODEL_NAME).absolutePath)
            lastError = ""; true
        } catch (e: Exception) { lastError = e.message ?: "unknown"; false }
    }

    fun unload() { session?.close(); session = null; extracted = false; vocab = null }

    var lastConfidence = 0f

    fun classify(inputText: String, context: Context): Int? {
        val s = session ?: run { lastError = "no_session"; return null }
        val (ids, mask) = tokenize(inputText, context) ?: return null
        return try {
            val tt = LongArray(256) { 0 }
            val input = OnnxTensor.createTensor(env, arrayOf(ids))
            val attn = OnnxTensor.createTensor(env, arrayOf(mask))
            val ttIds = OnnxTensor.createTensor(env, arrayOf(tt))
            val r = s.run(mapOf("input_ids" to input, "attention_mask" to attn, "token_type_ids" to ttIds))
            r.use { result ->
                val raw = result.get("logits")
                val tensor = if (raw is java.util.Optional) (raw as java.util.Optional<OnnxTensor>).get() else raw as OnnxTensor
                val scores = tensor.floatBuffer.array()
                val idx = scores.indices.maxByOrNull { scores[it] } ?: return@use null
                val maxScore = scores[idx]
                val sumExp = scores.sumOf { kotlin.math.exp(it.toDouble()) }
                lastConfidence = (kotlin.math.exp(maxScore.toDouble()) / sumExp).toFloat()
                lastError = ""; return@use idx
            }
        } catch (e: Exception) { lastError = "infer:${e.message}"; null }
    }
}
