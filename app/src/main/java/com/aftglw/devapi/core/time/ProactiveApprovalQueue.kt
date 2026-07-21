package com.aftglw.devapi.core.time

import android.content.Context
import android.util.Log
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 主动消息审批队列（P2.1 新增）。
 *
 * 设计目标（设计文档第十四章 P2.1）：
 * - dry-run → Desktop 批准 → 实际发送 的三段式审批流
 * - 暂不开放无确认自动发送（所有主动消息必须经过审批才能发送）
 * - 跨重启持久化（pending/approved 状态保留，避免决策丢失）
 * - 与 Desktop 端同步：POST pending → GET decisions
 * - 支持本地决策（DebugPage 内测，不依赖 Desktop）
 * - 支持取消（pending 或 approved 状态都可取消；sent 不可取消）
 *
 * 非目标：
 * - 不在 Desktop 端做实际发送（发送永远在 Android 端，避免远程操控风险）
 * - 不持久化已发送/已取消的最终态（仅保留最近 N 条用于幂等去重）
 *
 * 与 ProactiveGatekeeper 的关系：
 * - Gatekeeper 决定"是否允许进入审批队列"（AffectiveField + 冷却 + 免打扰 + 时间窗 DND）
 * - ApprovalQueue 决定"是否实际发送"（人工/本地审批）
 * - 二者串联：Gatekeeper 通过 → enqueue → Decision(APPROVE) → 实际发送
 *
 * 复用 Desktop bridge 配置（wisp_affect_sync_*）：
 * - 与 AffectiveField 同步共用同一端点（用户已在 DebugPage 配置过）
 * - 仅访问本地/私有 IP + 17890-17909 端口范围（与 PostLLMProcessor 一致）
 */
object ProactiveApprovalQueue {

    private const val TAG = "ProactiveApprovalQueue"
    private const val PREFS = "wechat_settings"
    private const val KEY_PENDING = "proactive_approval_pending"
    private const val KEY_DECIDED_IDS = "proactive_approval_decided_ids"
    private const val KEY_LAST_POLL_TS = "proactive_approval_last_poll_ts"
    private const val MAX_PENDING = 50
    private const val MAX_DECIDED_IDS = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Status { PENDING, APPROVED, REJECTED, CANCELLED, SENT }

    enum class DecisionAction { APPROVE, REJECT, CANCEL }

    data class PendingMessage(
        val id: String,
        val chatName: String,
        val content: String,
        val createdAt: Long,
        val gatekeeperReason: String,
        val warmth: Float,
        val tension: Float,
        val msSinceLastActive: Long,
        val status: Status = Status.PENDING,
        val syncedToDesktop: Boolean = false,
        val decidedAt: Long = 0L,
        val decisionSource: String = "",
    )

    data class Decision(
        val id: String,
        val action: DecisionAction,
        val decidedAt: Long,
        val source: String,
        val reason: String? = null,
    )

    // ── 入口 ──

    /**
     * 提交一条待审批消息到队列。
     *
     * 调用方：ProactiveScheduler.runOnce 在 gatekeeper 通过、generateMessage 之后。
     *
     * 内部行为：
     * 1. 加入本地队列（持久化）
     * 2. 异步 POST 到 Desktop（不阻塞调用方；失败保留本地等待下次 syncPendingToDesktop）
     *
     * 幂等：调用方应使用唯一 id（UUID）确保不会重复入队。
     */
    fun enqueue(ctx: Context, msg: PendingMessage) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = loadPending(prefs).toMutableList()
        // 队列上限：当 PENDING 状态的条数超限时丢弃最旧
        val pendingCount = list.count { it.status == Status.PENDING }
        if (pendingCount >= MAX_PENDING) {
            val firstPendingIdx = list.indexOfFirst { it.status == Status.PENDING }
            if (firstPendingIdx >= 0) {
                list.removeAt(firstPendingIdx)
                Log.w(TAG, "Queue full, dropped oldest pending entry")
            }
        }
        list.add(msg)
        savePending(prefs, list)
        Log.i(TAG, "Enqueued id=${msg.id} chat=${msg.chatName} content=${msg.content.take(30)}...")

        // 异步同步到 Desktop（失败保留本地，不影响调用方）
        scope.launch {
            try {
                if (postToDesktop(ctx, msg)) {
                    // 同步成功：标记 syncedToDesktop
                    val current = loadPending(prefs).toMutableList()
                    val idx = current.indexOfFirst { it.id == msg.id }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(syncedToDesktop = true)
                        savePending(prefs, current)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Desktop sync failed for ${msg.id}: ${e.message}")
            }
        }
    }

