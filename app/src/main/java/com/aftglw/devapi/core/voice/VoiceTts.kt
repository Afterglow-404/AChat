package com.aftglw.devapi.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音合成（TTS）：封装 Android 原生 TextToSpeech。
 *
 * 用法：
 *   val tts = VoiceTts(ctx)
 *   tts.initSync()              // 初始化（同步等待引擎就绪，超时 3s）
 *   tts.speak("你好", utteranceId, onStart = {}, onDone = {})
 *   tts.stop()
 *
 * 注意：
 * - 单实例管理，同时只能朗读一段
 * - 引擎初始化是异步的，initSync 内部用 CountDownLatch 等待
 * - 语速/音调可通过 updateParams 修改
 */
class VoiceTts(ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ready = AtomicBoolean(false)
    private val latch = java.util.concurrent.CountDownLatch(1)

    // 默认参数；可通过 updateParams 覆盖
    private var speechRate = 1.0f  // 0.5 ~ 2.0
    private var pitch = 1.0f       // 0.5 ~ 2.0

    // 当前正在播放的 utteranceId
    var currentUtteranceId: String? = null
        private set

    private var startCallback: (() -> Unit)? = null
    private var doneCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = this.tts
                if (tts != null) {
                    val res = tts.setLanguage(Locale.CHINESE)
                    // LANG_MISSING_DATA / LANG_NOT_SUPPORTED 也能继续，只是可能读音不准
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            currentUtteranceId = utteranceId
                            startCallback?.invoke()
                        }
                        override fun onDone(utteranceId: String?) {
                            currentUtteranceId = null
                            startCallback = null
                            doneCallback?.invoke()
                            doneCallback = null
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            currentUtteranceId = null
                            startCallback = null
                            doneCallback?.invoke()
                            doneCallback = null
                        }
                    })
                    ready.set(true)
                }
            }
            latch.countDown()
        }
    }

    /** 同步等待初始化，最多 [timeoutMs] 毫秒；返回是否就绪 */
    fun initSync(timeoutMs: Long = 3000L): Boolean {
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ready.get()
    }

    fun isReady(): Boolean = ready.get()

    /** 更新语速/音调 */
    fun updateParams(rate: Float? = null, pitch: Float? = null) {
        if (rate != null) speechRate = rate.coerceIn(0.5f, 2.0f)
        if (pitch != null) this.pitch = pitch.coerceIn(0.5f, 2.0f)
    }

    /** 获取当前语速/音调 */
    fun getRate(): Float = speechRate
    fun getPitch(): Float = pitch

    /**
     * 朗读 [text]。若已有朗读则先停止。
     * [utteranceId] 必须唯一（用于回调追踪，建议用消息 id 或 hash）。
     */
    fun speak(
        text: String,
        utteranceId: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val tts = this.tts
        if (tts == null || !ready.get()) {
            onDone()
            return
        }
        stop()
        startCallback = onStart
        doneCallback = onDone
        tts.setSpeechRate(speechRate)
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        val tts = this.tts ?: return
        try { tts.stop() } catch (_: Exception) {}
        currentUtteranceId = null
        startCallback = null
        // 触发 done 回调，让 UI 状态复位
        doneCallback?.invoke()
        doneCallback = null
    }

    fun isPlaying(utteranceId: String): Boolean = currentUtteranceId == utteranceId

    fun shutdown() {
        val tts = this.tts ?: return
        try { tts.stop() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        this.tts = null
        ready.set(false)
    }
}
