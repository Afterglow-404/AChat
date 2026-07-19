package com.aftglw.devapi.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 录音器：封装 MediaRecorder，输出 aac 文件到 filesDir/voice/。
 *
 * 用法：
 *   val recorder = VoiceRecorder(ctx)
 *   val file = recorder.start() ?: return  // 开始录音
 *   val duration = recorder.stop()         // 停止，返回时长（秒）；返回 0 表示失败
 *   recorder.cancel()                      // 取消并删除文件
 */
class VoiceRecorder(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    /** 开始录音，返回输出文件路径；失败返回 null */
    fun start(): File? {
        val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.aac")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(ctx)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (_: Exception) {
            try { r.release() } catch (_: Exception) {}
            return null
        }
        recorder = r
        outputFile = file
        startTimeMs = System.currentTimeMillis()
        return file
    }

    /** 停止录音，返回时长（秒）；失败返回 0 */
    fun stop(): Int {
        val r = recorder ?: return 0
        val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
        var stopped = false
        try {
            r.stop()
            stopped = true
        } catch (_: Exception) {
            // stop 失败通常因为录音时间过短，文件不可用
        } finally {
            r.release()
            recorder = null
        }
        if (!stopped) outputFile?.delete()
        outputFile = null
        return if (stopped) elapsed else 0
    }

    /** 取消录音，删除文件 */
    fun cancel() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        outputFile?.let { if (it.exists()) it.delete() }
        outputFile = null
    }

    companion object {
        /** 检测文件时长（秒），失败返回 0 */
        fun getDurationSec(ctx: Context, path: String): Int {
            if (path.isEmpty()) return 0
            val file = File(path)
            if (!file.exists()) return 0
            return try {
                val mp = MediaPlayer()
                mp.setDataSource(path)
                mp.prepare()
                val dur = (mp.duration / 1000).coerceAtLeast(1)
                mp.release()
                dur
            } catch (_: Exception) { 1 }
        }
    }
}
