package com.aftglw.devapi.core.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一 STT Provider 接口：屏蔽系统 STT / 云 Whisper / 远程 PC Whisper 的差异。
 *
 * 三种实现：
 * - [SystemSttProvider]：包装 Android [VoiceSttHelper]，系统 SpeechRecognizer 回调式
 * - [CloudSttProvider]：调用 OpenAI 兼容 /v1/audio/transcriptions（Whisper API）
 * - [RemoteWhisperSttProvider]：POST 音频到 PC 端 Whisper 服务（如 faster-whisper-server）
 *
 * 设计约定（与 [TtsProvider] 对称）：
 * - 调用方只看 [transcribe]，不关心底层是回调式还是 HTTP 式
 * - 所有 Provider 都通过 suspend 函数统一时序，结束时回调 [onResult] / [onError]
 * - 失败时由 [SttProviderManager] 按降级链切换备用 Provider
 *
 * 与 TTS 的差异：
 * - STT 输入是音频文件路径（非文本），输出是文字
 * - STT 不需要 voiceId / characterName 路由，但需要 [lang]（语言代码）
 * - STT 仍是事件驱动（系统 STT 可能不回调），调用方需保留超时保护
 */
interface SttProvider {
    /** Provider 标识：`system` / `cloud` / `remote_whisper` */
    val id: String

    /**
     * 探测是否可用（不抛异常）。
     * - SystemStt：返回 SpeechRecognizer.isRecognitionAvailable
     * - CloudStt：检查 API key/URL 是否配置
     * - RemoteWhisper：HTTP ping PC 服务
     */
    suspend fun isAvailable(): Boolean

    /**
     * 将 [audioPath] 指向的音频文件转为文字。
     *
     * @param audioPath  音频文件绝对路径（VoiceRecorder 输出的 aac 文件）
     * @param lang       语言代码（如 "zh-CN"、"en-US"）；空表示自动检测
     * @param onResult   成功回调，参数为转写文本（可能为空串）
     * @param onError    失败回调
     * @return SttOutcome 最终结果（成功/失败原因）
     */
    suspend fun transcribe(
        audioPath: String,
        lang: String = "zh-CN",
        onResult: (String) -> Unit = {},
        onError: () -> Unit = {}
    ): SttOutcome

    /** 停止当前转写 */
    fun stop()

    /** 释放资源（Provider 切换/退出时调用） */
    fun shutdown()
}

/**
 * STT 转写结果。
 */
sealed class SttOutcome {
    /** 成功转写（text 可能是空串，但调用流程正常完成） */
    data class Success(val text: String) : SttOutcome()

    /**
     * 失败。
     * @param reason 失败原因（用于降级链日志与 UI 提示）
     * @param cause  原始异常（可空）
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : SttOutcome()
}

/**
 * STT Provider 单例管理器。
 *
 * 职责：
 * - 按 `stt_engine` 设置选择主 Provider + 降级链
 * - 主 Provider 失败时自动尝试降级链
 *
 * 用法：
 *   SttProviderManager.configure(ctx, "cloud")
 *   SttProviderManager.transcribe(
 *       ctx = ctx,
 *       audioPath = file.absolutePath,
 *       lang = "zh-CN",
 *       onResult = { text -> ... },
 *       onError = { ... }
 *   )
 *
 * 约束（遵守项目硬约束）：
 * - 单例管理：保证同时只有一个 Provider 在转写
 * - 异步：transcribe 是 suspend 函数，调用方走协程，不阻塞主线程
 * - 回调去重：onResult / onError 在降级链中只转发一次
 */
object SttProviderManager {

    private const val TAG = "SttProviderManager"

    /** 当前主 Provider；为 null 表示未配置 */
    @Volatile
    private var primary: SttProvider? = null

    /** 降级链；primary 失败时按顺序尝试 */
    @Volatile
    private var fallbacks: List<SttProvider> = emptyList()

    /** 当前活跃 Provider（最近一次成功使用的） */
    @Volatile
    private var active: SttProvider? = null

