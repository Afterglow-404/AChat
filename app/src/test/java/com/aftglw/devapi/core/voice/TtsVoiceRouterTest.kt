package com.aftglw.devapi.core.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * [TtsVoiceRouter] 纯逻辑测试。
 *
 * 注：[TtsProviderManager] / [SystemTtsProvider] / [CloudTtsProvider] / [RemoteGptSoVitsTtsProvider]
 * 均依赖 Android 运行时（Context / TextToSpeech / MediaPlayer / 网络），无法在纯 JVM 单测中验证；
 * 仅 [TtsVoiceRouter] 为纯逻辑，可单元测试。
 */
class TtsVoiceRouterTest {

    @Test
    fun `prefsKey 格式正确`() {
        assertEquals("tts_voice_cloud_钦灵", TtsVoiceRouter.prefsKey("cloud", "钦灵"))
        assertEquals("tts_voice_gpt_sovits_Alice", TtsVoiceRouter.prefsKey("gpt_sovits", "Alice"))
    }

    @Test
    fun `enginePrefsKey 格式正确`() {
        assertEquals("tts_voice_cloud", TtsVoiceRouter.enginePrefsKey("cloud"))
        assertEquals("tts_voice_gpt_sovits", TtsVoiceRouter.enginePrefsKey("gpt_sovits"))
        assertEquals("tts_voice_system", TtsVoiceRouter.enginePrefsKey("system"))
    }

    @Test
    fun `enginePrefsKey 与角色级 prefsKey 不冲突`() {
        // 引擎级 "tts_voice_cloud" 与角色级 "tts_voice_cloud_shared" 字面不同
        // （角色级有额外的 _<character> 后缀，引擎级没有）
        assertNotEquals(TtsVoiceRouter.enginePrefsKey("cloud"), TtsVoiceRouter.prefsKey("cloud", "shared"))
    }

    @Test
    fun `voiceOverride 非空时直接返回`() {
        assertEquals("custom_voice",
            TtsVoiceRouter.resolve(characterName = "钦灵", engine = "cloud", storedVoice = "custom_voice"))
    }

    @Test
    fun `storedVoice 为空字符串时回退到默认`() {
        // 空字符串视为未配置
        assertEquals("alloy",
            TtsVoiceRouter.resolve(characterName = "钦灵", engine = "cloud", storedVoice = ""))
    }

    @Test
    fun `storedVoice 为 null 时回退到默认`() {
        assertEquals("alloy",
            TtsVoiceRouter.resolve(characterName = "钦灵", engine = "cloud", storedVoice = null))
    }

    @Test
    fun `cloud 引擎默认 alloy`() {
        assertEquals("alloy", TtsVoiceRouter.resolve(null, "cloud"))
    }

    @Test
    fun `gpt_sovits 引擎默认 default`() {
        assertEquals("default", TtsVoiceRouter.resolve(null, "gpt_sovits"))
    }

    @Test
    fun `system 引擎默认空字符串（被 SystemTts 忽略）`() {
        assertEquals("", TtsVoiceRouter.resolve(null, "system"))
    }

    @Test
    fun `未知引擎返回空字符串`() {
        assertEquals("", TtsVoiceRouter.resolve(null, "unknown_engine"))
    }

    @Test
    fun `defaultFor 与 resolve 无 storedVoice 时一致`() {
        for (engine in listOf("system", "cloud", "gpt_sovits")) {
            assertEquals(
                TtsVoiceRouter.defaultFor(engine),
                TtsVoiceRouter.resolve(characterName = null, engine = engine, storedVoice = null)
            )
        }
    }

    @Test
    fun `角色名不影响默认音色（无 storedVoice 时）`() {
        // 不论角色名是什么，无 storedVoice 时都返回引擎默认
        assertEquals("alloy", TtsVoiceRouter.resolve("钦灵", "cloud", null))
        assertEquals("alloy", TtsVoiceRouter.resolve("风雪", "cloud", null))
        assertEquals("alloy", TtsVoiceRouter.resolve(null, "cloud", null))
    }

    @Test
    fun `storedVoice 优先级最高 覆盖所有引擎默认`() {
        assertEquals("my_custom",
            TtsVoiceRouter.resolve("钦灵", "cloud", "my_custom"))
        assertEquals("clone_钦灵",
            TtsVoiceRouter.resolve("钦灵", "gpt_sovits", "clone_钦灵"))
        // system 引擎虽然默认空，但用户配置了也尊重
        assertEquals("zh-CN-Xiaoxiao",
            TtsVoiceRouter.resolve("钦灵", "system", "zh-CN-Xiaoxiao"))
    }

    @Test
    fun `storedVoice 为纯空白字符时回退到默认`() {
        // 空格、制表符等空白也算未配置
        assertEquals("alloy",
            TtsVoiceRouter.resolve("钦灵", "cloud", "   "))
        assertEquals("default",
            TtsVoiceRouter.resolve("钦灵", "gpt_sovits", "\t"))
    }
}

/**
 * [TtsOutcome] 数据行为测试。
 */
class TtsOutcomeTest {

    @Test
    fun `Success 是单例 object`() {
        assertSame(TtsOutcome.Success, TtsOutcome.Success)
    }

    @Test
    fun `Failed 携带原因与异常`() {
        val exc = RuntimeException("boom")
        val f = TtsOutcome.Failed("合成失败", exc)
        assertEquals("合成失败", f.reason)
        assertSame(exc, f.cause)
    }

    @Test
    fun `Failed 的 cause 可为空`() {
        val f = TtsOutcome.Failed("网络不可用")
        assertEquals("网络不可用", f.reason)
        assertNull(f.cause)
    }
}
