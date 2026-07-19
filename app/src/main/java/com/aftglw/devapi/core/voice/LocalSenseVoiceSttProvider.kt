package com.aftglw.devapi.core.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 SenseVoice STT Provider：基于 sherpa-onnx 在手机端做离线转写。
 *
 * SenseVoice 是阿里 funasr 开源的多语言 ASR 模型，中文准确率显著优于 whisper-tiny，
 * 支持 中/英/日/韩/粤 五语种。sherpa-onnx 官方原生支持，与 [LocalWhisperSttProvider]
 * 共用同一套 AAR，差别仅在 [OfflineModelConfig.senseVoice] 配置。
 *
 * 模型文件约定：
 *   - model  : filesDir/sensevoice/model.int8.onnx
 *   - tokens : filesDir/sensevoice/tokens.txt
 *
 * 性能预期（sense-voice-zh-en-ja-ko-yue-2024-07-17 int8 ~229MB）：
 *   - 加载耗时：3-8s
 *   - 单次推理：3-10s（比 whisper-tiny 略快，非自回归）
 *   - 内存占用：~250MB
 *
 * 失败场景：
 * - 模型未导入 → isAvailable=false
 * - tokens 缺失 → isAvailable=false
 * - 推理异常 → Failed
 */
class LocalSenseVoiceSttProvider(ctx: Context) : SttProvider {

    override val id: String = "local_sensevoice"

    private val ctx = ctx.applicationContext

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedSignature: String? = null

    private val inferMutex = Mutex()

    private val dir: File
        get() = File(ctx.filesDir, "sensevoice").apply { mkdirs() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        findModelFile() != null && findTokensFile() != null
    }

    override suspend fun transcribe(
        audioPath: String,
        lang: String,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ): SttOutcome = withContext(Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            return@withContext SttOutcome.Failed("音频文件不存在或为空: $audioPath")
        }

        val modelPath = findModelFile()?.absolutePath
            ?: return@withContext SttOutcome.Failed("SenseVoice model 未导入，请导入 model.int8.onnx")
        val tokensPath = findTokensFile()?.absolutePath
            ?: return@withContext SttOutcome.Failed("SenseVoice tokens 未导入，请导入 tokens.txt")

        val signature = "$modelPath|$tokensPath"
        if (recognizer == null || loadedSignature != signature) {
            try {
                // SenseVoice 语言代码：auto / zh / en / ja / ko / yue
                // 我们传 BCP-47（zh-CN），取前两位映射
                val svLang = when (lang.take(2).lowercase()) {
                    "zh" -> "auto"  // 中英混合场景下 auto 比 zh 更稳
                    "en" -> "en"
                    "ja" -> "ja"
                    "ko" -> "ko"
                    "yu" -> "yue"
                    else -> "auto"
                }
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelPath,
                            language = svLang,
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokensPath,
                        modelType = "sense_voice",
                        numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                        debug = false,
                    ),
                )
                Log.i(TAG, "Loading SenseVoice: model=$modelPath, lang=$svLang")
                recognizer?.release()
                recognizer = OfflineRecognizer(config = config)
                loadedSignature = signature
                Log.i(TAG, "SenseVoice loaded")
            } catch (e: Exception) {
                Log.e(TAG, "SenseVoice load failed", e)
                return@withContext SttOutcome.Failed("SenseVoice 加载失败: ${e.message?.take(80)}", e)
            }
        }
        val rec = recognizer ?: return@withContext SttOutcome.Failed("SenseVoice 未初始化")

        try {
            inferMutex.withLock {
                val samples = AudioDecoder.decodeToPcmFloat(audioPath)
                if (samples.isEmpty()) {
                    return@withLock SttOutcome.Failed("音频解码失败: $audioPath")
                }
                Log.d(TAG, "Decoded ${samples.size} samples (${samples.size / 16000f}s), recognizing...")

                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val text = result.text.trim()
                    Log.d(TAG, "Recognition done: '$text'")
                    SttOutcome.Success(text)
                } finally {
                    stream.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SenseVoice 推理异常", e)
            SttOutcome.Failed("SenseVoice 推理异常: ${e.message?.take(60)}", e)
        }
    }

    override fun stop() {
        // 同步推理，无法中途停止
    }

    override fun shutdown() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        loadedSignature = null
    }

    private fun findModelFile(): File? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val selected = prefs.getString("stt_sensevoice_model", "")?.takeIf { it.isNotEmpty() }
        if (selected != null) {
            val f = File(selected)
            if (f.exists() && f.extension.equals("onnx", ignoreCase = true)) return f
        }
        return dir.listFiles { f ->
            f.isFile && f.extension.equals("onnx", ignoreCase = true) &&
                f.name.contains("model", ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
    }

    private fun findTokensFile(): File? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val selected = prefs.getString("stt_sensevoice_tokens", "")?.takeIf { it.isNotEmpty() }
        if (selected != null) {
            val f = File(selected)
            if (f.exists() && f.name.endsWith(".txt", ignoreCase = true)) return f
        }
        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".txt", ignoreCase = true) &&
                f.name.contains("tokens", ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
    }

    companion object {
        private const val TAG = "LocalSenseVoiceSttProvider"

        /**
         * SAF 导入 SenseVoice 模型文件到 filesDir/sensevoice/。
         * 按文件名自动分类：
         * - .onnx：保存为 model，设为 stt_sensevoice_model
         * - tokens*.txt / *.txt（含 tokens）：保存为 tokens，设为 stt_sensevoice_tokens
         */
        fun importFromUri(ctx: Context, uri: Uri): File? {
            return try {
                val fileName = resolveFileName(ctx, uri)?.takeIf { it.isNotBlank() } ?: return null
                val lower = fileName.lowercase()
                val destDir = File(ctx.filesDir, "sensevoice").apply { mkdirs() }
                val dest = File(destDir, fileName)

                when {
                    lower.endsWith(".onnx") -> {
                        copyUriToFile(ctx, uri, dest)
                        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                            .putString("stt_sensevoice_model", dest.absolutePath).apply()
                    }
                    lower.endsWith(".txt") -> {
                        copyUriToFile(ctx, uri, dest)
                        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                            .putString("stt_sensevoice_tokens", dest.absolutePath).apply()
                    }
                    else -> return null
                }
                dest
            } catch (e: Exception) {
                Log.e(TAG, "importFromUri failed", e)
                null
            }
        }

        fun getModelFile(ctx: Context): File? =
            LocalSenseVoiceSttProvider(ctx).findModelFile()

        fun getTokensFile(ctx: Context): File? =
            LocalSenseVoiceSttProvider(ctx).findTokensFile()

        private fun copyUriToFile(ctx: Context, uri: Uri, dest: File) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            } ?: throw java.io.IOException("Cannot open URI: $uri")
        }

        private fun resolveFileName(ctx: Context, uri: Uri): String? {
            return try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment
        }
    }
}
