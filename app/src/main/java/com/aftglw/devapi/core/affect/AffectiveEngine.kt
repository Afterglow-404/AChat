package com.aftglw.devapi.core.affect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 关系引擎 — AffectiveField 体系的核心编排器（设计文档 14.9 P0 最小闭环）。
 *
 * 核心数据流（设计文档第十四章六节）：
 *   用户消息
 *     -> 生成 eventId
 *     -> 更新 RhythmSensor
 *     -> 评估 ResponseAssessment
 *     -> 更新 AffectiveField
 *     -> 扫描 PendingEvents（创建新的 + 检查收尾触发）
 *     -> 持久化
 *
 * 幂等性：每个 eventId 通过 PendingEventStore.checkAndMarkProcessed 去重，
 * 同一 eventId 被 update() 处理两次时第二次直接跳过。
 *
 * LLM 不可用降级：P0 的 AffectiveEngine.update() 完全不依赖 LLM
 * （ResponseAssessment 是 B 类启发式，本地零 token）。
 *
 * 完成标准（设计文档第十四章九节 P0）：
 * - 状态更新正确
 * - 事件不会重复处理（幂等）
 * - Pending 能创建、收尾、归档
 * - LLM 不可用时仍能运行
 * - 所有状态都能在 DebugPage 观察
 */
object AffectiveEngine {
    private const val TAG = "AffectiveEngine"

    /**
     * 处理一轮对话（用户消息 + AI 回复）。
     *
     * 这是从 PostLLMProcessor.process() 调用的主入口。
     * 完整执行：幂等检查 → 韵律采样 → 响应评估 → 场更新 → pending 扫描 → 持久化。
     *
     * @param ctx Context
     * @param chatName 会话名（角色名）
     * @param userMessage 用户消息原文
     * @param aiReply AI 回复原文
     * @param eventId 可选的幂等键；为 null 时自动生成（单端运行时）
     * @return UpdateResult 供调用方（如 PostLLMProcessor）感知本轮状态变化
     */
    suspend fun update(
        ctx: Context,
        chatName: String,
        userMessage: String,
        aiReply: String,
        eventId: String = UUID.randomUUID().toString(),
    ): UpdateResult = withContext(Dispatchers.IO) {
        // ① 幂等检查（设计文档 14.8）
        val shouldProcess = PendingEventStore.checkAndMarkProcessed(ctx, eventId)
        if (!shouldProcess) {
            Log.d(TAG, "eventId $eventId already processed, skipping update")
            return@withContext UpdateResult.skipped()
        }

        // ② 记录韵律样本（设计文档 2.1）
        val prevUserTs = RhythmSensor.getLastSampleTs(ctx, chatName)
        RhythmSensor.recordSample(ctx, chatName, userMessage, prevUserTs)
        val rhythmProfile = RhythmSensor.computeProfile(ctx, chatName)
        val stateHint = RhythmSensor.computeStateHint(rhythmProfile)

        // ③ 评估 AI 响应度（设计文档 14.3 第一级 + P1.1 对话时间线）
        // assessWithTimeline 会读取历史轮次，检测连续漏答/用户后续反馈/重复问
        val assessment = ResponseAssessment.assessWithTimeline(ctx, chatName, userMessage, aiReply, eventId)

        // ④ 更新 AffectiveField（设计文档 2.2）
        val currentField = loadField(ctx, chatName)
        val newField = updateField(currentField, assessment, rhythmProfile)
        saveField(ctx, chatName, newField)

        // ⑤ 扫描 PendingEvents
        // 5a. 创建新的 pending event（如果 AI 漏答或没共情）
        // P1.1：使用 assessment.pendingWeight（连续漏答时提高到 0.7）
        var createdPending: PendingEvent? = null
        if (assessment.shouldCreatePending && assessment.pendingSummary != null && assessment.pendingClosureType != null) {
            createdPending = PendingEventStore.createPendingEvent(
                ctx,
                chatName = chatName,
                summary = assessment.pendingSummary,
                triggerText = userMessage,
                closureType = assessment.pendingClosureType,
                weight = assessment.pendingWeight,
            )
        }

        // 5b. 检查是否该触发主动收尾（软提醒，由 PromptBuilder 注入）
        val activePending = PendingEventStore.getActivePendingEvents(ctx, chatName)
        val closureCandidates = activePending.filter { it.shouldTriggerClosure() }

        // 5c. 检查用户消息是否回应了已有 pending（收尾检测）
        // 简化判定：如果用户消息含 pending 的 triggerText 关键词，视为回应
        for (pending in activePending) {
            if (pending.attemptCount > 0 && !pending.resolved) {
                // 检查用户是否在回应这个 pending
                val triggerKeywords = pending.triggerText.take(10)
                if (triggerKeywords.any { it in userMessage }) {
                    PendingEventStore.markResolved(ctx, pending.id)
                    Log.d(TAG, "pending event resolved by user reply: ${pending.summary}")
                }
            }
        }

        // 5d. 归档过期 pending
        for (pending in activePending) {
            if (pending.shouldArchive()) {
                PendingEventStore.markArchived(ctx, pending.id)
            }
        }

        // ⑥ 定期清理 processed_events（每次 update 顺手检查，低频）
        // 简化：不每次清理，由调用方或 DebugPage 触发

        UpdateResult(
            fieldBefore = currentField,
            fieldAfter = newField,
            warmthDelta = assessment.warmthDelta,
            rhythmStateHint = stateHint,
            createdPendingEvent = createdPending,
            closureCandidates = closureCandidates,
            assessment = assessment,
        )
    }

