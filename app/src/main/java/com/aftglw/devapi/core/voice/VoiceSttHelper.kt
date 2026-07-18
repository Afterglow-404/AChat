package com.aftglw.devapi.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音转文字：封装 Android 原生 SpeechRecognizer。
 *
 * 用法：
 *   val helper = VoiceSttHelper(ctx)
 *   helper.start(
 *       onResult = { text -> /* 最终结果 */ },
 *       onError = { /* 出错 */ }
 *   )
 *   helper.stop()  // 可选，提前停止
 *
 * 注意：必须在主线程调用，且 SpeechRecognizer 在某些设备/ROM 上可能不可用。
 */
class VoiceSttHelper(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(
        onResult: (String) -> Unit,
        onError: () -> Unit
    ) {
        if (!isAvailable()) {
            onError()
            return
        }
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onError()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) onError() else onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
        recognizer = sr
    }

    fun stop() {
        recognizer?.let { sr ->
            try { sr.stopListening() } catch (_: Exception) {}
            try { sr.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }
}
