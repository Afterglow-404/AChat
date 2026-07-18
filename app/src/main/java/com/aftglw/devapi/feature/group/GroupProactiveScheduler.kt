package com.aftglw.devapi.feature.group

import com.aftglw.devapi.model.GroupChatMessage

/**
 * 群聊主动消息触发器 —— 纯逻辑部分可单元测试。
 *
 * 设计：
 * - 仅在用户停留在群聊页面时运行（由 GroupChatScreen 的 LaunchedEffect 驱动）
 * - 满足触发条件时，从「非最近发言人」中随机挑一个成员自发插话
 * - 触发条件：上一条消息来自用户且距上次活动 >= [minIdleMs]；
 *   或上一条消息来自 AI 但距上次活动 >= [longIdleMs]（避免冷场）
 */
object GroupProactiveScheduler {

    /** 用户发言后最少等待毫秒数才允许主动插话（避免抢话） */
    const val MIN_IDLE_MS = 30_000L

    /** AI 发言后允许主动插话的更长等待（避免连发） */
    const val LONG_IDLE_MS = 90_000L

    /** 每次轮询的触发概率 */
    const val TRIGGER_PROBABILITY = 0.35f

    /**
     * 判断是否应触发主动插话。
     *
     * @param messages        当前消息列表
     * @param lastActivityMs  最后一次消息的时间戳（System.currentTimeMillis）
     * @param nowMs           当前时间戳
     * @param probability     本次触发的概率（用于测试覆盖 0/1 边界）
     * @return true 表示应触发
     */
    fun shouldTrigger(
        messages: List<GroupChatMessage>,
        lastActivityMs: Long,
        nowMs: Long,
        probability: Float = TRIGGER_PROBABILITY
    ): Boolean {
        if (messages.isEmpty()) return false
        if (probability <= 0f) return false
        if (probability < 1f && kotlin.random.Random.nextFloat() >= probability) return false
        val idleMs = nowMs - lastActivityMs
        if (idleMs < 0) return false
        val last = messages.last()
        return if (last.isMe) idleMs >= MIN_IDLE_MS else idleMs >= LONG_IDLE_MS
    }

    /**
     * 挑选主动发言人：从 [members] 中排除 [lastSpeaker]（若有），随机选一个。
     * 若排除后为空，返回 null（如群只有 1 人）。
     */
    fun pickSpeaker(members: List<String>, lastSpeaker: String?): String? {
        if (members.isEmpty()) return null
        val candidates = if (lastSpeaker != null) members.filter { it != lastSpeaker } else members
        if (candidates.isEmpty()) return null
        return candidates.random(kotlin.random.Random.Default)
    }

    /**
     * 构建主动插话时给 AI 的系统 prompt。
     */
    fun buildSpontaneousPrompt(groupName: String, memberName: String, members: List<String>): String {
        return buildString {
            appendLine("你在群聊「$groupName」中，你的角色名是 $memberName。")
            appendLine("群成员：${members.joinToString("、")}")
            appendLine("现在没有人说话，但你突然想起一件事 / 想吐槽 / 想分享点什么。")
            appendLine("请以 $memberName 的身份自然插话，1-2 句话即可，不要说'作为AI'。")
            appendLine("可以用括号描述动作和表情，例如【叹气】【兴奋】。")
        }
    }
}
