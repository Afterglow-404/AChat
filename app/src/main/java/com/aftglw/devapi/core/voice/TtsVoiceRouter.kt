package com.aftglw.devapi.core.voice

/**
 * 角色名 → voiceId 路由（纯逻辑，无 Android 依赖，可单元测试）。
 *
 * 设计动机：
 * - 不同 TTS 引擎的 voiceId 体系不同：
 *   - SystemTts：忽略 voiceId（系统只有一个默认中文音色）
 *   - CloudTts：OpenAI 体系，alloy/echo/fable/onyx/nova/shimmer
 *   - GPT-SoVITS：自由命名，通常用角色名或音色 ID
 * - 用户可在 SharedPreferences 中配置两类覆盖：
 *   - 引擎级：`tts_voice_<engine>`（如 tts_voice_cloud = "alloy"），对该引擎下所有角色生效
 *   - 角色级：`tts_voice_<engine>_<characterName>`（如 tts_voice_gpt_sovits_钦灵），只对指定角色生效
 * - 未配置时回退到硬编码默认音色：每个引擎都有 DEFAULT_VOICE
 *
 * 路由优先级（由调用方 Manager 负责读 prefs 并合并）：
 *   1. 调用方显式 voiceOverride
 *   2. 角色级 storedVoice（已合并引擎级 fallback）
 *   3. 引擎默认 DEFAULT_VOICE
 *
 * 注意：实际读 SharedPreferences 由调用方做（避免本类依赖 Android Context）；
 * 本类只做纯字符串映射 + 提供 prefsKey 命名约定。
 */
object TtsVoiceRouter {

    /** GPT-SoVITS 的 text_lang 可选值（与官方推理接口保持一致）。 */
    val GPT_SOVITS_LANGUAGES = listOf("zh", "en", "ja", "ko", "yue")

    /** 各引擎默认音色（硬编码兜底） */
    val DEFAULT_VOICE = mapOf(
        "system" to "",        // SystemTts 忽略 voiceId
        "cloud" to "alloy",    // OpenAI 默认
        "gpt_sovits" to "default"
    )

    /**
     * 引擎级音色的 SharedPreferences key。
     * 例：tts_voice_cloud、tts_voice_gpt_sovits
     */
    fun enginePrefsKey(engine: String): String = "tts_voice_$engine"

    /**
     * 角色级音色的 SharedPreferences key。
     * 例：tts_voice_cloud_钦灵
     */
    fun prefsKey(engine: String, characterName: String): String =
        "tts_voice_${engine}_${characterName}"

    /** 引擎级语言配置 key，例如 tts_lang_gpt_sovits。 */
    fun languageEnginePrefsKey(engine: String): String = "tts_lang_$engine"

    /** 角色级语言配置 key，例如 tts_lang_gpt_sovits_钦灵。 */
    fun languagePrefsKey(engine: String, characterName: String): String =
        "tts_lang_${engine}_${characterName}"

    /**
     * 纯函数路由：根据引擎 + 已合并的 storedVoice 返回 voiceId。
     *
     * @param characterName   角色名（可空，仅用于日志，不影响逻辑）
     * @param engine          当前引擎 id
     * @param storedVoice     调用方已合并好的覆盖值（角色级 > 引擎级，可空）
     */
    fun resolve(
        characterName: String?,
        engine: String,
        storedVoice: String? = null
    ): String {
        // 1. 用户配置覆盖（角色级或引擎级，由调用方合并）
        if (!storedVoice.isNullOrBlank()) return storedVoice

        // 2. 引擎默认
        return DEFAULT_VOICE[engine] ?: ""
    }

    /**
     * 系统级默认音色（不区分角色）。
     */
    fun defaultFor(engine: String): String = DEFAULT_VOICE[engine] ?: ""

    /** 各引擎默认语言；系统和 OpenAI 云 TTS 不使用此参数。 */
    fun defaultLanguageFor(engine: String): String = if (engine == "gpt_sovits") "zh" else ""

    /** 根据角色级 > 引擎级 > 默认值解析 TTS 语言。 */
    fun resolveLanguage(engine: String, characterLanguage: String?, engineLanguage: String?): String {
        if (engine != "gpt_sovits") return ""
        return characterLanguage?.takeIf { it in GPT_SOVITS_LANGUAGES }
            ?: engineLanguage?.takeIf { it in GPT_SOVITS_LANGUAGES }
            ?: defaultLanguageFor(engine)
    }
}
