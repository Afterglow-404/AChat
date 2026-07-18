package com.aftglw.devapi.core.voice

import android.content.Context
import android.media.MediaPlayer

/**
 * 语音播放器：封装 MediaPlayer，单实例管理。
 *
 * 用法：
 *   val player = VoicePlayer(ctx)
 *   player.play(path, onStart = {}, onComplete = {})
 *   player.stop()
 *
 * 同一时刻只播一条语音；切换到新语音时自动停旧。
 */
class VoicePlayer(private val ctx: Context) {

    private var player: MediaPlayer? = null

    /** 当前正在播放的文件路径 */
    var currentPath: String? = null
        private set

    /** 开始播放；若已有播放则先停止 */
    fun play(
        path: String,
        onStart: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                player = null
                currentPath = null
                onComplete()
            }
            mp.start()
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            return
        }
        player = mp
        currentPath = path
        onStart()
    }

    fun stop() {
        player?.let { mp ->
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        player = null
        currentPath = null
    }

    fun isPlaying(path: String): Boolean = currentPath == path
}
