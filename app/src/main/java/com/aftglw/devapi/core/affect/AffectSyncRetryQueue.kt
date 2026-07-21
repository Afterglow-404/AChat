package com.aftglw.devapi.core.affect

import android.content.Context
import android.util.Log
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI

/**
 * AffectiveField 同步失败重试队列（P1.5 新增）。
 *
 * 设计目标（设计文档第十四章 P1.5）：
 * - syncAffectiveSnapshot 失败时入队待重试，避免网络抖动丢失数据
 * - 指数退避：10s → 30s → 90s，最多 3 次
 * - 队列上限 [MAX_QUEUE_SIZE]（超出丢弃最旧，避免内存膨胀）
 * - 纯内存队列，不持久化（app 重启清空，符合"同步数据非关键"语义）
 * - 状态码分类：仅可重试错误（408/425/429/5xx + 网络异常）入队，
 *   永久错误（400/401/403/404 等 4xx）直接丢弃，避免无谓重试
 *
 * 非目标：
 * - 不持久化到磁盘（避免 IO 开销 + 同步数据本身非关键）
 * - 不做端到端幂等（dev server 已有 eventKey 去重）
 *
 * 设计文档第十四章 P1.5。
 */
object AffectSyncRetryQueue {

    private const val TAG = "AffectSyncRetryQueue"
    private const val MAX_QUEUE_SIZE = 100
    private const val MAX_ATTEMPTS = 3

