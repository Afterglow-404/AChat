package com.aftglw.devapi.core.mood

import com.aftglw.devapi.core.character.BuiltInCharacterLoader

/**
 * 情绪 → 情绪头像文件名映射。
 *
 * MoodDetector 输出的 mood 已经是 normalized 后的 7 类（开心/悲伤/愤怒/害怕/惊讶/厌恶/中性）。
 * 这里映射到内置角色 avatar 目录下的文件名。
 *
 * 头像文件名清单（19 张）：
 *   伤心/兴奋/厌恶/害怕/害羞/心动/惊讶/慌张/担心/无奈/正常/生气/疑惑/紧张/羞耻/自信/认真/调皮/高兴
 */
object MoodAvatarMapper {

    /** normalized mood → 头像文件名（不含扩展名） */
    private val moodToAvatar = mapOf(
        "开心" to "高兴",
        "悲伤" to "伤心",
        "愤怒" to "生气",
        "害怕" to "害怕",
        "惊讶" to "惊讶",
        "厌恶" to "厌恶",
        "中性" to "正常"
    )

    /** 根据当前情绪返回 asset:// 头像路径，无匹配时返回 null（调用方用默认头像） */
    fun resolve(folder: String, mood: String?): String? {
        if (folder.isBlank() || mood == null) return null
        val avatarName = moodToAvatar[mood] ?: return null
        return BuiltInCharacterLoader.getMoodAvatarUri(folder, avatarName)
    }

    /** 内置角色 + 无情绪时使用的默认头像（"正常"） */
    fun defaultAvatar(folder: String): String {
        return BuiltInCharacterLoader.getMoodAvatarUri(folder, "正常")
    }
}
