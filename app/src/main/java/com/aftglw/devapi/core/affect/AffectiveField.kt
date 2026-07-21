package com.aftglw.devapi.core.affect

/**
 * 双向情绪场 — 用户与角色之间的关系状态（设计文档 2.2）。
 *
 * 四个维度：
 * - tension    张力   [-1 和谐, +1 剑拔弩张]
 * - warmth     温度   [-1 冷淡, +1 亲密]
 * - anticipation 期待 [0 平静, +1 焦急]
 * - drift      漂移   [-1 疏远, +1 靠近]
 *
 * P0 范围：
 * - 4 维结构冻结（设计文档 14.11），不引入第 5 维
 * - warmth 由"用户自我披露 + AI 是否回应到位 + 用户后续反馈"共同驱动（设计文档 14.3 第一级）
 * - 衰减采用简单的每周 wake 机制（设计文档 2.2.4）
 *
 * 存储：SharedPreferences `affective_$chatName`，4 个 Float + lastUpdatedTs
 */
data class AffectiveField(
    val tension: Float = 0f,
    val warmth: Float = 0f,
    val anticipation: Float = 0f,
    val drift: Float = 0f,
    /** 上次更新的时间戳（毫秒），用于 wake 衰减计算 */
    val lastUpdatedTs: Long = System.currentTimeMillis(),
) {
    /** 张力级别的人类可读标签，用于 DebugPage 展示 */
    val tensionLabel: String
        get() = when {
            tension > 0.6f -> "剑拔弩张"
            tension > 0.2f -> "紧张"
            tension > -0.2f -> "平和"
            tension > -0.6f -> "和谐"
            else -> "融洽"
        }

    val warmthLabel: String
        get() = when {
            warmth > 0.6f -> "亲密"
            warmth > 0.2f -> "熟络"
            warmth > -0.2f -> "初识"
            warmth > -0.6f -> "疏远"
            else -> "冷淡"
        }

    val anticipationLabel: String
        get() = when {
            anticipation > 0.7f -> "焦急等待"
            anticipation > 0.3f -> "有所期待"
            else -> "平静"
        }

    val driftLabel: String
        get() = when {
            drift > 0.3f -> "靠近中"
            drift > -0.1f -> "稳定"
            drift > -0.5f -> "疏远中"
            else -> "渐行渐远"
        }

    /**
     * 注入 PromptBuilder 的可读块（设计文档 2.2.5）。
     * LLM 拿到这个自然知道该用什么样的语气。
     */
    fun toPromptBlock(): String {
        return buildString {
            append("\n\n【我们之间】")
            append("\n张力：$tensionLabel")
            append("\n温度：$warmthLabel")
            append("\n期待：$anticipationLabel")
            append("\n走向：$driftLabel")
        }
    }

    /**
     * Wake 衰减（设计文档 2.2.4）。
     * - tension ×0.5（隔夜气消）
     * - warmth ×0.95（亲密保持）
     * - anticipation 归零（不再等了）
     * - drift 不衰减（漂移是长期量）
     */
    fun applyWakeDecay(): AffectiveField {
        return copy(
            tension = tension * 0.5f,
            warmth = warmth * 0.95f,
            anticipation = 0f,
            // drift 不变
            lastUpdatedTs = System.currentTimeMillis(),
        )
    }

    companion object {
        /** 从 SharedPreferences 序列化恢复 */
        fun fromPrefs(
            tension: Float,
            warmth: Float,
            anticipation: Float,
            drift: Float,
            lastUpdatedTs: Long,
        ): AffectiveField {
            return AffectiveField(tension, warmth, anticipation, drift, lastUpdatedTs)
        }
    }
}

/** 工具函数：将值 clamp 到 [min, max] */
internal fun clamp(value: Float, min: Float, max: Float): Float {
    return value.coerceIn(min, max)
}
