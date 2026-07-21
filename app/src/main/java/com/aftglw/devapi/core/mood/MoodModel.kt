package com.aftglw.devapi.core.mood

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Local ONNX mood model with an optional LLM fallback in the caller. */
object MoodModel {
    private const val TAG = "MoodModel"
    private const val PREFS = "wechat_mood_model"
    private const val KEY_MODEL_FILE = "mood_model_file"

    private var session: ai.onnxruntime.OrtSession? = null
    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var labels: List<String> = emptyList()

    var isLoaded = false
        private set

    fun load(context: Context) {
        try {
            val modelFile = findModelFile(context)
                ?: copyAssetToCache(context, "model_quant.onnx")
                ?: return

            val labelFile = copyAssetToCache(context, "label_mapping.json")
            labels = if (labelFile != null) {
                val id2label = org.json.JSONObject(labelFile.readText()).getJSONObject("id2label")
                (0 until id2label.length()).map { id2label.getString(it.toString()) }
            } else {
                listOf(
                    "happy", "excited", "calm", "surprised", "disgusted",
                    "sad", "afraid", "angry", "shy", "nervous",
                    "worried", "helpless", "confused", "panicked", "moved",
                    "playful", "serious", "confident", "difficult"
                )
            }

            env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val options = ai.onnxruntime.OrtSession.SessionOptions()
            session = env!!.createSession(modelFile.absolutePath, options)
            isLoaded = true
            Log.i(TAG, "ONNX model loaded from ${modelFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            isLoaded = false
        }
    }

    fun predict(text: String): String? {
        if (!isLoaded || session == null || env == null) return null
        return try {
            val (inputIds, attentionMask) = MoodTokenizer.tokenize(text)
            val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(env!!, inputIds)
            val maskTensor = ai.onnxruntime.OnnxTensor.createTensor(env!!, attentionMask)
            session!!.run(mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor)).use { result ->
                val value = result.iterator().next().value
                if (value !is ai.onnxruntime.OnnxTensor) return null
                val buffer = value.floatBuffer
                val scores = FloatArray(buffer.remaining()).also { buffer.get(it) }
                val predId = scores.indices.maxByOrNull { scores[it] } ?: return null
                labels.getOrNull(predId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ONNX inference failed", e)
            null
        }
    }

    fun close() {
        session?.close()
        session = null
        env = null
        isLoaded = false
    }

    fun findModelFile(ctx: Context, modelName: String? = null): File? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = modelName ?: prefs.getString(KEY_MODEL_FILE, null)
        if (!target.isNullOrBlank()) {
            val internal = File(ctx.filesDir, "models/$target")
            if (internal.isFile && internal.extension.equals("onnx", true)) return internal
            try {
                val external = File(ctx.getExternalFilesDir("models"), target)
                if (external.isFile && external.extension.equals("onnx", true)) return external
            } catch (e: Exception) {
                Log.w(TAG, "External model lookup failed", e)
            }
        }

        fun choose(dir: File?): File? = dir?.listFiles { file ->
            file.isFile && file.extension.equals("onnx", true)
        }?.sortedByDescending { it.name.contains("quant", true) }?.firstOrNull()

        choose(File(ctx.filesDir, "models"))?.let { return it }
        return try {
            choose(ctx.getExternalFilesDir("models"))
        } catch (e: Exception) {
            Log.w(TAG, "External model scan failed", e)
            null
        }
    }

    fun listAvailableModels(ctx: Context): List<File> {
        val result = mutableListOf<File>()
        fun addFrom(dir: File?) {
            dir?.listFiles { file -> file.isFile && file.extension.equals("onnx", true) }
                ?.let(result::addAll)
        }
        addFrom(File(ctx.filesDir, "models"))
        try {
            addFrom(ctx.getExternalFilesDir("models"))
        } catch (e: Exception) {
            Log.w(TAG, "External model list failed", e)
        }
        return result.distinctBy { it.absolutePath }
    }

    fun getSelectedModelName(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL_FILE, null)

    fun setSelectedModelName(ctx: Context, fileName: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (fileName.isNullOrBlank()) remove(KEY_MODEL_FILE) else putString(KEY_MODEL_FILE, fileName)
            apply()
        }
    }

    fun importModelFromUri(ctx: Context, uri: Uri): File? {
        return try {
            val fileName = (resolveFileName(ctx, uri)?.takeIf { it.isNotBlank() }
                ?: "mood_model_${System.currentTimeMillis()}.onnx")
            val finalName = if (fileName.endsWith(".onnx", true)) fileName else "$fileName.onnx"
            val dest = File(ctx.filesDir, "models/$finalName").also { it.parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
            dest
        } catch (e: Exception) {
            Log.w(TAG, "Model import failed", e)
            null
        }
    }

    private fun resolveFileName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun copyAssetToCache(context: Context, fileName: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            if (!cacheFile.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.w(TAG, "Asset $fileName not found", e)
            null
        }
    }
}
