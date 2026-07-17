package com.aftglw.devapi.core.mood

import android.content.SharedPreferences

data class AffinityLevel(val name: String, val min: Float, val max: Float)

object AffinityManager {
    val levels = listOf(
        AffinityLevel("厌恶", -100f, -20f),
        AffinityLevel("冷淡", -20f, 0f),
        AffinityLevel("疏远", 0f, 10f),
        AffinityLevel("初识", 10f, 30f),
        AffinityLevel("熟络", 30f, 50f),
        AffinityLevel("亲密", 50f, 70f),
        AffinityLevel("至交", 70f, 101f)
    )

    private fun modeKey(chatName: String) = "affinity_mode_$chatName"
    private fun lockKey(chatName: String) = "affinity_lock_$chatName"
    private fun prefKey(chatName: String) = "affinity_v2_$chatName"

    fun getAffinity(prefs: SharedPreferences, chatName: String = ""): Float {
        val key = prefKey(chatName)
        return prefs.getFloat(key, 0f)
    }

    fun getLevel(affinity: Float): AffinityLevel {
        return levels.last { affinity >= it.min }
    }

    fun isAutoMode(prefs: SharedPreferences, chatName: String): Boolean {
        return prefs.getString(modeKey(chatName), "auto") == "auto"
    }

    fun getLockedLevel(prefs: SharedPreferences, chatName: String): Int {
        return prefs.getInt(lockKey(chatName), 0).coerceIn(0, levels.size - 1)
    }

    fun update(mood: String?, confidence: Float, prefs: SharedPreferences, chatName: String = "") {
        val key = prefKey(chatName)
        val current = prefs.getFloat(key, 0f)
        val delta = when {
            mood == "positive" && confidence > 0.8f -> 0.5f
            mood == "positive" && confidence > 0.6f -> 0.2f
            mood == "positive" -> 0.1f
            mood == "negative" && confidence > 0.8f -> -0.5f
            mood == "negative" && confidence > 0.6f -> -0.3f
            mood == "negative" -> -0.1f
            else -> 0.1f
        }
        prefs.edit().putFloat(key, (current + delta).coerceIn(-100f, 100f)).apply()
    }

    fun applyDecay(prefs: SharedPreferences, hoursSinceLastMsg: Long, chatName: String = "") {
        if (hoursSinceLastMsg > 24) {
            val key = prefKey(chatName)
            val days = (hoursSinceLastMsg / 24).toInt()
            val decay = minOf(days * 0.1f, 5f)
            val current = prefs.getFloat(key, 0f)
            prefs.edit().putFloat(key, (current - decay).coerceIn(-100f, 100f)).apply()
        }
    }

    fun getLevelHint(level: AffinityLevel): String? {
        return when (level.name) {
            "至交" -> "你和对方非常熟悉，像老朋友一样。用短句，多用语气词，可以主动抛话题、开玩笑，不用每句都回应。"
            "亲密" -> "你和对方关系很好。语气轻松亲切，可以适当用语气词，主动关心对方。"
            "熟络" -> "你和对方已经比较熟悉了。保持友好自然的语气，偶尔可以开轻松的玩笑。"
            "初识" -> "你和对方还不太熟悉。保持礼貌友好，句子完整，不越界。"
            "疏远" -> "对方对你比较冷淡。回复简洁克制，不主动延伸话题，保持礼貌距离。"
            "冷淡" -> "你们之间的关系有些疏离。回复礼貌但保持距离，避免亲密语气。"
            "厌恶" -> "你们关系紧张。回复极为克制，简洁礼貌，避免任何亲近的表达。"
            else -> null
        }
    }
}
