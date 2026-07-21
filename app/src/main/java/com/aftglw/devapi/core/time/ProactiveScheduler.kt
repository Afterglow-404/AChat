package com.aftglw.devapi.core.time

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aftglw.devapi.MainActivity
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.mood.AffinityManager
import com.aftglw.devapi.core.storage.ChatHistory
import com.aftglw.devapi.core.storage.room.AppDatabase
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ProactiveScheduler {
    fun enqueue(context: Context) {
        // app 启动时调用：用 REPLACE 策略确保每次启动都会跑一次（取消 pending 的周期任务）。
        // runOnce 末尾的 finally 会重新 enqueue 30min 周期任务，因此这里覆盖不会丢失后续调度。
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "proactive",
            ExistingWorkPolicy.REPLACE,
            request
        )
        // P2.1: 启动审批队列轮询协程（拉取 Desktop 决策 + 发送已批准消息）
        startApprovalPoller(context)
        // P2.1: 同步上次 app 期间未同步的 pending 到 Desktop
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ProactiveApprovalQueue.syncPendingToDesktop(context)
            } catch (e: Exception) {
                Log.w(TAG, "syncPendingToDesktop on startup failed", e)
            }
        }
    }

    fun triggerNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch { runOnce(context) }
    }

    // ── P2.1: 审批队列轮询协程 ──

    private val pollerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val approvedSendMutex = Mutex()
    @Volatile private var pollerStarted = false

    /**
     * 启动审批队列轮询：定期拉取 Desktop 决策 + 发送已批准但未发送的消息。
     *
     * 周期：60s（短于 Worker 的 30min 周期，确保审批通过后能及时发送）
     * 幂等：pollerStarted 标志位确保只启动一次
     */
    private fun startApprovalPoller(context: Context) {
        if (pollerStarted) return
        pollerStarted = true
        pollerScope.launch {
            while (true) {
                try {
                    // 1. 拉取 Desktop 决策并应用（APPROVE → status=APPROVED）
                    ProactiveApprovalQueue.pollDesktopDecisions(context)
                    // 2. 发送所有已批准但未发送的消息
                    sendApprovedMessages(context)
                } catch (e: Exception) {
                    Log.w(TAG, "approval poller cycle failed", e)
                }
                kotlinx.coroutines.delay(60_000L)  // 60s 周期
            }
        }
    }

    /**
     * 发送所有 APPROVED 状态的消息。
     *
     * 调用时机：
     * - startApprovalPoller 每 60s 一次
     * - DebugPage 本地批准后立即触发（通过 triggerSendApproved）
     *
     * 内部行为（每条 APPROVED 消息）：
     * 1. ChatHistory.appendMessage（入库）
     * 2. sendNotif（通知）
     * 3. ProactiveMessageStore.recordSentMessage / markAnyChatSent / incrMissStreak
     * 4. 更新 proactive_count / proactive_last
     * 5. ProactiveApprovalQueue.markSent（更新队列状态）
     *
     * 失败处理：单条发送失败不阻塞其他条目，标记为 SENT 后从队列移除（避免无限重试）
     */
    private suspend fun sendApprovedMessages(context: Context) {
        approvedSendMutex.withLock {
            sendApprovedMessagesLocked(context)
        }
    }

    private suspend fun sendApprovedMessagesLocked(context: Context) {
        val approved = ProactiveApprovalQueue.listApprovedUnsent(context)
        if (approved.isEmpty()) return
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        for (msg in approved) {
            try {
                // 二次检查 Gatekeeper（防止审批期间状态变化，如用户主动设置了免打扰）
                val gate = ProactiveGatekeeper.decide(context, msg.chatName)
                if (!gate.allow) {
                    // 审批已通过但 gatekeeper 此刻拒绝 → 取消本次发送（保留 APPROVED 状态等待下次轮询重试？）
                    // 简化策略：直接标记为 CANCELLED（决策已变化，重新生成更好）
                    ProactiveApprovalQueue.cancelByGatekeeper(
                        context,
                        msg.id,
                        reason = gate.reason,
                    )
                    Log.i(TAG, "Cancelled approved msg ${msg.id}: gatekeeper re-check denied (${gate.reason})")
                    continue
                }
                // 实际发送
                ChatHistory.appendMessage(
                    context, msg.chatName,
                    com.aftglw.devapi.model.ChatMessage(
                        role = "assistant",
                        content = msg.content,
                        sourceEventId = msg.id,
                    ),
                )
                sendNotif(context, msg.chatName, msg.content)
                ProactiveMessageStore.recordSentMessage(context, msg.chatName, msg.content)
                ProactiveMessageStore.markAnyChatSent(context)
                ProactiveMessageStore.incrMissStreak(context, msg.chatName)

                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val todayCount = prefs.getInt("proactive_count_${msg.chatName}_$today", 0)
                prefs.edit()
                    .putInt("proactive_count_${msg.chatName}_$today", todayCount + 1)
                    .putLong("proactive_last_${msg.chatName}", System.currentTimeMillis())
                    .apply()
                ProactiveApprovalQueue.markSent(context, msg.id)
                Log.i(TAG, "Sent approved msg ${msg.id} (chat=${msg.chatName})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send approved msg ${msg.id}", e)
                // 标记为 SENT 避免无限重试；如需重发由用户在 DebugPage 手动操作
                ProactiveApprovalQueue.markSent(context, msg.id)
            }
        }
    }

    /** DebugPage 用：本地批准后立即触发一次发送（不必等 60s 轮询） */
    fun triggerSendApproved(context: Context) {
        pollerScope.launch { sendApprovedMessages(context) }
    }

    /** 仅供 UI 测试：生成一条消息但不入库不发通知，返回文本供 Toast 预览 */
    suspend fun generateMessagePublic(ctx: Context, chatName: String): String? = try {
        generateMessage(ctx, chatName).ifBlank { null }
    } catch (e: Exception) { Log.w(TAG, "preview failed", e); null }

    fun forceSend(context: Context, chatName: String) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = "Test message ($now)"
        CoroutineScope(Dispatchers.IO).launch {
            // 单条 append 避免全量 load+save 覆盖并发写入
            ChatHistory.appendMessage(context, chatName, com.aftglw.devapi.model.ChatMessage("assistant", message))
        }
        sendNotif(context, chatName, message)
    }

    suspend fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("worker_last_run", System.currentTimeMillis()).apply()
        // 风险 1 防护：dry-run only 模式下，只跑 dryRunScan（已在 Worker 中跑过），不进入实际发送循环
        if (prefs.getBoolean("proactive_dry_run_only", false)) {
            Log.i(TAG, "runOnce skipped: proactive_dry_run_only=true (dry-run mode)")
            ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                timestamp = System.currentTimeMillis(), chatName = "",
                node = ProactiveDecisionLog.Node.DRY_RUN_ONLY, action = "SKIP",
                reason = "dry-run only 模式",
            ))
            return
        }
        try {
            // 全局凌晨静默：1-4 点不触发任何主动消息（避免夜间骚扰）
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 1..4) {
                ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                    timestamp = System.currentTimeMillis(), chatName = "",
                    node = ProactiveDecisionLog.Node.NIGHT_SILENCE, action = "SKIP",
                    reason = "凌晨 1-4 点静默（当前 ${hour} 时）",
                ))
                return
            }
            // P0-3: 全局勿扰检查
            if (ProactiveMessageStore.isGlobalPaused(context)) {
                ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                    timestamp = System.currentTimeMillis(), chatName = "",
                    node = ProactiveDecisionLog.Node.GLOBAL_PAUSED, action = "SKIP",
                    reason = "全局勿扰已开启",
                ))
                return
            }
            // P1-1: 跨角色协调 — 30 分钟内已有任意角色发过消息，本轮跳过
            if (ProactiveMessageStore.shouldSkipByCrossChat(context)) {
                ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                    timestamp = System.currentTimeMillis(), chatName = "",
                    node = ProactiveDecisionLog.Node.CROSS_CHAT, action = "SKIP",
                    reason = "30 分钟内已有其他角色发过消息",
                ))
                return
            }

            for (item in AppDatabase.get(context).chatDao().getAll()) {
                val chatDisplay = item.name
                val chat = item.id.ifEmpty { chatDisplay }
                val now = System.currentTimeMillis()
                if (!prefs.getBoolean("proactive_enabled_$chat", false)) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.NOT_ENABLED, action = "SKIP",
                        reason = "主动消息未开启",
                    ))
                    continue
                }
                if (now < prefs.getLong("proactive_silence_$chat", 0L)) {
                    val remainMin = (prefs.getLong("proactive_silence_$chat", 0L) - now) / 60_000L
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.SILENCE, action = "SKIP",
                        reason = "免打扰中，剩余 ${remainMin}min",
                    ))
                    continue
                }

                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val todayCount = prefs.getInt("proactive_count_${chat}_$today", 0)
                if (todayCount >= prefs.getInt("proactive_daily_limit_$chat", 10)) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.DAILY_LIMIT, action = "SKIP",
                        reason = "今日配额已满（$todayCount/${prefs.getInt("proactive_daily_limit_$chat", 10)}）",
                    ))
                    continue
                }

                // P1-4: 用户 30 分钟内发过消息则跳过
                val lastActive = prefs.getLong("last_active_$chat", 0L)
                val msSinceLastActive = if (lastActive > 0) now - lastActive else -1L
                if (lastActive > 0 && now - lastActive < 30 * 60_000L) {
                    val minAgo = (now - lastActive) / 60_000L
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.USER_ACTIVE_30MIN, action = "SKIP",
                        reason = "用户 ${minAgo}min 内活跃过", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P0-1: 连续未回复降频
                if (ProactiveMessageStore.shouldSkipByMissStreak(context, chat)) {
                    val streak = ProactiveMessageStore.getMissStreak(context, chat)
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.MISS_STREAK, action = "SKIP",
                        reason = "连续未回复 ${streak} 次，降频跳过", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P2-3: 用户作息学习 — 当前小时不在用户活跃时段则跳过
                if (!ProactiveMessageStore.isUserActiveHour(context, chat)) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.NOT_ACTIVE_HOUR, action = "SKIP",
                        reason = "当前小时不在用户活跃时段", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                val mode = prefs.getString("proactive_trigger_mode_$chat", "custom") ?: "custom"
                val shouldTrigger = if (mode == "ai") {
                    true
                } else {
                    shouldTrigger(prefs, chat)
                }
                if (!shouldTrigger) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.SHOULD_TRIGGER, action = "SKIP",
                        reason = "触发条件未达（mode=$mode）", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P1.2: ProactiveGatekeeper 审批（AffectiveField + 动态冷却 + 临时免打扰）
                val gateDecision = ProactiveGatekeeper.decide(context, chat)
                if (!gateDecision.allow) {
                    Log.i(TAG, "gatekeeper denied $chat: ${gateDecision.reason}")
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.GATEKEEPER, action = "SKIP",
                        reason = "gatekeeper: ${gateDecision.reason}", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P1-2: 事件驱动 — 检查今天是否有应跟进的事件，有则强制触发一次
                val dueEvents = ProactiveMessageStore.getDueEvents(context, chat)
                val message = if (dueEvents.isNotEmpty()) {
                    generateEventFollowupMessage(context, chat, dueEvents.first())
                } else {
                    generateMessage(context, chat)
                }
                // 用空字符串表示"不发"；只判 isBlank
                if (message.isBlank()) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.MESSAGE_BLANK, action = "SKIP",
                        reason = "生成的消息为空（LLM 未产出内容）", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P1-4: 去重 — 若与最近 5 条主动消息相似度 >60% 则跳过本轮
                if (ProactiveMessageStore.isDuplicate(context, chat, message)) {
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.DUPLICATE, action = "SKIP",
                        reason = "与最近 5 条消息相似度 >60%", msSinceLastActive = msSinceLastActive,
                    ))
                    continue
                }

                // P2.1: 三段式审批流 — 默认进入审批队列，不直接发送
                val approvalRequired = prefs.getBoolean("proactive_approval_required", true)
                if (approvalRequired) {
                    val snapshot = try {
                        com.aftglw.devapi.core.affect.AffectiveEngine.snapshot(context, chat)
                    } catch (_: Exception) { null }
                    val pendingMsg = ProactiveApprovalQueue.PendingMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        chatName = chat,
                        content = message,
                        createdAt = System.currentTimeMillis(),
                        gatekeeperReason = gateDecision.reason,
                        warmth = snapshot?.field?.warmth ?: 0f,
                        tension = snapshot?.field?.tension ?: 0f,
                        msSinceLastActive = msSinceLastActive,
                    )
                    ProactiveApprovalQueue.enqueue(context, pendingMsg)
                    prefs.edit()
                        .putLong("proactive_last_$chat", System.currentTimeMillis())
                        .apply()
                    Log.i(TAG, "Enqueued for approval: $chat (id=${pendingMsg.id})")
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.ENQUEUE, action = "ENQUEUE",
                        reason = "已入队审批（id=${pendingMsg.id.take(8)}）",
                        warmth = snapshot?.field?.warmth ?: 0f,
                        tension = snapshot?.field?.tension ?: 0f,
                        msSinceLastActive = msSinceLastActive,
                    ))
                } else {
                    ChatHistory.appendMessage(context, chat, com.aftglw.devapi.model.ChatMessage("assistant", message))
                    sendNotif(context, chatDisplay, message)
                    ProactiveMessageStore.recordSentMessage(context, chat, message)
                    ProactiveMessageStore.markAnyChatSent(context)
                    ProactiveMessageStore.incrMissStreak(context, chat)
                    prefs.edit()
                        .putInt("proactive_count_${chat}_$today", todayCount + 1)
                        .putLong("proactive_last_$chat", System.currentTimeMillis())
                        .apply()
                    ProactiveDecisionLog.append(context, ProactiveDecisionLog.DecisionEntry(
                        timestamp = now, chatName = chat,
                        node = ProactiveDecisionLog.Node.SENT_DIRECT, action = "SENT",
                        reason = "直接发送（approval_required=false）",
                        msSinceLastActive = msSinceLastActive,
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proactive trigger failed", e)
        } finally {
            // P0: 周期触发 —— 本轮跑完后重新 enqueue 自己
            val nextRequest = OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "proactive",
                ExistingWorkPolicy.KEEP,
                nextRequest
            )
        }
    }

    private fun shouldTrigger(prefs: android.content.SharedPreferences, chat: String): Boolean {
        val checkMode = prefs.getString("proactive_check_mode_$chat", "random") ?: "random"
        if (checkMode == "random" && kotlin.random.Random.nextFloat() > 0.4f) return false
        val affinity = AffinityManager.getAffinity(prefs, chat)
        val minLevel = prefs.getInt("proactive_affinity_min_$chat", 0)
        if (AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) < minLevel) return false

        val lastActive = prefs.getLong("last_active_$chat", 0L)
        val hoursSince = if (lastActive > 0) {
            (System.currentTimeMillis() - lastActive) / 3_600_000L
        } else {
            Long.MAX_VALUE
        }
        val lastProactive = prefs.getLong("proactive_last_$chat", 0L)
        val interval = prefs.getInt("proactive_interval_min_$chat", 30).coerceAtLeast(1)
        val minimumDelay = if (checkMode == "fixed") interval * 60_000L else 7_200_000L
        if (System.currentTimeMillis() - lastProactive < minimumDelay) return false

        val idleHours = prefs.getInt("proactive_idle_hours_$chat", 0)
        val triggers = prefs.getString("proactive_triggers_$chat", "1,2,3,4,5,6")
            ?.split(',') ?: emptyList()
        return triggers.any { trigger ->
            when (trigger.trim()) {
                "1" -> idleHours == 0 || hoursSince >= idleHours
                "2" -> {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    hour in 1..4 && hoursSince < 12
                }
                "3" -> hoursSince < 3
                "4" -> hoursSince >= 24
                "5" -> AffinityManager.levels.indexOf(AffinityManager.getLevel(affinity)) >= 3 && hoursSince < 6
                "6" -> hoursSince >= 72
                else -> false
            }
        }
    }

    private suspend fun generateMessage(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        if (prefs.getString("proactive_trigger_mode_$chatName", "custom") == "ai") {
            return generateMessageAiDriven(ctx, chatName)
        }

        val apiKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key")
        if (apiKey.isBlank()) {
            // P0-2: API 未配置 → 走模板 fallback
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val mood = prefs.getString("last_mood_$chatName", null)
            return ProactiveMessageStore.pickPresetTemplate(hour, mood)
        }
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        val messages = history.map {
            com.aftglw.devapi.model.ChatMessage(if (it.second) "user" else "assistant", it.first)
        }
        // P1-3: 用户情绪适配
        val userMood = prefs.getString("last_mood_$chatName", null)
        val moodHint = if (userMood != null) {
            val lowMoodSet = setOf("低落", "难过", "沮丧", "疲惫", "累", "烦")
            if (lowMoodSet.any { userMood.contains(it) }) "【对方最近情绪】$userMood — 语气要更温柔、关心，不要嬉皮笑脸" else ""
        } else ""
        // P1-4: 注入最近主动消息文本，让 AI 知道不要重复
        val recentSent = ProactiveMessageStore.getRecentSent(ctx, chatName, 3)
        val recentHint = if (recentSent.isNotEmpty()) "【你最近主动发过的消息（不要重复类似内容）】\n${recentSent.joinToString("\n")}" else ""

        val insightText = try { MemoryStore.listRecentByTopic("insight:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val aiEmoText = try { MemoryStore.listRecentByTopic("ai_emo:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val now = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val lastActive = prefs.getLong("last_active_$chatName", 0L)
        val gapHint = if (lastActive > 0L) {
            val mins = (System.currentTimeMillis() - lastActive) / 60_000L
            when {
                mins < 60 -> "距上次聊天 ${mins} 分钟"
                mins < 1440 -> "距上次聊天 ${mins / 60} 小时"
                else -> "距上次聊天 ${mins / 1440} 天"
            }
        } else "之前没聊过"
        val prompt = buildString {
            if (persona.isNotBlank()) append(persona).append("\n\n")
            append("【当前时间】$now\n")
            append("【距离上次聊天】$gapHint\n")
            if (insightText.isNotBlank()) append("【上次对话本质】$insightText\n")
            if (aiEmoText.isNotBlank()) append("【你上次的情绪】$aiEmoText\n")
            if (moodHint.isNotBlank()) append(moodHint).append('\n')
            if (recentHint.isNotBlank()) append(recentHint).append('\n')
            append("\n你现在要主动给对方发一条消息，像朋友自然地打招呼或关心一下。")
            append("要求：1-2 句话，每句不超过 15 字；不要提及\"我刚想起来\"\"我主动\"等元描述；不要重复上次说过的话。")
            append("如果没什么好说的，就回复空白。")
        }
        // P2-4: LLM 失败时重试 1 次；若仍失败则走模板 fallback
        return try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(messages, "主动发起一条自然的消息", prompt)
            if (reply.isNullOrBlank()) {
                // 第二次尝试：用简化 prompt
                val reply2 = try {
                    com.aftglw.devapi.network.AiServiceFactory.getService()
                        .sendMessage(messages.takeLast(4), "主动发一条关心的消息", "现在主动给对方发一句话。")
                } catch (_: Exception) { null }
                if (reply2.isNullOrBlank()) {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    ProactiveMessageStore.pickPresetTemplate(hour, userMood)
                } else reply2
            } else reply
        } catch (e: Exception) {
            Log.w(TAG, "Message generation failed, using template", e)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            ProactiveMessageStore.pickPresetTemplate(hour, userMood)
        }
    }

    /** P1-2: 事件跟进消息 — 用户之前提到"明天XX"，今天主动跟进 */
    private suspend fun generateEventFollowupMessage(ctx: Context, chatName: String, eventText: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val apiKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key")
        if (apiKey.isBlank()) {
            // 简化 fallback
            return "你之前提到的「${eventText.take(15)}」怎么样了？"
        }
        val persona = loadPersona(ctx, chatName)
        val prompt = buildString {
            if (persona.isNotBlank()) append(persona).append("\n\n")
            append("【情境】对方之前跟你提过：$eventText\n")
            append("现在该到时间了，你主动关心一下这件事怎么样了。\n")
            append("要求：1-2 句话，每句 ≤15 字；自然口语，不要像机器提醒。")
        }
        return try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "你是这个对话中的角色。主动跟进对方之前提到的事。")
            reply?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: "之前那件事怎么样了？"
        } catch (e: Exception) {
            Log.w(TAG, "event followup failed", e)
            "你之前提到的「${eventText.take(15)}」怎么样了？"
        }
    }

    private suspend fun generateMessageAiDriven(ctx: Context, chatName: String): String {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val persona = loadPersona(ctx, chatName)
        val history = ChatHistory.load(ctx, chatName)
        // AI 模式：让模型自己决定是否要发消息；同时给足上下文（反思产物 + 时间 + 间隔）
        val insightText = try { MemoryStore.listRecentByTopic("insight:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val aiEmoText = try { MemoryStore.listRecentByTopic("ai_emo:$chatName", 1).firstOrNull()?.text ?: "" } catch (_: Exception) { "" }
        val memo = try { MemoryStore.listRecentByTopic("turn:$chatName", 3).joinToString("\n") { "- ${it.text}" } } catch (_: Exception) { "" }
        val now = SimpleDateFormat("MM-dd HH:mm E", Locale.CHINESE).format(Date())
        val lastActive = prefs.getLong("last_active_$chatName", 0L)
        val gapHint = if (lastActive > 0L) {
            val mins = (System.currentTimeMillis() - lastActive) / 60_000L
            when {
                mins < 60 -> "${mins} 分钟前"
                mins < 1440 -> "${mins / 60} 小时前"
                else -> "${mins / 1440} 天前"
            }
        } else "很久没聊"
        val prompt = buildString {
            append("你要决定是否主动给对方发一条消息。\n\n")
            append("【当前时间】$now\n")
            append("【上次聊天】$gapHint\n")
            if (persona.isNotBlank()) append("【你的人设】\n").append(persona).append("\n\n")
            append("【最近对话】\n")
            history.takeLast(6).forEach { append(if (it.second) "对方: " else "你: ").append(it.first.take(80)).append('\n') }
            if (memo.isNotBlank()) append("\n【记忆碎片】\n").append(memo).append('\n')
            if (insightText.isNotBlank()) append("\n【上次对话本质】").append(insightText).append('\n')
            if (aiEmoText.isNotBlank()) append("【你上次情绪】").append(aiEmoText).append('\n')
            append("\n请判断：现在主动发消息是否合适？")
            append("\n- 如果不合适（如刚聊完、深夜、没话说），输出空白一行")
            append("\n- 如果合适，直接输出消息内容（1-2 句话，每句 ≤15 字），不要任何解释或前缀")
        }
        return try {
            val reply = com.aftglw.devapi.network.AiServiceFactory.getService()
                .sendMessage(emptyList(), prompt, "你是这个对话中的角色本人。只输出消息内容或空白。")
            reply?.trim()?.takeIf { it.isNotBlank() }?.take(100) ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "AI proactive generation failed", e)
            ""
        }
    }

    private suspend fun loadPersona(ctx: Context, chatName: String): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).chatDao()
        val entity = dao.getById(chatName) ?: dao.getAll().firstOrNull { it.name == chatName }
        entity?.persona ?: ""
    }

    private fun sendNotif(ctx: Context, chat: String, message: String) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("proactive", "主动消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "AI角色主动发起的关怀消息"
                }
            )
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("open_chat", chat)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, chat.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // P2-1: 通知 Action — 稍后免打扰 1h
        val silenceIntent = Intent(ctx, ProactionNotificationReceiver::class.java).apply {
            action = "com.aftglw.devapi.PROACTIVE_SILENCE_1H"
            putExtra("chat", chat)
        }
        val silencePending = PendingIntent.getBroadcast(
            ctx, chat.hashCode() + 1, silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionSilence = android.app.Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "免打扰1小时", silencePending
        ).build()

        // P2-2: 隐私模式 — 只显示"发来一条消息"，不显示全文
        val (title, text) = if (ProactiveMessageStore.isPrivacyMode(ctx)) {
            chat to "发来一条消息"
        } else {
            chat to message
        }

        val builder = android.app.Notification.Builder(ctx, "proactive")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(actionSilence)
        // 隐私模式锁屏不显示全文
        if (ProactiveMessageStore.isPrivacyMode(ctx)) {
            builder.setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
        }
        manager.notify(chat.hashCode(), builder.build())
    }

    // ── P0.1 dry-run：基于 AffectiveField 的只读建议（不发送消息）──
    //
    // **隔离边界（风险 1 防护）**：
    // dryRunScan 是纯只读操作，绝不调用以下方法：
    // - ChatHistory.appendMessage / ChatHistory.save（不入库）
    // - sendNotif（不通知）
    // - ProactiveMessageStore.recordSentMessage / markAnyChatSent / incrMissStreak（不改发送状态）
    // - prefs.edit().put*("proactive_*")（不改主动消息相关 prefs）
    //
    // 它只写入 ProactiveDryRunStore（独立命名空间 proactive_dry_run_log）。
    // runOnce 不读取 ProactiveDryRunStore，二者数据流完全隔离。
    //
    // 全局开关 `proactive_dry_run_only`：
    // 当开启时，runOnce 在进入主循环前直接 return，只跑 dryRunScan。
    // 用于用户验证 dry-run 决策合理之前，完全关闭实际发送。

    /**
     * 扫描所有 chat，基于 AffectiveField snapshot 输出"是否建议主动联系"建议。
     *
     * **只读**：不调用 ChatHistory.appendMessage，不调用 sendNotif，
     * 仅写入 [ProactiveDryRunStore] 供 DebugPage 观察。
     *
     * 决策规则（基于设计文档第十四章 P0 范围，低风险保守版）：
     * - **建议主动联系** 当且仅当满足以下任一条件且不命中任何禁止条件：
     *   - warmth > 0.2 且距上次用户活跃 > 6h（关系亲密且久未聊）
     *   - 有 closureCandidates（待收尾事件，可自然提起）
     * - **禁止主动联系**（推荐=false，优先级高于建议条件）：
     *   - warmth < -0.2（关系冷淡，让用户先发起）
     *   - tension > 0.4（关系紧张，避免施压）
     *   - RhythmSensor signals 含 LATENCY_INCREASING 或 INITIATIVE_DROPPING（结构化信号，风险 2 修复）
     *   - 距上次用户活跃 < 2h（刚聊过，无需主动）
     *
     * 注意：dry-run 与 [runOnce] 完全独立，不影响实际发送决策。
     * 二者并行运行，便于对比"AffectiveField 驱动"与"现有规则驱动"的差异。
     */
    suspend fun dryRunScan(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        try {
            val chats = AppDatabase.get(context).chatDao().getAll()
            for (item in chats) {
                val chatName = item.id.ifEmpty { item.name }
                val entry = computeDryRunEntry(context, chatName, now, prefs)
                if (entry != null) {
                    ProactiveDryRunStore.append(context, entry)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dryRunScan failed", e)
        }
    }

    /** 计算单个 chat 的 dry-run 建议；chat 从未被活跃过时返回 null（无足够数据） */
    private suspend fun computeDryRunEntry(
        ctx: Context,
        chatName: String,
        now: Long,
        prefs: android.content.SharedPreferences,
    ): ProactiveDryRunEntry? {
        // 距上次用户活跃
        val lastActive = prefs.getLong("last_active_$chatName", 0L)
        val msSinceLastActive = if (lastActive > 0) now - lastActive else -1L

        // AffectiveField snapshot
        val snapshot = try {
            com.aftglw.devapi.core.affect.AffectiveEngine.snapshot(ctx, chatName)
        } catch (e: Exception) {
            Log.w(TAG, "snapshot failed for $chatName", e)
            return null
        }
        val field = snapshot.field
        val rhythmObs = snapshot.stateHint.observation
        val pendingCount = snapshot.activePendingEvents.size
        val closureCount = snapshot.closureCandidates.size

        // ── 决策 ──
        // 禁止条件（优先级高）
        val tooSoon = msSinceLastActive in 0..<2 * 3_600_000L  // < 2h
        val tooCold = field.warmth < -0.2f
        val tooTense = field.tension > 0.4f
        // 风险 2 修复：改用结构化 signals，而非中文文案匹配
        val userSlowingDown = com.aftglw.devapi.core.affect.RhythmSensor.RhythmSignal.LATENCY_INCREASING in snapshot.stateHint.signals
            || com.aftglw.devapi.core.affect.RhythmSensor.RhythmSignal.INITIATIVE_DROPPING in snapshot.stateHint.signals
        val neverInteracted = msSinceLastActive < 0 && field.lastUpdatedTs == 0L
        if (neverInteracted) {
            return null  // 无足够数据
        }

        // 建议条件
        val warmAndIdle = field.warmth > 0.2f && msSinceLastActive > 6 * 3_600_000L
        val hasClosure = closureCount > 0

        val (recommend, reason) = when {
            tooSoon -> false to "刚聊过 ${(msSinceLastActive / 60_000L)}min，无需主动"
            tooTense -> false to "关系紧张(tension=${"%.2f".format(field.tension)})，让用户先发起"
            tooCold -> false to "关系冷淡(warmth=${"%.2f".format(field.warmth)})，避免突兀打扰"
            userSlowingDown -> false to "用户回复变慢，避免施压（R-S3）"
            hasClosure && warmAndIdle -> true to "关系亲密且 ${msSinceLastActive / 3_600_000L}h 未聊，有 $closureCount 件未完成的事可跟进"
            warmAndIdle -> true to "关系亲密(warmth=${"%.2f".format(field.warmth)})且 ${msSinceLastActive / 3_600_000L}h 未聊"
            hasClosure -> true to "有 $closureCount 件未完成的事可自然提起"
            else -> false to "条件未达建议阈值(warmth=${"%.2f".format(field.warmth)}, gap=${if (msSinceLastActive < 0) "从未" else "${msSinceLastActive / 3_600_000L}h"})"
        }

        return ProactiveDryRunEntry(
            timestamp = now,
            chatName = chatName,
            recommendContact = recommend,
            reason = reason,
            field = field,
            rhythmObservation = rhythmObs,
            rhythmSignals = snapshot.stateHint.signals,
            pendingCount = pendingCount,
            closureCandidateCount = closureCount,
            msSinceLastActive = msSinceLastActive,
        )
    }

    private const val TAG = "ProactiveScheduler"
}
