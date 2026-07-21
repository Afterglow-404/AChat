package com.aftglw.devapi.core.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 云端 TTS Provider：包装现有 [CloudTtsService]（OpenAI 兼容 /v1/audio/speech）+ [VoicePlayer]。
 *
 * 复用项目硬约束：Cloud TTS 必须用 SHA-1 hashed filenames 缓存到 cacheDir/tts/。
 * 该约束已在 [CloudTtsService.synthesize] 内实现，本 Provider 直接复用其缓存逻辑。
 *
 * 失败场景：
 * - API Key / URL 未配置 → isAvailable=false
 * - 网络异常 / HTTP 非 2xx → speak 返回 Failed
 * - 播放器 prepare 异常 → speak 返回 Failed
 */
class CloudTtsProvider(ctx: Context) : TtsProvider {

    override val id: String = "cloud"

    private val ctx = ctx.applicationContext
    private val service = CloudTtsService(ctx)
    private val player = VoicePlayer(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 只校验配置存在；不发实际 ping（避免每次 speak 都消耗一次网络）
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val hasKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "tts_cloud_key").isNotEmpty() ||
            com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key").isNotEmpty()
        val hasUrl = prefs.getString("tts_cloud_url", "")?.isNotEmpty() == true ||
            prefs.getString("ai_api_url", "")?.isNotEmpty() == true
        hasKey && hasUrl
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
        val voice = voiceId.ifEmpty { "alloy" }

        // 1. 合成（或命中缓存）→ 拿到本地音频文件路径
        val audioPath = try {
            service.synthesize(text, voice)
        } catch (e: Exception) {
            return TtsOutcome.Failed("CloudTts 合成失败: ${e.message?.take(60)}", e)
        }

        // 2. 播放（用 suspendCancellableCoroutine 桥接 VoicePlayer 回调）
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
                        safeResume(if (started) TtsOutcome.Success else TtsOutcome.Failed("CloudTts 播放未启动"))
                    }
                )
            } catch (e: Exception) {
                safeResume(TtsOutcome.Failed("CloudTts 播放异常", e))
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
        // CloudTtsService 无状态，无需 shutdown
    }
}