    // 指数退避间隔（毫秒）：10s → 30s → 90s
    private val BACKOFF_MS = longArrayOf(10_000L, 30_000L, 90_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 重试结果分类。
     * - [SUCCESS]：发送成功，从队列移除
     * - [RETRYABLE]：可重试失败（网络异常 / 408 / 425 / 429 / 5xx），按 attempts 退避重试
     * - [PERMANENT]：永久失败（4xx 除上述外），直接丢弃不重试
     */
    private enum class RetryOutcome { SUCCESS, RETRYABLE, PERMANENT }

    /**
     * 判断 HTTP 状态码是否可重试。
     *
     * 可重试（[RETRYABLE]）：
     * - 408 Request Timeout
     * - 425 Too Early
     * - 429 Too Many Requests
     * - 5xx 服务端错误
     *
     * 永久失败（[PERMANENT]）：
     * - 400 Bad Request（请求格式错误，重试也是同样结果）
     * - 401/403 鉴权失败（重试无意义，应提示用户检查 token）
     * - 404 路径错误（dev server 端点不对）
     * - 其他 4xx
     *
     * @param code HTTP 状态码
     * @return true 表示可重试
     */
    fun isRetryableStatus(code: Int): Boolean {
        return code == 408 || code == 425 || code == 429 || code in 500..599
    }

    /**
     * 队列条目。
     *
     * @param body 原始 JSON body（来自 syncAffectiveSnapshot）
     * @param syncUrl 同步 URL
     * @param token 鉴权 token（可空）
     * @param attempts 已尝试次数（0 = 首次入队，1 = 已重试 1 次）
     * @param nextRetryAt 下次重试时间戳（毫秒）
     */
    private data class RetryEntry(
        val body: String,
        val syncUrl: String,
        val token: String,
        val attempts: Int,
        val nextRetryAt: Long,
    )

    private val queue = ArrayDeque<RetryEntry>()
    @Volatile private var workerStarted = false

    // P2.4b: 同步失败统计（纯内存，与队列语义一致，app 重启清空）
    @Volatile private var failureStats = FailureStats()

    /**
     * 同步失败统计快照（P2.4b 新增）。
     *
     * @param totalCount 总失败次数（retryable + permanent + dropped）
     * @param retryableCount 可重试失败次数（408/425/429/5xx + 网络异常）
     * @param permanentCount 永久失败次数（400/401/403/404 等 4xx）
     * @param successCount 成功次数
     * @param droppedCount 因超 MAX_ATTEMPTS / 队列满丢弃次数
     * @param lastFailureCode 最近失败 HTTP 状态码（0 = 网络异常，-1 = 无失败）
     * @param lastFailureReason 最近失败原因分类
     * @param lastFailureAt 最近失败时间戳（0 = 无失败）
     * @param lastSuccessAt 最近成功时间戳（0 = 无成功）
     */
    data class FailureStats(
        val totalCount: Int = 0,
        val retryableCount: Int = 0,
        val permanentCount: Int = 0,
        val successCount: Int = 0,
        val droppedCount: Int = 0,
        val lastFailureCode: Int = -1,
        val lastFailureReason: String = "",
        val lastFailureAt: Long = 0L,
        val lastSuccessAt: Long = 0L,
    ) {
        /** 成功率（0..1），total=0 时返回 1.0（无失败 = 100% 成功） */
        val successRate: Float get() {
            val total = successCount + totalCount
            return if (total == 0) 1.0f else successCount.toFloat() / total
        }
    }

    /**
     * 入队一条失败的同步请求。
     * 仅当 [attempts] < [MAX_ATTEMPTS] 时入队；超出则丢弃并 Log.w。
     *
     * @param body 原始 JSON body
     * @param syncUrl 同步 URL
     * @param token 鉴权 token（可空）
     * @param attempts 已尝试次数（0 = 首次入队）
     */
    fun enqueue(body: String, syncUrl: String, token: String, attempts: Int = 0) {
        synchronized(queue) {
            if (attempts >= MAX_ATTEMPTS) {
                Log.w(TAG, "Dropping sync entry after $MAX_ATTEMPTS attempts: ${body.take(80)}...")
                recordDropped()
                return
            }
            // 队列满时丢弃最旧
            while (queue.size >= MAX_QUEUE_SIZE) {
                queue.removeFirst()
                Log.w(TAG, "Queue full, dropped oldest entry")
                recordDropped()
            }
            val backoff = BACKOFF_MS[attempts.coerceAtMost(BACKOFF_MS.size - 1)]
            val entry = RetryEntry(
                body = body,
                syncUrl = syncUrl,
                token = token,
                attempts = attempts,
                nextRetryAt = System.currentTimeMillis() + backoff,
            )
            queue.addLast(entry)
            Log.d(TAG, "Enqueued sync entry (attempt=${attempts + 1}/$MAX_ATTEMPTS, backoff=${backoff}ms, queueSize=${queue.size})")
        }
        startWorkerIfNeeded()
    }

    /** 查询当前队列状态（DebugPage 用） */
    fun getStatus(): QueueStatus {
        synchronized(queue) {
            val now = System.currentTimeMillis()
            return QueueStatus(
                queueSize = queue.size,
                pendingRetry = queue.count { it.nextRetryAt > now },
                readyToRetry = queue.count { it.nextRetryAt <= now },
            )
        }
    }

    /** 清空队列（DebugPage 用） */
    fun clear() {
        synchronized(queue) {
            queue.clear()
            Log.i(TAG, "Queue cleared")
        }
    }

    data class QueueStatus(
        val queueSize: Int,
        val pendingRetry: Int,
        val readyToRetry: Int,
    )

    // ── 内部：重试 worker ──

    private fun startWorkerIfNeeded() {
        if (workerStarted) return
        workerStarted = true
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val due: List<RetryEntry> = synchronized(queue) {
                    queue.filter { it.nextRetryAt <= now }
                }
                for (entry in due) {
                    retryEntry(entry)
                }
                // 等待下一轮检查（5s 检查一次，避免 busy loop）
                delay(5_000L)
            }
        }
    }

    private suspend fun retryEntry(entry: RetryEntry) {
        val outcome = trySend(entry.body, entry.syncUrl, entry.token)
        synchronized(queue) {
            queue.remove(entry)
        }
        when (outcome) {
            RetryOutcome.SUCCESS -> {
                Log.i(TAG, "Sync retry succeeded (attempt=${entry.attempts + 1})")
                recordSuccess()
            }
            RetryOutcome.RETRYABLE -> {
                // 可重试失败：重新入队（增加 attempts）
                enqueue(entry.body, entry.syncUrl, entry.token, entry.attempts + 1)
            }
            RetryOutcome.PERMANENT -> {
                // 永久失败：直接丢弃，不再重试
                Log.w(TAG, "Sync retry permanently failed, dropping (body=${entry.body.take(80)}...)")
                // recordPermanentFailure 已在 trySend 中调用
            }
        }
    }

    private suspend fun trySend(body: String, syncUrl: String, token: String): RetryOutcome {
        return try {
            val request = if (token.isBlank()) {
                HttpClient.postJson(
                    "$syncUrl/api/v1/debug/affect/snapshot",
                    body,
                    "X-Wisp-Source" to "android",
                )
            } else {
                HttpClient.postJson(
                    "$syncUrl/api/v1/debug/affect/snapshot",
                    body,
                    "Authorization" to "Bearer $token",
                    "X-Wisp-Source" to "android",
                )
            }
            HttpClient.clientFor(syncUrl).newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> RetryOutcome.SUCCESS
                    isRetryableStatus(response.code) -> {
                        Log.w(TAG, "Sync retry HTTP ${response.code} (retryable)")
                        recordRetryableFailure(response.code, "HTTP ${response.code}")
                        RetryOutcome.RETRYABLE
                    }
                    else -> {
                        Log.w(TAG, "Sync retry HTTP ${response.code} (permanent, not retrying)")
                        recordPermanentFailure(response.code, "HTTP ${response.code}")
                        RetryOutcome.PERMANENT
                    }
                }
            }
        } catch (e: Exception) {
            // 网络异常（UnknownHostException / SocketTimeoutException / ConnectException 等）默认可重试
            Log.w(TAG, "Sync retry failed (network): ${e.message}")
            recordRetryableFailure(0, "network: ${e.javaClass.simpleName}")
            RetryOutcome.RETRYABLE
        }
    }

    // ── P2.4b: 失败统计内部方法 ──

    private fun recordSuccess() {
        failureStats = failureStats.copy(
            successCount = failureStats.successCount + 1,
            lastSuccessAt = System.currentTimeMillis(),
        )
    }

    private fun recordRetryableFailure(code: Int, reason: String) {
        failureStats = failureStats.copy(
            totalCount = failureStats.totalCount + 1,
            retryableCount = failureStats.retryableCount + 1,
            lastFailureCode = code,
            lastFailureReason = reason,
            lastFailureAt = System.currentTimeMillis(),
        )
    }

    private fun recordPermanentFailure(code: Int, reason: String) {
        failureStats = failureStats.copy(
            totalCount = failureStats.totalCount + 1,
            permanentCount = failureStats.permanentCount + 1,
            lastFailureCode = code,
            lastFailureReason = reason,
            lastFailureAt = System.currentTimeMillis(),
        )
    }

    private fun recordDropped() {
        failureStats = failureStats.copy(
            totalCount = failureStats.totalCount + 1,
            droppedCount = failureStats.droppedCount + 1,
            lastFailureReason = "dropped (max attempts / queue full)",
            lastFailureAt = System.currentTimeMillis(),
        )
    }

    /** 查询失败统计快照（DebugPage 用） */
    fun getFailureStats(): FailureStats = failureStats

    /** 清空失败统计（DebugPage 用） */
    fun clearFailureStats() {
        failureStats = FailureStats()
        Log.i(TAG, "Failure stats cleared")
    }

    // P2.4 修复: 首次同步统计（PostLLMProcessor 调用）
    // FailureStats 原先只统计重试阶段，现补齐首次同步链路，成功率反映完整同步可靠性

    /** 记录首次同步成功（PostLLMProcessor 同步 2xx 时调用） */
    fun recordFirstSyncSuccess() {
        failureStats = failureStats.copy(
            successCount = failureStats.successCount + 1,
            lastSuccessAt = System.currentTimeMillis(),
        )
    }

    /** 记录首次同步失败（PostLLMProcessor 同步非 2xx / 网络异常时调用） */
    fun recordFirstSyncFailure(code: Int, reason: String, retryable: Boolean) {
        val newRetryable = if (retryable) failureStats.retryableCount + 1 else failureStats.retryableCount
        val newPermanent = if (!retryable) failureStats.permanentCount + 1 else failureStats.permanentCount
        failureStats = failureStats.copy(
            totalCount = failureStats.totalCount + 1,
            retryableCount = newRetryable,
            permanentCount = newPermanent,
            lastFailureCode = code,
            lastFailureReason = reason,
            lastFailureAt = System.currentTimeMillis(),
        )
    }
}
