package com.aftglw.devapi.core.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 Whisper STT Provider：基于 sherpa-onnx（ONNX Runtime）在手机端做离线转写。
 *
 * 替代旧版 tflite whisper 实现。旧版问题是 tflite 端到端模型把 decoder 内化了，
 * 无法控制 task token，导致默认走 TRANSLATE 任务（中文→英文翻译）。
 *
 * sherpa-onnx 把 encoder/decoder 分离，可以通过 [OfflineWhisperModelConfig.language]
 * 和 [OfflineWhisperModelConfig.task] 强制设为 "zh" + "transcribe"，确保中文转写。
 *
 * 模型文件约定（与 [com.aftglw.devapi.core.ai.LlamaEngine] 一致）：
 *   - encoder: filesDir/whisper/xxx-encoder.int8.onnx
 *   - decoder: filesDir/whisper/xxx-decoder.int8.onnx
 *   - tokens : filesDir/whisper/xxx-tokens.txt
 *
 * 性能预期（whisper-tiny int8，~103MB）：
 *   - 加载耗时：3-8s（ONNX Runtime 初始化）
 *   - 单次推理：5-15s（取决于手机 CPU）
 *   - 内存占用：~150MB
 *
 * 与 [CloudSttProvider] / [RemoteWhisperSttProvider] 对称。
 *
 * 失败场景：
 * - 模型未导入 → isAvailable=false
 * - tokens 缺失 → isAvailable=false
 * - 推理异常 → Failed
 */
class LocalWhisperSttProvider(ctx: Context) : SttProvider {

    override val id: String = "local_whisper"

    private val ctx = ctx.applicationContext

    /** Recognizer 实例（懒加载，避免无配置时也实例化） */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** 已加载的模型签名（避免重复加载） */
    @Volatile
    private var loadedSignature: String? = null

    /** 推理互斥锁（OfflineRecognizer 非线程安全） */
    private val inferMutex = Mutex()

