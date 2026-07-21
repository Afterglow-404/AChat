package com.aftglw.devapi.core.affect

import java.util.UUID

/**
 * 关系事件 — AffectiveField 体系的核心数据载体。
 *
 * 每一轮用户消息 / AI 回复 / 系统触发都封装为一个 RelationshipEvent，
 * 携带幂等 eventId 防止手机端 / Desktop / 服务器重复处理同一条消息（见设计文档 14.8）。
 *
 * P0 范围：仅作为 update() 的入参和持久化记录，不引入复杂的事件溯源。
 */
data class RelationshipEvent(
    /** 幂等键：UUID。同一 eventId 被 AffectiveEngine.update() 处理两次时第二次直接跳过 */
    val eventId: String = UUID.randomUUID().toString(),
    /** 会话 ID：单聊为 chatName，群聊为 groupId（P0 仅支持单聊） */
    val conversationId: String,
    /** 角色 ID：即 chatName / character name */
    val characterId: String,
    /** 毫秒时间戳 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 事件来源 */
    val source: EventSource,
    /** 置信度 0-1：LLM 判定类事件带置信度，本地启发式事件默认 1.0 */
    val confidence: Float = 1.0f,
    /** 事件内容 */
    val payload: EventPayload,
)

enum class EventSource {
    /** 用户发送的消息 */
    USER,
    /** AI 生成的回复 */
    AI,
    /** 系统触发（如 ProactiveScheduler 主动消息、定时衰减等） */
    SYSTEM,
}

/**
 * 事件内容 — sealed class 区分不同事件类型。
 *
 * P0 仅支持 TEXT_MESSAGE；后续扩展 FOLLOWUP_DUE / FIELD_DECAY 等。
 */
sealed class EventPayload {
    /** 文本消息：用户或 AI 的文字内容 */
    data class TextMessage(val text: String) : EventPayload()
}