    /**
     * 按引擎名配置降级链。
     *
     * @param engine "system" | "cloud" | "remote_whisper" | "local_whisper" | "local_sensevoice" | "xfyun"
     * engine=local_sensevoice 时降级链：local_sensevoice → cloud → system（本地新模型优先，云兜底）
     * engine=local_whisper   时降级链：local_whisper → cloud → system（本地优先保隐私，云兜底）
     * engine=xfyun           时降级链：xfyun → cloud → system（讯飞在线优先，云兜底）
     * engine=remote_whisper  时降级链：remote_whisper → cloud → system
     * engine=cloud           时降级链：cloud → system
     * engine=system          时降级链：system（不降级）
     */
    @Synchronized
    fun configure(ctx: Context, engine: String) {
        shutdown()

        val system = SystemSttProvider(ctx)
        val cloud = CloudSttProvider(ctx)
        val remote = RemoteWhisperSttProvider(ctx)
        val local = LocalWhisperSttProvider(ctx)
        val senseVoice = LocalSenseVoiceSttProvider(ctx)
        val xfyun = XfyunSttProvider(ctx)

        when (engine) {
            "local_sensevoice" -> {
                primary = senseVoice
                fallbacks = listOf(xfyun, cloud, system)
            }
            "xfyun" -> {
                primary = xfyun
                fallbacks = listOf(cloud, system)
            }
            "local_whisper" -> {
                primary = local
                fallbacks = listOf(senseVoice, cloud, system)
            }
            "remote_whisper" -> {
                primary = remote
                fallbacks = listOf(cloud, system)
            }
            "cloud" -> {
                primary = cloud
                fallbacks = listOf(system)
            }
            else -> {
                primary = system
                fallbacks = emptyList()
            }
        }
        android.util.Log.i(TAG, "Configured: primary=${primary?.id}, fallbacks=${fallbacks.map { it.id }}")
    }

    /** 当前主 Provider id（用于 UI 显示） */
    fun currentEngineId(): String? = primary?.id

    /**
     * 转写：先试 primary，失败按降级链尝试。
     */
    suspend fun transcribe(
        ctx: Context,
        audioPath: String,
        lang: String = "zh-CN",
        onResult: (String) -> Unit = {},
        onError: () -> Unit = {}
    ): SttOutcome = withContext(Dispatchers.IO) {
        val primary = primary ?: return@withContext SttOutcome.Failed("STT 未配置")

        val chain = listOf(primary) + fallbacks
        var lastOutcome: SttOutcome = SttOutcome.Failed("无可用 Provider")

        // 去重保护：onResult / onError 只转发第一次
        // 异常隔离：回调里若抛异常（如 Toast 在 IO 线程调用），
        // 不能让协程崩掉，吞下并记录日志
        var onResultFired = false
        var onErrorFired = false
        fun safeResult(text: String) {
            if (!onResultFired) {
                onResultFired = true
                try { onResult(text) } catch (e: Exception) {
                    android.util.Log.e(TAG, "onResult callback threw", e)
                }
            }
        }
        fun safeError() {
            if (!onErrorFired) {
                onErrorFired = true
                try { onError() } catch (e: Exception) {
                    android.util.Log.e(TAG, "onError callback threw", e)
                }
            }
        }

        for (provider in chain) {
            try {
                val available = provider.isAvailable()
                if (!available) {
                    android.util.Log.w(TAG, "Provider ${provider.id} not available, skip")
                    lastOutcome = SttOutcome.Failed("Provider ${provider.id} 不可用")
                    continue
                }
                android.util.Log.d(TAG, "Trying provider: ${provider.id}")
                val outcome = provider.transcribe(
                    audioPath = audioPath,
                    lang = lang,
                    onResult = { safeResult(it) },
                    onError = { /* 失败时不立即转发，等降级链尝试完再统一转发 */ }
                )
                if (outcome is SttOutcome.Success) {
                    active = provider
                    safeResult(outcome.text)
                    return@withContext outcome
                }
                lastOutcome = outcome
                android.util.Log.w(TAG, "Provider ${provider.id} failed: ${(outcome as SttOutcome.Failed).reason}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Provider ${provider.id} exception", e)
                lastOutcome = SttOutcome.Failed("Provider ${provider.id} 异常", e)
            }
        }
        // 全部失败：转发一次 onError 让 UI 复位
        safeError()
        lastOutcome
    }

    /** 停止当前活跃 Provider 的转写 */
    fun stop() {
        val a = active ?: primary
        a?.stop()
    }

    /** 释放所有 Provider 资源（切换引擎/退出时调用） */
    @Synchronized
    fun shutdown() {
        primary?.shutdown()
        fallbacks.forEach { it.shutdown() }
        primary = null
        fallbacks = emptyList()
        active = null
    }
}