    /**
     * 本地决策（DebugPage 用）。
     */
    fun decideLocally(ctx: Context, id: String, action: DecisionAction, reason: String? = null) {
        applyDecision(ctx, Decision(
            id = id,
            action = action,
            decidedAt = System.currentTimeMillis(),
            source = "local",
            reason = reason,
        ))
    }

    /**
     * 应用一个决策（本地或 Desktop 来源）。
     *
     * 幂等：已决策过的 id 直接跳过（用 KEY_DECIDED_IDS 集合去重）。
     */
    fun applyDecision(ctx: Context, decision: Decision) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val decided = loadDecidedIds(prefs)
        if (decision.id in decided) {
            Log.d(TAG, "Decision ${decision.id} already applied, skip")
            return
        }
        val list = loadPending(prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == decision.id }
        if (idx < 0) {
            Log.w(TAG, "Decision ${decision.id} not in queue (evicted?)")
            // 仍记录到 decided 集合，避免 Desktop 重复推送
            addDecidedId(prefs, decision.id)
            return
        }
        val current = list[idx]
        // SENT 状态不可再决策（避免发送后被 Desktop 误判 reject）
        if (current.status == Status.SENT) {
            Log.w(TAG, "Cannot decide on ${decision.id}: already SENT")
            addDecidedId(prefs, decision.id)
            return
        }
        val newStatus = when (decision.action) {
            DecisionAction.APPROVE -> Status.APPROVED
            DecisionAction.REJECT -> Status.REJECTED
            DecisionAction.CANCEL -> Status.CANCELLED
        }
        list[idx] = current.copy(
            status = newStatus,
            decidedAt = decision.decidedAt,
            decisionSource = decision.source,
        )
        savePending(prefs, list)
        addDecidedId(prefs, decision.id)
        Log.i(TAG, "Applied ${decision.action} to ${decision.id} (source=${decision.source})")
    }

    /**
     * 发送前二次 Gatekeeper 检查失败时的安全终止。
     *
     * 这里不能复用 [applyDecision]：APPROVE 已经是一次合法决策，id
     * 已经进入 decidedIds；再次走幂等入口会把 APPROVED 永远留在队列里。
     * 该方法只允许把当前 APPROVED 项转为 CANCELLED，不改变原决策去重记录。
     */
    fun cancelByGatekeeper(ctx: Context, id: String, reason: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = loadPending(prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "Cannot cancel $id by Gatekeeper: not in queue")
            return
        }
        val current = list[idx]
        if (current.status != Status.APPROVED) {
            Log.d(TAG, "Gatekeeper cancellation skipped for $id: status=${current.status}")
            return
        }
        list[idx] = current.copy(
            status = Status.CANCELLED,
            decidedAt = System.currentTimeMillis(),
            decisionSource = "gatekeeper",
        )
        savePending(prefs, list)
        Log.i(TAG, "Cancelled approved $id by Gatekeeper: $reason")
    }

    /** 查询当前队列（DebugPage 用） */
    fun listPending(ctx: Context): List<PendingMessage> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return loadPending(prefs)
    }

    /** 查询已批准但未发送的消息（ProactiveScheduler 轮询发送用） */
    fun listApprovedUnsent(ctx: Context): List<PendingMessage> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return loadPending(prefs).filter { it.status == Status.APPROVED }
    }

    /** 标记为已发送（ProactiveScheduler 发送后调用） */
    fun markSent(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = loadPending(prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(status = Status.SENT)
        savePending(prefs, list)
        Log.i(TAG, "Marked $id as SENT")
    }

    /** 取消一条消息（pending/approved 状态都可取消） */
    fun cancel(ctx: Context, id: String) {
        decideLocally(ctx, id, DecisionAction.CANCEL, reason = "user cancelled")
    }

    /** 清空所有非终态条目（DebugPage 用）；终态条目保留用于幂等 */
    fun clearAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val terminal = loadPending(prefs).filter {
            it.status in listOf(Status.SENT, Status.REJECTED, Status.CANCELLED)
        }
        // 只保留最近 N 条终态条目用于幂等去重
        val trimmedTerminal = terminal.sortedByDescending { it.decidedAt }.take(MAX_PENDING)
        savePending(prefs, trimmedTerminal)
        Log.i(TAG, "Cleared all non-terminal pending messages")
    }

    /**
     * P2.4 修复: 清除指定角色的所有 pending/approved 消息。
     *
     * 用于 AffectiveEngine.clearForChat 时清理审批队列中该角色的待处理消息，
     * 避免删除角色后仍发送已审批的消息。
     *
     * - PENDING/APPROVED 状态：直接移除（设为 CANCELLED 会污染 decidedIds，物理删除更干净）
     * - SENT/REJECTED/CANCELLED 状态：保留（用于幂等去重，但 decidedIds 中该 chat 的 id 也需清理）
     * - decidedIds 中该 chat 的 id：移除（避免 decidedIds 无限增长）
     */
    fun clearForChat(ctx: Context, chatName: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = loadPending(prefs)
        // 移除该 chat 的 PENDING/APPROVED 消息（物理删除，不污染 decidedIds）
        val remaining = all.filter { !(it.chatName == chatName && it.status in listOf(Status.PENDING, Status.APPROVED)) }
        savePending(prefs, remaining)
        // 清理 decidedIds 中该 chat 的 id（从 pending 列表中反查该 chat 的所有 id）
        val chatIds = all.filter { it.chatName == chatName }.map { it.id }.toSet()
        if (chatIds.isNotEmpty()) {
            val decided = prefs.getStringSet(KEY_DECIDED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            val beforeSize = decided.size
            decided.removeAll(chatIds)
            if (decided.size != beforeSize) {
                prefs.edit().putStringSet(KEY_DECIDED_IDS, decided).apply()
            }
        }
        val removedCount = all.size - remaining.size
        if (removedCount > 0) {
            Log.i(TAG, "Cleared $removedCount pending/approved messages for $chatName")
        }
    }

    /**
     * 拉取 Desktop 端的决策并应用。
     *
     * ProactiveScheduler 轮询协程定期调用（建议周期 60s）。
     * - GET /api/v1/proactive/decisions?since=<lastPollTs>
     * - 对每个决策调用 applyDecision（自带幂等）
     * - 更新 lastPollTs
     */
    suspend fun pollDesktopDecisions(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wisp_affect_sync_enabled", false)) return
        val syncUrl = prefs.getString("wisp_affect_sync_url", "")?.trimEnd('/') ?: return
        if (!isSafeDesktopUrl(syncUrl)) return
        val token = prefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
        val lastPollTs = prefs.getLong(KEY_LAST_POLL_TS, 0L)

        val url = "$syncUrl/api/v1/proactive/decisions?since=$lastPollTs"
        val builder = okhttp3.Request.Builder().url(url).get()
            .header("X-Wisp-Source", "android")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        try {
            HttpClient.clientFor(syncUrl).newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Desktop poll HTTP ${response.code}")
                    return
                }
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val arr = json.optJSONArray("decisions") ?: return
                var latestTs = lastPollTs
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val id = d.getString("id")
                    val actionStr = d.getString("action")
                    val action = when (actionStr.uppercase()) {
                        "APPROVE" -> DecisionAction.APPROVE
                        "REJECT" -> DecisionAction.REJECT
                        "CANCEL" -> DecisionAction.CANCEL
                        else -> null
                    } ?: continue
                    val decidedAt = d.optLong("decidedAt", System.currentTimeMillis())
                    val reason = d.optString("reason", "")
                    applyDecision(ctx, Decision(
                        id = id,
                        action = action,
                        decidedAt = decidedAt,
                        source = "desktop",
                        reason = reason,
                    ))
                    if (decidedAt > latestTs) latestTs = decidedAt
                }
                prefs.edit().putLong(KEY_LAST_POLL_TS, latestTs).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Desktop poll failed: ${e.message}")
        }
    }

    /**
     * 同步所有未同步的 pending 到 Desktop。
     *
     * ProactiveScheduler 启动时调用一次，处理上次 app 期间未同步成功的条目。
     */
    suspend fun syncPendingToDesktop(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unsynced = loadPending(prefs).filter { !it.syncedToDesktop && it.status == Status.PENDING }
        for (msg in unsynced) {
            try {
                if (postToDesktop(ctx, msg)) {
                    val current = loadPending(prefs).toMutableList()
                    val idx = current.indexOfFirst { it.id == msg.id }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(syncedToDesktop = true)
                        savePending(prefs, current)
                    }
                } else {
                    break  // HTTP 失败，等下次重试
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed for ${msg.id}: ${e.message}")
                break  // 网络异常时停止后续同步
            }
        }
    }

    // ── 内部：Desktop POST ──

    private suspend fun postToDesktop(ctx: Context, msg: PendingMessage): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wisp_affect_sync_enabled", false)) return false
        val syncUrl = prefs.getString("wisp_affect_sync_url", "")?.trimEnd('/') ?: return false
        if (!isSafeDesktopUrl(syncUrl)) return false
        val token = prefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()

        val body = JSONObject().apply {
            put("id", msg.id)
            put("chatName", msg.chatName)
            put("content", msg.content)
            put("createdAt", msg.createdAt)
            put("gatekeeperReason", msg.gatekeeperReason)
            put("warmth", msg.warmth.toDouble())
            put("tension", msg.tension.toDouble())
            put("msSinceLastActive", msg.msSinceLastActive)
        }
        val request = if (token.isBlank()) {
            HttpClient.postJson(
                "$syncUrl/api/v1/proactive/pending",
                body.toString(),
                "X-Wisp-Source" to "android",
            )
        } else {
            HttpClient.postJson(
                "$syncUrl/api/v1/proactive/pending",
                body.toString(),
                "Authorization" to "Bearer $token",
                "X-Wisp-Source" to "android",
            )
        }
        return try {
            HttpClient.clientFor(syncUrl).newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Synced ${msg.id} to Desktop")
                    true
                } else {
                    Log.w(TAG, "Desktop sync HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Desktop sync failed: ${e.message}")
            false
        }
    }

    // ── 内部：持久化 ──

    private fun loadPending(prefs: android.content.SharedPreferences): List<PendingMessage> {
        return try {
            val json = prefs.getString(KEY_PENDING, null) ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingMessage(
                    id = o.getString("id"),
                    chatName = o.getString("chatName"),
                    content = o.getString("content"),
                    createdAt = o.getLong("createdAt"),
                    gatekeeperReason = o.optString("gatekeeperReason", ""),
                    warmth = o.optDouble("warmth", 0.0).toFloat(),
                    tension = o.optDouble("tension", 0.0).toFloat(),
                    msSinceLastActive = o.optLong("msSinceLastActive", -1L),
                    status = Status.valueOf(o.optString("status", Status.PENDING.name)),
                    syncedToDesktop = o.optBoolean("syncedToDesktop", false),
                    decidedAt = o.optLong("decidedAt", 0L),
                    decisionSource = o.optString("decisionSource", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadPending failed", e)
            emptyList()
        }
    }

    private fun savePending(prefs: android.content.SharedPreferences, list: List<PendingMessage>) {
        // 清理：终态条目（SENT/REJECTED/CANCELLED）只保留最近 MAX_PENDING 条用于幂等
        val terminalStatuses = listOf(Status.SENT, Status.REJECTED, Status.CANCELLED)
        val terminal = list.filter { it.status in terminalStatuses }
            .sortedByDescending { it.decidedAt }
            .take(MAX_PENDING)
        val active = list.filter { it.status !in terminalStatuses }
        val finalList = active + terminal

        val arr = JSONArray()
        finalList.forEach { msg ->
            arr.put(JSONObject().apply {
                put("id", msg.id)
                put("chatName", msg.chatName)
                put("content", msg.content)
                put("createdAt", msg.createdAt)
                put("gatekeeperReason", msg.gatekeeperReason)
                put("warmth", msg.warmth.toDouble())
                put("tension", msg.tension.toDouble())
                put("msSinceLastActive", msg.msSinceLastActive)
                put("status", msg.status.name)
                put("syncedToDesktop", msg.syncedToDesktop)
                put("decidedAt", msg.decidedAt)
                put("decisionSource", msg.decisionSource)
            })
        }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun loadDecidedIds(prefs: android.content.SharedPreferences): Set<String> {
        return try {
            val json = prefs.getString(KEY_DECIDED_IDS, null) ?: return emptySet()
            JSONArray(json).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (e: Exception) { emptySet() }
    }

    private fun addDecidedId(prefs: android.content.SharedPreferences, id: String) {
        val current = loadDecidedIds(prefs).toMutableSet()
        current.add(id)
        // 上限：超出按字典序丢弃最旧（仅用于幂等去重，不需要严格时间序）
        val trimmed = if (current.size > MAX_DECIDED_IDS) {
            current.sorted().takeLast(MAX_DECIDED_IDS).toSet()
        } else current
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        prefs.edit().putString(KEY_DECIDED_IDS, arr.toString()).apply()
    }

    // ── 内部：URL 安全校验（与 PostLLMProcessor 保持一致）──

    private fun isSafeDesktopUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase() ?: return false
            val port = uri.port
            val localHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
            val privateV4 = host.startsWith("10.") || host.startsWith("192.168.") ||
                (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
            val privateV6 = host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
            uri.scheme in listOf("http", "https") && port in 17890..17909 && (localHost || privateV4 || privateV6)
        } catch (_: Exception) {
            false
        }
    }
}
