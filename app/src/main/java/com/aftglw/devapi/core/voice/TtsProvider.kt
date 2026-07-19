package com.aftglw.devapi.core.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一 TTS Provider 接口：屏蔽系统 TTS / 云 TTS / 远程 GPT-SoVITS 的差异。
 *
 * 三种实现：
 * - [SystemTtsProvider]：包装 Android [VoiceTts]，回调式直播，无文件输出
 * - [CloudTtsProvider]：包装 [CloudTtsService] + [VoicePlayer]，OpenAI 兼容云 TTS
 * - [RemoteGptSoVitsTtsProvider]：调用 PC 端 GPT-SoVITS 服务，支持音色克隆
 *
 * 设计约定：
 * - 调用方只看 [speak]，不关心底层是回调式还是文件式
 * - 所有 Provider 都通过 suspend 函数统一时序，结束时回调 [onDone]
 * - [voiceId] 由 [TtsVoiceRouter] 根据角色名映射后传入
 * - 失败时由 [TtsProviderManager] 按降级链切换备用 Provider
 */
interface TtsProvider {
    /** Provider 标识：`system` / `cloud` / `gpt_sovits` */
    val id: String

    /**
     * 探测是否可用（不抛异常）。
     * - SystemTts：返回 VoiceTts.isReady()
     * - CloudTts：检查 API key/URL 是否配置
     * - RemoteGptSoVits：HTTP ping PC 服务
     */
    suspend fun isAvailable(): Boolean

    /**
     * 朗读 [text]，按 [voiceId] 选择音色。
     *
     * @param text         待合成文本
     * @param voiceId      音色标识（SystemTts 忽略；CloudTts 用 alloy/echo 等；GPT-SoVITS 用音色 ID）
     * @param utteranceId  唯一追踪 ID（用于 stop / isPlaying）
     * @param onStart      开始播放回调（主线程）
     * @param onDone       结束回调（success=true 表示正常播完，false 表示失败/中止）
     * @return TtsOutcome  最终结果（成功/失败原因）
     */
    suspend fun speak(
        text: String,
        voiceId: String,
        utteranceId: String,
        onStart: () -> Unit = {},
        onDone: (success: Boolean) -> Unit = {}
    ): TtsOutcome

    /** 停止当前朗读 */
    fun stop()

    /** 释放资源（Provider 切换/退出时调用） */
    fun shutdown()
}

/**
 * TTS 合成结果。
 */
sealed class TtsOutcome {
    /** 成功播完 */
    object Success : TtsOutcome()

    /**
     * 失败。
     * @param reason 失败原因（用于降级链日志与 UI 提示）
     * @param cause  原始异常（可空）
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : TtsOutcome()
}

/**
 * TTS Provider 单例管理器。
 *
 * 职责：
 * - 按 `tts_engine` 设置选择主 Provider + 降级链
 * - 主 Provider 失败时自动尝试降级链
 * - 提供角色音色路由（[TtsVoiceRouter]）
 *
 * 用法：
 *   TtsProviderManager.configure(ctx, "gpt_sovits")
 *   TtsProviderManager.speak(
 *       ctx = ctx,
 *       text = "你好",
 *       utteranceId = "msg_1",
 *       characterName = "钦灵",
 *       onStart = { playingTtsId = "msg_1" },
 *       onDone = { success -> playingTtsId = null }
 *   )
 *
 * 约束（遵守项目硬约束）：
 * - 单例管理：保证同时只有一个 Provider 在播放
 * - 异步：speak 是 suspend 函数，调用方走协程，不阻塞主线程
 * - SHA-1 缓存：File-based Provider（Cloud/GPT-SoVITS）复用 cacheDir/tts/，按 (voiceId|text) 哈希命名
 */
object TtsProviderManager {

    private const val TAG = "TtsProviderManager"

    /** 当前主 Provider；为 null 表示未配置 */
    @Volatile
    private var primary: TtsProvider? = null

    /** 降级链；primary 失败时按顺序尝试 */
    @Volatile
    private var fallbacks: List<TtsProvider> = emptyList()

    /** 当前活跃 Provider（最近一次成功使用的） */
    @Volatile
    private var active: TtsProvider? = null

