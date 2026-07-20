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
 * 远程 GPT-SoVITS TTS Provider：调用 PC 端运行的 GPT-SoVITS 推理服务。
 *
 * 预期 PC 端 API（基于 GPT-SoVITS 官方 api.py 扩展的薄路由层）：
 *
 * GET  /speakers              → ["钦灵","风雪", ...]（可用音色列表）
 * GET  /healthz               → "ok"（探活）
 * POST /tts                   → 合成语音
 *      请求体 JSON:
 *        {
 *          "text":   "你好",
 *          "voice":  "钦灵",          // 与 /speakers 列表对应
 *          "ref_audio_path": "/...",  // 可选：zero-shot 模式参考音频（PC 端绝对路径）
 *          "ref_text":        "..."   // 可选：参考音频对应文本
 *        }
 *      响应体：audio/mpeg 二进制流
 *
 * 失败场景：
 * - PC 服务未启动 / 网络不通 → isAvailable=false
 * - 角色音色不存在 → 400 Bad Request
 * - 推理超时（>30s）→ Failed
 *
 * 缓存：与 [CloudTtsService] 一致，按 (voice|text) SHA-1 哈希命名，存于 cacheDir/tts/。
 *
 * 配置项（wechat_settings）：
 * - tts_gptsovits_url: PC 服务地址，例如 http://192.168.1.10:9880
 */
class RemoteGptSoVitsTtsProvider(ctx: Context) : TtsProvider {

    override val id: String = "gpt_sovits"

    private val ctx = ctx.applicationContext
    private val player = VoicePlayer(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 探活结果缓存（避免每次 speak 都 ping），5 分钟有效 */
    @Volatile private var lastHealthCheckMs: Long = 0L
    @Volatile private var lastHealthResult: Boolean = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val url = getServiceUrl()
        if (url.isEmpty()) return@withContext false

        // 5 分钟内缓存
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckMs < 300_000L) return@withContext lastHealthResult

        val ok = try {
            withTimeoutOrNull(2000L) {
                val req = Request.Builder().url("${url.trimEnd('/')}/healthz").get().build()
                val resp = HttpClient.client.newCall(req).execute()
                val success = resp.isSuccessful
                resp.close()
                success
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
        onStart: () -> Unit,
        onDone: (Boolean) -> Unit
    ): TtsOutcome {
        if (text.isBlank()) return TtsOutcome.Failed("空文本")
        val url = getServiceUrl()
        if (url.isEmpty()) return TtsOutcome.Failed("GPT-SoVITS 服务地址未配置")

        // 1. 命中缓存直接播放
        val cacheDir = File(ctx.cacheDir, "tts").apply { mkdirs() }
        val textLanguage = language.ifBlank { "zh" }
        val hash = sha1("$voiceId|$textLanguage|$text")
        // PC 端 GPT-SoVITS api.py 默认返回 wav，bridge 透传 wav
        val cacheFile = File(cacheDir, "gptsovits_$hash.wav")
        val audioPath = if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.absolutePath
        } else {
            // 2. 合成
            val voice = voiceId.ifEmpty { "default" }
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("text_lang", textLanguage)
            }.toString()

            val request = Request.Builder()
                .url("${url.trimEnd('/')}/tts")
                .post(jsonBody.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
                .build()

            try {
                // 把 execute + 读取响应体都放进 withTimeoutOrNull 块内
                // 超时时协程被取消，resp.use 会触发 close，避免连接泄漏
                val path = withTimeoutOrNull(30_000L) {
                    val resp = HttpClient.client.newCall(request).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) {
                            val err = r.body?.string()?.take(120) ?: ""
                            throw java.io.IOException("GPT-SoVITS HTTP ${r.code}: $err")
                        }
                        val bytes = r.body?.bytes() ?: throw java.io.IOException("GPT-SoVITS 空响应")
                        cacheFile.outputStream().use { it.write(bytes) }
                        cacheFile.absolutePath
                    }
                } ?: return TtsOutcome.Failed("GPT-SoVITS 推理超时（30s）")
                path
            } catch (e: java.io.IOException) {
                // 区分 HTTP 错误（带 "HTTP" 字样）和网络异常
                val msg = e.message ?: ""
                return if (msg.contains("HTTP")) {
                    TtsOutcome.Failed(msg.take(80), e)
                } else {
                    TtsOutcome.Failed("GPT-SoVITS 请求异常: ${msg.take(60)}", e)
                }
            } catch (e: Exception) {
                return TtsOutcome.Failed("GPT-SoVITS 请求异常: ${e.message?.take(60)}", e)
            }
        }

        // 3. 播放
        return suspendCancellableCoroutine { cont ->
            var started = false
            var resumed = false
            fun safeResume(outcome: TtsOutcome) {
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
                        safeResume(if (started) TtsOutcome.Success else TtsOutcome.Failed("GPT-SoVITS 播放未启动"))
                    }
                )
            } catch (e: Exception) {
                safeResume(TtsOutcome.Failed("GPT-SoVITS 播放异常", e))
            }

            cont.invokeOnCancellation {
                try { player.stop() } catch (_: Exception) {}
            }
        }
    }

    override fun stop() {
        player.stop()
    }

    override fun shutdown() {
        player.stop()
    }

    /** 从 wechat_settings 读取 PC GPT-SoVITS 服务地址 */
    private fun getServiceUrl(): String {
        return ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .getString("tts_gptsovits_url", "")?.trim() ?: ""
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
