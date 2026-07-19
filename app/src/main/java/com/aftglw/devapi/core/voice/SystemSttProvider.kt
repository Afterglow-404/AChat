package com.aftglw.devapi.core.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统级 STT Provider：包装 Android [VoiceSttHelper]（基于 SpeechRecognizer）。
 *
 * 重要：Android 系统 STT 是「实时识别」模式 —— 需要在录音期间持续监听麦克风，
 * 不能在录音停止后对已保存的音频文件做转写。
 *
 * 因此本 Provider 的 [transcribe] 接口语义特殊：
 * - 不实际转写 [audioPath] 指向的文件
 * - 直接返回 [SttOutcome.Failed]，让 [SttProviderManager] 降级到下一个 Provider
 *   或最终触发 [onError]，调用方走「仅发送音频」分支
 *
 * 实际的「边录边识别」流程由调用方（ChatScreen）保留原 [VoiceSttHelper] 调用
 * 实现：当 `stt_engine=system` 时仍用 VoiceSttHelper；只有当
 * `stt_engine=cloud` / `remote_whisper` 时才走 [SttProviderManager.transcribe]。
 *
 * 失败场景：
 * - 设备/ROM 不支持 SpeechRecognizer → isAvailable=false
 * - 调用 transcribe（不应该走到这里）→ Failed
 */
class SystemSttProvider(ctx: Context) : SttProvider {

    override val id: String = "system"

    private val helper = VoiceSttHelper(ctx.applicationContext)

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 静默检测：不实际创建 SpeechRecognizer（避免资源占用）
        try {
            android.content.pm.PackageManager::class.java
            helper.isAvailable()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun transcribe(
        audioPath: String,
        lang: String,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ): SttOutcome {
        // 系统 STT 不支持文件转写；直接返回失败，让 Manager 降级或最终触发 onError
        return SttOutcome.Failed("系统 STT 不支持文件转写，请使用云端 / PC Whisper 引擎")
    }

    override fun stop() {
        try { helper.stop() } catch (_: Exception) {}
    }

    override fun shutdown() {
        try { helper.stop() } catch (_: Exception) {}
    }
}
