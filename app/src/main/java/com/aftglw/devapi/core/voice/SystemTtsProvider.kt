package com.aftglw.devapi.core.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

/**
 * 系统级 TTS Provider：包装现有 [VoiceTts]，通过 suspendCancellableCoroutine
 * 将回调式接口桥接到协程。
 *
 * 特点：
 * - 直播模式（不缓存文件，无法重播同一段）
 * - 初始化异步，[isAvailable] 内部调用 initSync
 * - voiceId 参数被忽略（系统 TTS 只能用系统已安装音色）
 * - 失败场景：引擎未就绪、初始化超时
 */
class SystemTtsProvider(ctx: Context) : TtsProvider {

    override val id: String = "system"

    private val tts = VoiceTts.getInstance(ctx.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var initialized = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) {
            initialized = tts.initSync(3000L)
        }
        tts.isReady()
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
        // 确保 TTS 已初始化
        if (!tts.isReady()) {
            val ok = tts.initSync(1500L)
            if (!ok) return TtsOutcome.Failed("SystemTts 初始化失败")
            initialized = true
        }
        if (text.isBlank()) return TtsOutcome.Failed("空文本")

        return suspendCancellableCoroutine { cont ->
            // 回调需在主线程触发（VoiceTts 内部回调来自 TTS 线程，统一切到主线程）
            var started = false
            var resumed = false
            fun safeResume(outcome: TtsOutcome) {
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(outcome)
                }
            }
            try {
                tts.speak(
                    text = text,
                    utteranceId = utteranceId,
                    onStart = {
                        started = true
                        mainHandler.post { onStart() }
                    },
                    onDone = {
                        mainHandler.post {
                            onDone(started)  // started=true 表示至少播过；false 表示直接 onError
                        }
                        safeResume(if (started) TtsOutcome.Success else TtsOutcome.Failed("SystemTts 播放失败"))
                    }
                )
            } catch (e: Exception) {
                safeResume(TtsOutcome.Failed("SystemTts 异常", e))
            }

            cont.invokeOnCancellation {
                // 协程被取消时停止朗读
                try { tts.stop() } catch (_: Exception) {}
            }
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
        initialized = false
    }
}
