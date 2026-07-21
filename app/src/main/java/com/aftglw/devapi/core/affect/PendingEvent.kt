package com.aftglw.devapi.core.affect

/**
 * 未完成事件 — 关系中的"欠债"（设计文档 2.3）。
 *
 * 当用户问句被 AI 漏答、AI 提了话题用户没接、用户道谢 AI 没回应等情况，
 * 生成一个 PendingEvent，由后续对话自然跟进。
 *
 * P0 范围：
 * - 基础 PendingEvent（不含依恋损伤 isAttachmentInjury — 该机制已降级为"疑似关系伤害只记录"，见 14.4.2）
 * - staleness 计算 + 主动收尾触发（设计文档 2.3.4 / 2.3.5）
 * - 软提醒注入 PromptBuilder（设计文档 2.3.5）
 *
 * 存储：Room 表 pending_events（见 PendingEventEntity）
 */
data class PendingEvent(
    val id: String,
    val chatName: String,
    val createdAt: Long,
    /** 一句话概括，如"用户问了考试结果但 AI 没答" */
    val summary: String,
    /** 触发原文（用户消息片段，≤60 字） */
    val triggerText: String,
    /** 重要性 0-1 */
    val weight: Float = 0.5f,
    val closureType: ClosureType,
    /** AI 已尝试收尾次数（避免重复提起） */
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    /** 是否已被收尾（用户回应了相关话题） */
    val resolved: Boolean = false,
    /** 是否归档（staleness 过高且 attemptCount > 0，不再主动提起） */
    val archived: Boolean = false,
) {
    /**
     * staleness 衰减度 0-1（设计文档 2.3.4）。
     * 半衰期 3 天：staleness = 1 - 0.5^(days_since_creation / 3)
     * 7 天后 ≈ 0.84，14 天后 ≈ 0.97
     */
    fun staleness(now: Long = System.currentTimeMillis()): Float {
        val daysSince = ((now - createdAt) / 86_400_000L).toFloat().coerceAtLeast(0f)
        val halfLifeDays = 3f
        return (1f - 0.5f.pow(daysSince / halfLifeDays)).coerceIn(0f, 1f)
    }

    /**
     * 是否该触发主动收尾（设计文档 2.3.5）。
     * - attemptCount == 0（还没提过）
     * - staleness > 0.4（已经"欠"了一阵子）
     * - 距上次尝试 > 6h（避免重复）
     * - weight > 0.3（足够重要）
     * - 未收尾、未归档
     */
    fun shouldTriggerClosure(now: Long = System.currentTimeMillis()): Boolean {
        if (resolved || archived) return false
        if (attemptCount > 0) return false
        if (staleness(now) <= 0.4f) return false
        if (weight <= 0.3f) return false
        val lastTs = lastAttemptAt ?: createdAt
        val hoursSinceAttempt = (now - lastTs) / 3_600_000L
        return hoursSinceAttempt > 6
    }

    /**
     * 是否该归档（设计文档 2.3.4）。
     * staleness > 0.95 且 attemptCount > 0 → 归档
     */
    fun shouldArchive(now: Long = System.currentTimeMillis()): Boolean {
        if (archived) return true
        return staleness(now) > 0.95f && attemptCount > 0
    }

    /**
     * 注入 PromptBuilder 的软提醒（设计文档 2.3.5）。
     * LLM 可以选择提起或不提起，这是软提醒不是强制。
     */
    fun toPromptHint(): String {
        val ageHours = ((System.currentTimeMillis() - createdAt) / 3_600_000L).toInt()
        val ageDesc = when {
            ageHours < 1 -> "刚才"
            ageHours < 24 -> "${ageHours}小时前"
            else -> "${ageHours / 24}天前"
        }
        return "- $ageDesc$summary\n  → 可以这样开头：\"对了，关于你刚才提到的...\""
    }
}

/**
 * 期待收尾方式（设计文档 2.3.2）。
 * P0 不含依恋损伤 APOLOGY 的自动修复轨迹（14.4.2 已降级为"只记录，用户确认才触发"）。
 */
enum class ClosureType {
    /** 解释（AI 没答清楚） */
    EXPLANATION,
    /** 跟进（用户提到"明天/下次"，到了时间该问） */
    FOLLOWUP,
    /** 致谢/确认（用户的好意 AI 没回应） */
    ACKNOWLEDGE,
}

private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
