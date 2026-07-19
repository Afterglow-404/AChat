package com.aftglw.devapi.core.voice

import android.content.Context
import com.aftglw.devapi.core.security.SecureKeyStore
import com.aftglw.devapi.network.HttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * 云端 STT Provider：调用 OpenAI 兼容的 /v1/audio/transcriptions（Whisper API）。
 *
 * 请求格式：multipart/form-data
 *   - file:       音频文件（aac/mpeg/mp3/wav/m4a/webm 等）
 *   - model:      "whisper-1"（默认）或其他兼容模型
 *   - language:   可选，语言代码如 "zh"、"en"；空则自动检测
 *   - response_format: "json"（默认，返回 {"text": "..."}）
 *
 * 响应格式：{"text": "转写文本"}
 *
 * 失败场景：
 * - API Key / URL 未配置 → isAvailable=false
 * - 网络异常 / HTTP 非 2xx → Failed
 * - 响应 JSON 解析失败 → Failed
 *
 * 配置项（wechat_settings）：
 *   - stt_cloud_url   : 完整端点 URL（默认用 ai_api_url 拼接 /v1/audio/transcriptions）
 *   - stt_cloud_key   : API Key（默认复用 ai_api_key）
 *   - stt_cloud_model : STT 模型名（默认 "whisper-1"）
 *
 * 与 [CloudTtsProvider] 对称设计。
 */
class CloudSttProvider(ctx: Context) : SttProvider {

    override val id: String = "cloud"

    private val ctx = ctx.applicationContext

    override suspend fun isAvailable(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val hasKey = SecureKeyStore.getString(ctx, "stt_cloud_key").isNotEmpty() ||
            SecureKeyStore.getString(ctx, "ai_api_key").isNotEmpty()
        val hasUrl = prefs.getString("stt_cloud_url", "")?.isNotEmpty() == true ||
            prefs.getString("ai_api_url", "")?.isNotEmpty() == true
        hasKey && hasUrl
    }

    override suspend fun transcribe(
        audioPath: String,
        lang: String,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ): SttOutcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            return@withContext SttOutcome.Failed("音频文件不存在或为空: $audioPath")
        }

        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val endpoint = (prefs.getString("stt_cloud_url", "") ?: "").ifEmpty {
            val aiUrl = (prefs.getString("ai_api_url", "") ?: "").trimEnd('/')
            if (aiUrl.isEmpty()) {
                return@withContext SttOutcome.Failed("STT 端点未配置")
            }
            if (aiUrl.endsWith("/v1")) "$aiUrl/audio/transcriptions"
            else "$aiUrl/v1/audio/transcriptions"
        }
        val key = SecureKeyStore.getString(ctx, "stt_cloud_key").ifEmpty {
            SecureKeyStore.getString(ctx, "ai_api_key")
        }
        if (key.isEmpty()) {
            return@withContext SttOutcome.Failed("STT Key 未配置")
        }
        val model = prefs.getString("stt_cloud_model", "whisper-1") ?: "whisper-1"

        // 构建 multipart 请求
        // aac 文件用 audio/aac；其他格式用 audio/mpeg 兜底（OpenAI 接受多种格式）
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
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        // language: OpenAI Whisper API 用 ISO-639-1 双字母代码（zh / en），不使用 zh-CN
        if (lang.isNotEmpty()) {
            val short = lang.substringBefore('-').ifEmpty { lang }
            multipartBuilder.addFormDataPart("language", short)
        }
        val requestBody = multipartBuilder.build()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $key")
            .build()

        try {
            val resp = HttpClient.client.newCall(request).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    val err = r.body?.string()?.take(200) ?: ""
                    return@withContext SttOutcome.Failed("Whisper HTTP ${r.code}: $err")
                }
                val bodyStr = r.body?.string() ?: return@withContext SttOutcome.Failed("Whisper 空响应")
                val json = try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    return@withContext SttOutcome.Failed("Whisper 响应解析失败: ${bodyStr.take(80)}", e)
                }
                val text = json.optString("text", "").trim()
                SttOutcome.Success(text)
            }
        } catch (e: Exception) {
            SttOutcome.Failed("Whisper 请求异常: ${e.message?.take(80)}", e)
        }
    }

    override fun stop() {
        // 同步 HTTP 请求，无状态可停
    }

    override fun shutdown() {
        // 无状态
    }
}
