package com.aftglw.devapi.core.voice

import android.content.Context
import com.aftglw.devapi.network.HttpClient
import java.io.File
import java.security.MessageDigest

/**
 * 云端 TTS 服务：调用 OpenAI 兼容的 /v1/audio/speech 接口，将返回的音频字节
 * 缓存到 cacheDir/tts/，文件名按 (voice + text) 的 SHA-1 哈希命名，避免重复请求。
 *
 * 用法：
 *   val svc = CloudTtsService(ctx)
 *   val audioPath = svc.synthesize("你好", voice = "alloy")  // 挂起，返回本地文件路径
 *   VoicePlayer(ctx).play(audioPath)
 *
 * 失败时抛 IOException；调用方需 try/catch 并回退到本地 TTS。
 *
 * 配置项（来自 wechat_settings）：
 *   - tts_cloud_url  : 完整端点 URL（默认用 ai_api_url 拼接 /v1/audio/speech）
 *   - tts_cloud_key  : API Key（默认用 ai_api_key）
 *   - tts_cloud_model: TTS 模型名（默认 "tts-1"）
 */
class CloudTtsService(private val ctx: Context) {

    /**
     * 合成语音并返回本地缓存文件路径。
     * @param text 待合成文本
     * @param voice 音色名（OpenAI: alloy/echo/fable/onyx/nova/shimmer）
     * @param url 端点 URL；为空时从设置读取
     * @param apiKey 鉴权 Key；为空时从设置读取
     * @param model TTS 模型；为空时从设置读取
     */
    suspend fun synthesize(
        text: String,
        voice: String,
        url: String = "",
        apiKey: String = "",
        model: String = ""
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val endpoint = url.ifEmpty {
            (prefs.getString("tts_cloud_url", "") ?: "").ifEmpty {
                // 回退：基于 ai_api_url 推导
                val aiUrl = (prefs.getString("ai_api_url", "") ?: "").trimEnd('/')
                if (aiUrl.isEmpty()) throw java.io.IOException("TTS 端点未配置")
                "$aiUrl/v1/audio/speech"
            }
        }
        val key = apiKey.ifEmpty { (prefs.getString("tts_cloud_key", "") ?: "").ifEmpty { prefs.getString("ai_api_key", "") ?: "" } }
        if (key.isEmpty()) throw java.io.IOException("TTS Key 未配置")
        val mdl = model.ifEmpty { prefs.getString("tts_cloud_model", "tts-1") ?: "tts-1" }

        // 缓存目录
        val cacheDir = File(ctx.cacheDir, "tts").apply { mkdirs() }
        val hash = sha1("$voice|$mdl|$text")
        val cacheFile = File(cacheDir, "$hash.mp3")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext cacheFile.absolutePath
        }

        // OpenAI 兼容请求体
        val jsonBody = """
            {"model":"$mdl","input":${escapeJson(text)},"voice":"$voice","response_format":"mp3"}
        """.trimIndent()
        val request = HttpClient.postJson(
            endpoint,
            jsonBody,
            "Authorization" to "Bearer $key"
        )

        val response = HttpClient.client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(200) ?: ""
                throw java.io.IOException("HTTP ${response.code} - ${response.message} - $errBody")
            }
            val bytes = response.body?.bytes() ?: throw java.io.IOException("空响应体")
            cacheFile.outputStream().use { it.write(bytes) }
            cacheFile.absolutePath
        } finally {
            response.close()
        }
    }

    /** 清空 TTS 缓存目录 */
    fun clearCache() {
        val cacheDir = File(ctx.cacheDir, "tts")
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        /** OpenAI TTS 常用音色列表 */
        val AVAILABLE_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
    }
}
