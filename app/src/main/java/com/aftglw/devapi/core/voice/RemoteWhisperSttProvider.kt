package com.aftglw.devapi.core.voice

import android.content.Context
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * 远程 Whisper STT Provider：POST 音频到 PC 端运行的 Whisper 推理服务。
 *
 * 预期 PC 端 API（兼容 faster-whisper-server / whisper-asr-webservice 等）：
 *
 * GET  /healthz               → "ok"（探活）
 * POST /transcribe            → 转写音频
 *      请求体：multipart/form-data
 *        file:        音频文件（aac/wav/mp3/m4a）
 *        language:    可选，如 "zh"、"en"
 *        response_format: "json"（可选）
 *      响应体：{"text": "转写文本"}
 *
 * 也可兼容 OpenAI 风格的 /v1/audio/transcriptions 端点（如果 PC 服务实现该接口）。
 *
 * 失败场景：
 * - PC 服务未启动 / 网络不通 → isAvailable=false
 * - 推理超时（>30s）→ Failed
 * - 响应 JSON 解析失败 → Failed
 *
 * 配置项（wechat_settings）：
 *   - stt_whisper_url: PC 服务地址，例如 http://192.168.1.10:9000
 *
 * 与 [RemoteGptSoVitsTtsProvider] 对称设计。
 */
class RemoteWhisperSttProvider(ctx: Context) : SttProvider {

    override val id: String = "remote_whisper"

    private val ctx = ctx.applicationContext

    /** 探活结果缓存（避免每次 transcribe 都 ping），5 分钟有效 */
    @Volatile private var lastHealthCheckMs: Long = 0L
    @Volatile private var lastHealthResult: Boolean = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val url = getServiceUrl()
        if (url.isEmpty()) return@withContext false

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
        val url = getServiceUrl()
        if (url.isEmpty()) return@withContext SttOutcome.Failed("PC Whisper 服务地址未配置")

        // 构建 multipart 请求（与 CloudSttProvider 一致）
        val fileMediaType = when (file.extension.lowercase()) {
            "aac" -> "audio/aac".toMediaTypeOrNull()
            "wav" -> "audio/wav".toMediaTypeOrNull()
            "m4a" -> "audio/mp4".toMediaTypeOrNull()
            "webm" -> "audio/webm".toMediaTypeOrNull()
            "mp3", "mpeg" -> "audio/mpeg".toMediaTypeOrNull()
            else -> "audio/mpeg".toMediaTypeOrNull()
        }
        val fileBody = file.asRequestBody(fileMediaType)
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
        if (lang.isNotEmpty()) {
            val short = lang.substringBefore('-').ifEmpty { lang }
            multipartBuilder.addFormDataPart("language", short)
        }
        val requestBody = multipartBuilder.build()

        val request = Request.Builder()
            .url("${url.trimEnd('/')}/transcribe")
            .post(requestBody)
            .build()

        try {
            val path = withTimeoutOrNull(30_000L) {
                val resp = HttpClient.client.newCall(request).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val err = r.body?.string()?.take(200) ?: ""
                        throw java.io.IOException("Whisper HTTP ${r.code}: $err")
                    }
                    val bodyStr = r.body?.string() ?: throw java.io.IOException("Whisper 空响应")
                    val json = JSONObject(bodyStr)
                    json.optString("text", "").trim()
                }
            } ?: return@withContext SttOutcome.Failed("Whisper 推理超时（30s）")
            SttOutcome.Success(path)
        } catch (e: java.io.IOException) {
            val msg = e.message ?: ""
            SttOutcome.Failed(
                if (msg.contains("HTTP")) msg.take(80)
                else "Whisper 请求异常: ${msg.take(60)}",
                e
            )
        } catch (e: Exception) {
            SttOutcome.Failed("Whisper 请求异常: ${e.message?.take(60)}", e)
        }
    }

    override fun stop() {
        // 同步 HTTP 请求，无状态可停
    }

    override fun shutdown() {
        // 无状态
    }

    /** 从 wechat_settings 读取 PC Whisper 服务地址 */
    private fun getServiceUrl(): String {
        return ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .getString("stt_whisper_url", "")?.trim() ?: ""
    }
}