    /** 角色音色路由 */
    private val router = TtsVoiceRouter

    /**
     * 按引擎名配置降级链。
     *
     * @param engine "system" | "cloud" | "gpt_sovits"
     * engine=gpt_sovits 时降级链：gpt_sovits → cloud → system
     * engine=cloud     时降级链：cloud → system
     * engine=system    时降级链：system（不降级）
     */
    @Synchronized
    fun configure(ctx: Context, engine: String) {
        shutdown()

        val system = SystemTtsProvider(ctx)
        val cloud = CloudTtsProvider(ctx)
        val gptsovits = RemoteGptSoVitsTtsProvider(ctx)

        when (engine) {
            "gpt_sovits" -> {
                primary = gptsovits
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
     * 朗读：先试 primary，失败按降级链尝试。
     *
     * @param characterName 角色名（可空）；非空时通过 [TtsVoiceRouter] 映射为 voiceId
     * @param voiceOverride 直接指定 voiceId（优先级高于 characterName）
     */
    suspend fun speak(
        ctx: Context,
        text: String,
        utteranceId: String,
        characterName: String? = null,
        voiceOverride: String? = null,
        onStart: () -> Unit = {},
        onDone: (success: Boolean) -> Unit = {}
    ): TtsOutcome = withContext(Dispatchers.IO) {
        val primary = primary ?: return@withContext TtsOutcome.Failed("TTS 未配置")
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

        val chain = listOf(primary) + fallbacks
        var lastOutcome: TtsOutcome = TtsOutcome.Failed("无可用 Provider")

        // 去重保护：onStart 只转发第一次（避免降级时 UI 状态闪烁）
        var onStartFired = false
        // onDone 只在最终结果时转发一次（success=true 成功时，或全部失败后 false）
        var onDoneFired = false
        fun safeStart() {
            if (!onStartFired) {
                onStartFired = true
                onStart()
            }
        }
        fun safeDone(success: Boolean) {
            if (!onDoneFired) {
                onDoneFired = true
                onDone(success)
            }
        }

        for (provider in chain) {
            try {
                val available = provider.isAvailable()
                if (!available) {
                    android.util.Log.w(TAG, "Provider ${provider.id} not available, skip")
                    lastOutcome = TtsOutcome.Failed("Provider ${provider.id} 不可用")
                    continue
                }
                android.util.Log.d(TAG, "Trying provider: ${provider.id}")
                // 每个 provider 按自己的引擎 id 解析 voiceId（避免跨引擎污染）
                // 优先级：voiceOverride > 角色级 tts_voice_<engine>_<character> > 引擎级 tts_voice_<engine> > DEFAULT_VOICE
                val characterStored = if (characterName != null) {
                    prefs.getString(TtsVoiceRouter.prefsKey(provider.id, characterName), null)
                } else null
                val engineStored = prefs.getString(TtsVoiceRouter.enginePrefsKey(provider.id), null)
                val merged = characterStored?.takeIf { it.isNotBlank() }
                    ?: engineStored?.takeIf { it.isNotBlank() }
                val providerVoice = voiceOverride ?: router.resolve(characterName, provider.id, merged)

                val outcome = provider.speak(
                    text = text,
                    voiceId = providerVoice,
                    utteranceId = utteranceId,
                    onStart = { safeStart() },
                    onDone = { success ->
                        // Provider 内部完成时回调；只有 success=true 才立即转发到 UI
                        // failure 时由降级链继续尝试，最终在循环外统一转发一次 onDone(false)
                        if (success) safeDone(true)
                    }
                )
                if (outcome is TtsOutcome.Success) {
                    active = provider
                    // 兜底：如果 provider 没触发 onDone(true)（异常路径），这里补一次
                    safeDone(true)
                    return@withContext outcome
                }
                lastOutcome = outcome
                android.util.Log.w(TAG, "Provider ${provider.id} failed: ${(outcome as TtsOutcome.Failed).reason}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Provider ${provider.id} exception", e)
                lastOutcome = TtsOutcome.Failed("Provider ${provider.id} 异常", e)
            }
        }
        // 全部失败：转发一次 onDone(false) 让 UI 复位
        safeDone(false)
        lastOutcome
    }

    /** 停止当前活跃 Provider 的朗读 */
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
