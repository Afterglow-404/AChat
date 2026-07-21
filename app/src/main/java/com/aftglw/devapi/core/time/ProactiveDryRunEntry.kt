package com.aftglw.devapi.core.time

import com.aftglw.devapi.core.affect.AffectiveField
import com.aftglw.devapi.core.affect.RhythmSensor

/**
 * ProactiveScheduler dry-run 建议条目（P0.1 新增）。
 *
 * 只读模式：每次 ProactiveWorker 触发时，扫描所有 chat 的 AffectiveField snapshot，
 * 输出"是否建议主动联系 + 原因"，但不实际发送消息。
 *
 * 目的：
 * - 验证 AffectiveField 驱动的决策是否合理（与现有规则驱动的 runOnce 对比）
 * - 在不骚扰用户的前提下积累决策日志，便于后续调优
 * - DebugPage 可观察最近 N 条建议
 *
 * 设计文档第十四章十节验证基础设施 + 14.11 P0 完成标准"所有状态都能在 DebugPage 观察"。
 */
data class ProactiveDryRunEntry(
    /** 扫描时间戳（毫秒） */
    val timestamp: Long,
    /** 角色 / 会话名 */
    val chatName: String,
    /** 是否建议主动联系 */
    val recommendContact: Boolean,
    /** 决策原因（自然语言，供 DebugPage 展示） */
    val reason: String,
    /** 决策时的 AffectiveField 快照 */
    val field: AffectiveField,
    /** RhythmSensor stateHint 观测层（可能为空，展示用） */
    val rhythmObservation: String,
    /** RhythmSensor 结构化信号（调度用，风险 2 修复） */
    val rhythmSignals: Set<RhythmSensor.RhythmSignal>,
    /** 未完成事件数 */
    val pendingCount: Int,
    /** 待收尾事件数（shouldTriggerClosure） */
    val closureCandidateCount: Int,
    /** 距上次用户活跃的毫秒数；-1 表示从未活跃 */
    val msSinceLastActive: Long,
) {
    /** 用于 DebugPage 展示的一行摘要 */
    fun toSummaryLine(): String {
        val recommend = if (recommendContact) "✓ 建议" else "✗ 跳过"
        val gap = if (msSinceLastActive < 0) "从未" else "${msSinceLastActive / 3_600_000L}h"
        return "[$recommend] $chatName · warmth=${"%.2f".format(field.warmth)} · gap=$gap · $reason"
    }
}