    /**
     * 快照 — 供 PromptBuilder 和 DebugPage 读取当前状态。
     *
     * 不修改任何状态，纯读取。
     */
    suspend fun snapshot(ctx: Context, chatName: String): Snapshot = withContext(Dispatchers.IO) {
        val field = loadField(ctx, chatName)
        val rhythmProfile = RhythmSensor.computeProfile(ctx, chatName)
        val stateHint = RhythmSensor.computeStateHint(rhythmProfile)
        val activePending = PendingEventStore.getActivePendingEvents(ctx, chatName)
        val closureCandidates = activePending.filter { it.shouldTriggerClosure() }

        Snapshot(
            field = field,
            rhythmProfile = rhythmProfile,
            stateHint = stateHint,
            activePendingEvents = activePending,
            closureCandidates = closureCandidates,
        )
    }

    /**
     * 标记 AI 已尝试收尾某个 pending event。
     * 由 ChatScreen 在 AI 回复后调用（如果本轮回复中包含了 pending 的跟进）。
     */
    suspend fun markClosureAttempted(ctx: Context, pendingId: String) = withContext(Dispatchers.IO) {
        PendingEventStore.markAttempt(ctx, pendingId)
    }

    /**
     * 删除某会话的所有状态（会话被删除时调用）。
     */
    /** Read the group-level field, derived from member fields unless overridden. */
    suspend fun snapshotGroup(
        ctx: Context,
        groupId: String,
        memberNames: List<String>,
    ): GroupSnapshot = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val distinctMembers = memberNames.distinct()
        val memberFields = distinctMembers.mapNotNull { memberName ->
            loadStoredField(prefs, fieldPrefsKey("group_${groupId}_$memberName"))
                ?.let { memberName to it }
        }.toMap()
        val groupKey = groupFieldPrefsKey(groupId)
        val overrideEnabled = prefs.getBoolean("${groupKey}_override", false)
        val field = if (overrideEnabled) loadFieldAtKey(prefs, groupKey)
        else deriveGroupField(memberFields.values)
        GroupSnapshot(
            groupId = groupId,
            memberNames = distinctMembers,
            memberFields = memberFields,
            field = field,
            isOverride = overrideEnabled,
        )
    }

    /** Persist an explicit group-level override for experiments and debugging. */
    suspend fun setGroupFieldOverride(ctx: Context, groupId: String, field: AffectiveField) {
        withContext(Dispatchers.IO) {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val key = groupFieldPrefsKey(groupId)
            saveFieldAtKey(prefs, key, field)
                .putBoolean("${key}_override", true)
                .apply()
        }
    }

    /** Return the group to member-derived mode and remove the stored override. */
    suspend fun clearGroupFieldOverride(ctx: Context, groupId: String) {
        withContext(Dispatchers.IO) {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            removeFieldPrefs(prefs, groupFieldPrefsKey(groupId), removeOverride = true)
        }
    }

    /** Clear group-level state when a group is deleted. */
    suspend fun clearForGroup(ctx: Context, groupId: String) {
        clearGroupFieldOverride(ctx, groupId)
    }

    suspend fun clearForChat(ctx: Context, chatName: String) = withContext(Dispatchers.IO) {
        try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // 清理 AffectiveField
            removeFieldPrefs(prefs, fieldPrefsKey(chatName))
            // 清理 RhythmSensor 样本
            editor.remove("rhythm_$chatName")
            // P2.4d: 清理 proactive 相关残留（原先 clearForChat 未覆盖）
            editor.remove("proactive_last_$chatName")
            editor.remove("proactive_silence_$chatName")
            editor.remove("proactive_tension_dnd_$chatName")
            editor.remove("proactive_gatekeeper_last_$chatName")
            // P2.4 修复: 清理降频/回复统计/最近消息/作息学习残留
            editor.remove("proactive_miss_streak_$chatName")
            editor.remove("proactive_last_reply_$chatName")
            editor.remove("proactive_recent_msgs_$chatName")
            // 清理今日配额（日期通配，需遍历 prefs keys）
            val countPrefix = "proactive_count_${chatName}_"
            prefs.all.keys.filter { it.startsWith(countPrefix) }.forEach { editor.remove(it) }
            // P2.4 修复: 清理作息学习数据（proactive_active_hour_${chat}_$hour, hour 0-23）
            val activeHourPrefix = "proactive_active_hour_${chatName}_"
            prefs.all.keys.filter { it.startsWith(activeHourPrefix) }.forEach { editor.remove(it) }
            // P2.4d: 清理活跃度/情绪残留
            editor.remove("last_active_$chatName")
            editor.remove("last_mood_$chatName")
            // P2.4d: 清理对话特质/反思残留
            editor.remove("persona_dialogue_traits_$chatName")
            editor.remove("reflection_$chatName")
            editor.apply()
            // 清理 PendingEvents
            PendingEventStore.deleteForChat(ctx, chatName)
            // P1.1：清理 ResponseAssessment 时间线
            ResponseAssessment.clearTimeline(ctx, chatName)
            // P2.4d: 清理 ProactiveDecisionLog 中该 chat 的条目（过滤后重写）
            try {
                val allLogs = com.aftglw.devapi.core.time.ProactiveDecisionLog.loadAll(ctx)
                val filtered = allLogs.filter { it.chatName != chatName }
                if (filtered.size != allLogs.size) {
                    com.aftglw.devapi.core.time.ProactiveDecisionLog.clearAll(ctx)
                    filtered.reversed().forEach { com.aftglw.devapi.core.time.ProactiveDecisionLog.append(ctx, it) }
                }
            } catch (_: Exception) { /* 日志清理失败不阻塞 */ }
            // P2.4d: 清理 ProactiveDryRunStore 中该 chat 的条目
            try {
                val allDry = com.aftglw.devapi.core.time.ProactiveDryRunStore.loadAll(ctx)
                val filtered = allDry.filter { it.chatName != chatName }
                if (filtered.size != allDry.size) {
                    com.aftglw.devapi.core.time.ProactiveDryRunStore.clearAll(ctx)
                    filtered.reversed().forEach { com.aftglw.devapi.core.time.ProactiveDryRunStore.append(ctx, it) }
                }
            } catch (_: Exception) { /* 日志清理失败不阻塞 */ }
            // P2.4 修复: 清理 ProactiveApprovalQueue 中该角色的 PENDING/APPROVED 消息
            // 避免删除角色后仍发送已审批的消息
            try {
                com.aftglw.devapi.core.time.ProactiveApprovalQueue.clearForChat(ctx, chatName)
            } catch (_: Exception) { /* 审批队列清理失败不阻塞 */ }
            Log.d(TAG, "cleared all affect state for $chatName (incl proactive/decision/dryrun/approval residuals)")
        } catch (e: Exception) {
            Log.w(TAG, "clearForChat failed", e)
        }
    }

    // ── 内部：AffectiveField 更新逻辑 ──

    /**
     * 更新 AffectiveField（设计文档 2.2.3）。
     *
     * warmth 由"用户自我披露 + AI 是否回应到位"共同驱动（设计文档 14.3 第一级）。
     * tension / anticipation / drift 基于设计文档 2.2.3 的规则。
     */
    private fun updateField(
        current: AffectiveField,
        assessment: ResponseAssessment.Assessment,
        rhythm: RhythmSensor.RhythmProfile,
    ): AffectiveField {
        val now = System.currentTimeMillis()

        // warmth：核心驱动来自 ResponseAssessment
        // tension：用户冲突信号词 + 感叹号（简化版，设计文档 2.2.3）
        // P0 不做完整的关键词检测，仅用 rhythm 信号
        val tensionDelta = when {
            rhythm.sampleCount < 20 -> 0f
            rhythm.latencyPercentile > 0.9f && rhythm.lengthPercentile < 0.2f -> 0.05f  // 极简回复轻微加张力
            else -> 0f
        }
        // anticipation：用户回了就清零（设计文档 2.2.3）
        // update() 被调用意味着用户刚回了消息，所以 anticipation 归零
        val newAnticipation = 0f

        // drift：长期趋势，深聊拉近，不联系疏远（设计文档 2.2.3）
        // P0 简化：warmth 上升时 drift 微升，warmth 下降时 drift 微降
        val driftDelta = when {
            assessment.warmthDelta > 0.05f -> 0.02f
            assessment.warmthDelta < -0.02f -> -0.02f
            else -> 0f
        }
        val newDrift = clamp(current.drift + driftDelta, -1f, 1f)

        // wake 衰减：如果距上次更新 > 7 天，先衰减再更新
        val daysSinceUpdate = (now - current.lastUpdatedTs) / 86_400_000L
        val afterDecay = if (daysSinceUpdate > 7) current.applyWakeDecay() else current

        return AffectiveField(
            tension = clamp(afterDecay.tension + tensionDelta, -1f, 1f),
            warmth = clamp(afterDecay.warmth + assessment.warmthDelta, -1f, 1f),
            anticipation = newAnticipation,
            drift = clamp(afterDecay.drift + driftDelta, -1f, 1f),
            lastUpdatedTs = now,
        )
    }

    // ── 内部：AffectiveField 持久化 ──

    private fun fieldPrefsKey(chatName: String) = "affective_field_$chatName"

    private fun groupFieldPrefsKey(groupId: String) = "affective_field_group_$groupId"

    private fun loadField(ctx: Context, chatName: String): AffectiveField {
        return try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            loadFieldAtKey(prefs, fieldPrefsKey(chatName))
        } catch (e: Exception) {
            Log.w(TAG, "loadField failed, returning default", e)
            AffectiveField()
        }
    }

    private fun saveField(ctx: Context, chatName: String, field: AffectiveField) {
        try {
            val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            saveFieldAtKey(prefs, fieldPrefsKey(chatName), field).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveField failed", e)
        }
    }

    private fun loadFieldAtKey(
        prefs: android.content.SharedPreferences,
        key: String,
    ): AffectiveField = AffectiveField.fromPrefs(
        tension = prefs.getFloat("${key}_tension", 0f),
        warmth = prefs.getFloat("${key}_warmth", 0f),
        anticipation = prefs.getFloat("${key}_anticipation", 0f),
        drift = prefs.getFloat("${key}_drift", 0f),
        lastUpdatedTs = prefs.getLong("${key}_ts", System.currentTimeMillis()),
    )

    private fun loadStoredField(
        prefs: android.content.SharedPreferences,
        key: String,
    ): AffectiveField? {
        if (!prefs.contains("${key}_ts")) return null
        return loadFieldAtKey(prefs, key)
    }

    private fun saveFieldAtKey(
        prefs: android.content.SharedPreferences,
        key: String,
        field: AffectiveField,
    ): android.content.SharedPreferences.Editor {
        return prefs.edit()
            .putFloat("${key}_tension", field.tension)
            .putFloat("${key}_warmth", field.warmth)
            .putFloat("${key}_anticipation", field.anticipation)
            .putFloat("${key}_drift", field.drift)
            .putLong("${key}_ts", field.lastUpdatedTs)
    }

    private fun removeFieldPrefs(
        prefs: android.content.SharedPreferences,
        key: String,
        removeOverride: Boolean = false,
    ) {
        prefs.edit().apply {
            remove("${key}_tension")
            remove("${key}_warmth")
            remove("${key}_anticipation")
            remove("${key}_drift")
            remove("${key}_ts")
            if (removeOverride) remove("${key}_override")
        }.apply()
    }

    private fun deriveGroupField(fields: Collection<AffectiveField>): AffectiveField {
        if (fields.isEmpty()) return AffectiveField()
        return AffectiveField(
            tension = fields.maxOf { it.tension },
            warmth = fields.map { it.warmth }.average().toFloat(),
            anticipation = fields.maxOf { it.anticipation },
            drift = fields.map { it.drift }.average().toFloat(),
            lastUpdatedTs = fields.maxOf { it.lastUpdatedTs },
        )
    }

    // ── 结果数据类 ──

    /**
     * update() 的返回结果，供调用方感知本轮状态变化。
     */
    data class UpdateResult(
        val fieldBefore: AffectiveField,
        val fieldAfter: AffectiveField,
        val warmthDelta: Float,
        val rhythmStateHint: RhythmSensor.StateHint,
        val createdPendingEvent: PendingEvent?,
        val closureCandidates: List<PendingEvent>,
        val assessment: ResponseAssessment.Assessment,
    ) {
        companion object {
            fun skipped(): UpdateResult {
                return UpdateResult(
                    fieldBefore = AffectiveField(),
                    fieldAfter = AffectiveField(),
                    warmthDelta = 0f,
                    rhythmStateHint = RhythmSensor.StateHint("", "", ""),
                    createdPendingEvent = null,
                    closureCandidates = emptyList(),
                    assessment = ResponseAssessment.Assessment(
                        userDisclosed = false,
                        userAskedQuestion = false,
                        userSharedPositive = false,
                        aiRespondedToEmotion = false,
                        aiAnsweredContent = false,
                        aiCelebrated = false,
                        warmthDelta = 0f,
                        shouldCreatePending = false,
                        pendingSummary = null,
                        pendingClosureType = null,
                    ),
                )
            }
        }
    }

    /**
     * snapshot() 的返回结果，供 PromptBuilder 和 DebugPage 读取。
     */
    data class GroupSnapshot(
        val groupId: String,
        val memberNames: List<String>,
        val memberFields: Map<String, AffectiveField>,
        val field: AffectiveField,
        val isOverride: Boolean,
    ) {
        val observedMemberCount: Int get() = memberFields.size
        val sourceLabel: String
            get() = when {
                isOverride -> "手动覆盖"
                observedMemberCount == 0 -> "无数据"
                else -> "成员关系场派生"
            }
    }

    data class Snapshot(
        val field: AffectiveField,
        val rhythmProfile: RhythmSensor.RhythmProfile,
        val stateHint: RhythmSensor.StateHint,
        val activePendingEvents: List<PendingEvent>,
        val closureCandidates: List<PendingEvent>,
    ) {
        /**
         * 构建 PromptBuilder 注入块（设计文档 2.2.5 + 2.3.5 + 14.4.3）。
         * 包含：AffectiveField 块 + stateHint 三层块 + pending 软提醒块 + P1.4 说话方式块。
         */
        fun toPromptBlocks(): String {
            return buildString {
                append(field.toPromptBlock())
                val hint = stateHint.toPromptBlock()
                if (hint.isNotBlank()) append(hint)
                if (closureCandidates.isNotEmpty()) {
                    append("\n\n【未完成的事】（你可以自然提起，但不要硬塞）")
                    closureCandidates.take(2).forEach { pending ->
                        append("\n${pending.toPromptHint()}")
                    }
                }
                // P1.4: 关系场 → 说话方式提示（让 LLM 生成带语气的文本 + 多用/少用【顿】）
                // 不改变角色音色身份（voice_id 由 TtsVoiceRouter 独立路由）
                val ttsHints = AffectiveTtsMapper.fromField(field)
                if (ttsHints.promptBlock.isNotBlank()) append(ttsHints.promptBlock)
            }
        }
    }
}