    /** 模型目录：filesDir/whisper/ */
    private val whisperDir: File
        get() = File(ctx.filesDir, "whisper").apply { mkdirs() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        findEncoderFile() != null && findDecoderFile() != null && findTokensFile() != null
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

        val encoderPath = findEncoderFile()?.absolutePath
            ?: return@withContext SttOutcome.Failed("Whisper encoder 未导入，请导入 *-encoder.int8.onnx")
        val decoderPath = findDecoderFile()?.absolutePath
            ?: return@withContext SttOutcome.Failed("Whisper decoder 未导入，请导入 *-decoder.int8.onnx")
        val tokensPath = findTokensFile()?.absolutePath
            ?: return@withContext SttOutcome.Failed("Whisper tokens 未导入，请导入 *-tokens.txt")

        // 模型签名变化时重新加载
        val signature = "$encoderPath|$decoderPath|$tokensPath"
        if (recognizer == null || loadedSignature != signature) {
            try {
                // sherpa-onnx 的 language 用 ISO-639-1 代码（"zh", "en" 等），
                // 我们传入的 lang 是 BCP-47（"zh-CN"），取前两位即可
                val whisperLang = lang.take(2).lowercase().ifEmpty { "zh" }
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = encoderPath,
                            decoder = decoderPath,
                            language = whisperLang,
                            task = "transcribe",  // 强制转写，避免默认 translate 把中文翻成英文
                            tailPaddings = 1000,
                        ),
                        tokens = tokensPath,
                        modelType = "whisper",
                        numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                        debug = false,
                    ),
                )
                Log.i(TAG, "Loading sherpa-onnx whisper: enc=$encoderPath, dec=$decoderPath, lang=$whisperLang, task=transcribe")
                recognizer?.release()
                recognizer = OfflineRecognizer(config = config)
                loadedSignature = signature
                Log.i(TAG, "sherpa-onnx whisper loaded")
            } catch (e: Exception) {
                Log.e(TAG, "sherpa-onnx load failed", e)
                return@withContext SttOutcome.Failed("Whisper 加载失败: ${e.message?.take(80)}", e)
            }
        }
        val rec = recognizer ?: return@withContext SttOutcome.Failed("Whisper 未初始化")

        // 推理（OfflineRecognizer 非线程安全，加锁串行化）
        try {
            inferMutex.withLock {
                // 解码音频为 16kHz mono float[]
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
            Log.e(TAG, "Whisper 推理异常", e)
            SttOutcome.Failed("Whisper 推理异常: ${e.message?.take(60)}", e)
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

    /** 查找 encoder 文件：用户选择 > 目录内首个 *-encoder*.onnx */
    private fun findEncoderFile(): File? = findOnnxFile("encoder", "stt_whisper_encoder")

    /** 查找 decoder 文件：用户选择 > 目录内首个 *-decoder*.onnx */
    private fun findDecoderFile(): File? = findOnnxFile("decoder", "stt_whisper_decoder")

    /** 查找 tokens 文件：用户选择 > 目录内首个 *-tokens.txt */
    private fun findTokensFile(): File? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val selected = prefs.getString("stt_whisper_tokens", "")?.takeIf { it.isNotEmpty() }
        if (selected != null) {
            val f = File(selected)
            if (f.exists() && f.name.endsWith(".txt", ignoreCase = true)) return f
        }
        return whisperDir.listFiles { f ->
            f.isFile && f.name.contains("tokens", ignoreCase = true) &&
                f.name.endsWith(".txt", ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
    }

    /** 通用查找：prefs 指定 > 目录内首个匹配名称的 .onnx */
    private fun findOnnxFile(role: String, prefsKey: String): File? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val selected = prefs.getString(prefsKey, "")?.takeIf { it.isNotEmpty() }
        if (selected != null) {
            val f = File(selected)
            if (f.exists() && f.extension.equals("onnx", ignoreCase = true)) return f
        }
        return whisperDir.listFiles { f ->
            f.isFile && f.extension.equals("onnx", ignoreCase = true) &&
                f.name.contains(role, ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
    }

    companion object {
        private const val TAG = "LocalWhisperSttProvider"

        /**
         * 通过 SAF 导入 Whisper 模型文件到 filesDir/whisper/。
         * 根据文件名自动分类：
         * - 包含 "encoder"：保存为 encoder 模型，设为 stt_whisper_encoder
         * - 包含 "decoder"：保存为 decoder 模型，设为 stt_whisper_decoder
         * - 包含 "tokens" 且为 .txt：保存为 tokens 文件，设为 stt_whisper_tokens
         * 其他扩展名返回 null。
         *
         * 用 64KB buffer 流式拷贝，适配大文件（与 LlamaEngine.importModelFromUri 一致）。
         */
        fun importFromUri(ctx: Context, uri: Uri): File? {
            return try {
                val fileName = resolveFileName(ctx, uri)?.takeIf { it.isNotBlank() }
                    ?: return null
                val lower = fileName.lowercase()
                val dir = File(ctx.filesDir, "whisper").apply { mkdirs() }
                val dest = File(dir, fileName)

                when {
                    lower.endsWith(".onnx") && lower.contains("encoder") -> {
                        copyUriToFile(ctx, uri, dest)
                        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                            .putString("stt_whisper_encoder", dest.absolutePath).apply()
                    }
                    lower.endsWith(".onnx") && lower.contains("decoder") -> {
                        copyUriToFile(ctx, uri, dest)
                        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                            .putString("stt_whisper_decoder", dest.absolutePath).apply()
                    }
                    lower.endsWith(".txt") && lower.contains("tokens") -> {
                        copyUriToFile(ctx, uri, dest)
                        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                            .putString("stt_whisper_tokens", dest.absolutePath).apply()
                    }
                    else -> return null
                }
                dest
            } catch (e: Exception) {
                Log.e(TAG, "importFromUri failed", e)
                null
            }
        }

        /** 获取当前已导入的 encoder 文件（如有） */
        fun getEncoderFile(ctx: Context): File? =
            LocalWhisperSttProvider(ctx).findEncoderFile()

        /** 获取当前已导入的 decoder 文件（如有） */
        fun getDecoderFile(ctx: Context): File? =
            LocalWhisperSttProvider(ctx).findDecoderFile()

        /** 获取 tokens 文件（如有） */
        fun getTokensFile(ctx: Context): File? =
            LocalWhisperSttProvider(ctx).findTokensFile()

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
