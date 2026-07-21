package com.aftglw.devapi.core.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * 远程 Qwen3-TTS Provider：调用电脑端的 Wisp/Qwen3-TTS 适配服务。
 *
 * 适配服务协议：
 * - GET  /healthz -> 2xx
 * - GET  /speakers -> ["Vivian", "Ryan", ...]
 * - POST /tts -> audio/wav 或 audio/mpeg
 *   {"text":"你好", "language":"Chinese", "speaker":"Vivian", "instruct":"温柔地说"}
 */
class RemoteQwen3TtsProvider(ctx: Context) : TtsProvider {

    override val id: String = "qwen3_tts"

    private val ctx = ctx.applicationContext
    private val player = VoicePlayer(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastHealthCheckMs: Long = 0L
    @Volatile private var lastHealthResult: Boolean = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val url = getServiceUrl()
        if (url.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckMs < 300_000L) return@withContext lastHealthResult
        val ok = try {
            withTimeoutOrNull(3000L) {
                HttpClient.client.newCall(Request.Builder().url("${url.trimEnd('/')}/qwen3/healthz").get().build()).execute().use { it.isSuccessful }
            } ?: false
        } catch (_: Exception) { false }
        lastHealthCheckMs = now
        lastHealthResult = ok
        ok
    }

    override suspend fun speak(
        text: String,
        voiceId: String,
        utteranceId: String,
        language: String,
        instruction: String,
        onStart: () -> Unit,
        onDone: (Boolean) -> Unit
    ): TtsOutcome {
        if (text.isBlank()) return TtsOutcome.Failed("空文本")
        val url = getServiceUrl()
        if (url.isEmpty()) return TtsOutcome.Failed("Qwen3-TTS 服务地址未配置")

        val languageName = normalizeLanguage(language)
        val speaker = voiceId.ifBlank { "Vivian" }
        val hash = sha1("$speaker|$languageName|$instruction|$text")
        val cacheFile = File(ctx.cacheDir, "tts/qwen3_$hash.wav").apply { parentFile?.mkdirs() }
        val audioPath = if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.absolutePath
        } else {
            val requestBody = JSONObject().apply {
                put("text", text)
                put("language", languageName)
                put("speaker", speaker)
                if (instruction.isNotBlank()) put("instruct", instruction)
                put("response_format", "wav")
            }.toString().toRequestBody(HttpClient.JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("${url.trimEnd('/')}/qwen3/tts")
                .post(requestBody)
                .build()
            try {
                withTimeoutOrNull(60_000L) {
                    HttpClient.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val error = response.body?.string()?.take(160) ?: ""
                            throw java.io.IOException("Qwen3-TTS HTTP ${response.code}: $error")
                        }
                        val bytes = response.body?.bytes() ?: throw java.io.IOException("Qwen3-TTS 空响应")
                        cacheFile.outputStream().use { it.write(bytes) }
                    }
                    cacheFile.absolutePath
                } ?: return TtsOutcome.Failed("Qwen3-TTS 推理超时（60s）")
            } catch (e: java.io.IOException) {
                return TtsOutcome.Failed("Qwen3-TTS 请求失败: ${e.message?.take(80)}", e)
            } catch (e: Exception) {
                return TtsOutcome.Failed("Qwen3-TTS 请求异常: ${e.message?.take(80)}", e)
            }
        }

        return suspendCancellableCoroutine { cont ->
            var started = false
            var resumed = false
            fun finish(outcome: TtsOutcome) {
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(outcome)
                }
            }
            try {
                player.play(
                    path = audioPath,
                    onStart = {
                        started = true
                        mainHandler.post { onStart() }
                    },
                    onComplete = {
                        mainHandler.post { onDone(started) }
                        finish(if (started) TtsOutcome.Success else TtsOutcome.Failed("Qwen3-TTS 播放未启动"))
                    }
                )
            } catch (e: Exception) {
                finish(TtsOutcome.Failed("Qwen3-TTS 播放异常", e))
            }
            cont.invokeOnCancellation { try { player.stop() } catch (_: Exception) {} }
        }
    }

    override fun stop() = player.stop()

    override fun shutdown() = player.stop()

    private fun getServiceUrl(): String = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        .getString("tts_qwen3_url", "")?.trim() ?: ""

    private fun normalizeLanguage(language: String): String = when (language.trim().lowercase()) {
        "zh", "chinese" -> "Chinese"
        "en", "english" -> "English"
        "ja", "japanese" -> "Japanese"
        "ko", "korean" -> "Korean"
        "de", "german" -> "German"
        "fr", "french" -> "French"
        "ru", "russian" -> "Russian"
        "pt", "portuguese" -> "Portuguese"
        "es", "spanish" -> "Spanish"
        "it", "italian" -> "Italian"
        "auto" -> "Auto"
        else -> "Chinese"
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
