package com.aftglw.devapi.core.affect

import android.content.Context
import android.util.Log
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.PendingEventEntity
import com.aftglw.devapi.core.storage.room.ProcessedEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 未完成事件存储 — Room 持久化 + 幂等键管理。
 *
 * 职责：
 * 1. PendingEvent 的 CRUD（Room 表 pending_events）
 * 2. eventId 幂等检查（Room 表 processed_events，设计文档 14.8）
 * 3. 定期清理过期的 processed_events 记录（7 天前）
 *
 * 所有方法都是 suspend，调用方需在 IO 协程中调用。
 */
object PendingEventStore {
    private const val TAG = "PendingEventStore"
    private const val PROCESSED_CLEANUP_THRESHOLD_MS = 7L * 86_400_000L  // 7 天

    // ── 幂等键 ──

    /**
     * 检查 eventId 是否已被处理。
     * 若未被处理，标记为已处理并返回 true（调用方随后执行实际逻辑）。
     * 若已被处理，返回 false（调用方跳过）。
     *
     * 这保证同一 eventId 被 AffectiveEngine.update() 处理两次时第二次直接跳过。
     */
    suspend fun checkAndMarkProcessed(ctx: Context, eventId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.get(ctx).processedEventDao()
            if (dao.isProcessed(eventId)) {
                Log.d(TAG, "eventId $eventId already processed, skipping")
                false
            } else {
                dao.markProcessed(ProcessedEventEntity(eventId, System.currentTimeMillis()))
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkAndMarkProcessed failed, allowing processing", e)
            // 失败时允许处理（宁可重复也不阻塞）
            true
        }
    }

    /** 清理过期的 processed_events 记录（7 天前） */
    suspend fun cleanupOldProcessedEvents(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - PROCESSED_CLEANUP_THRESHOLD_MS
            val deleted = AppDatabase.get(ctx).processedEventDao().cleanupOlderThan(cutoff)
            if (deleted > 0) Log.d(TAG, "cleaned up $deleted old processed_events")
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOldProcessedEvents failed", e)
        }
    }

    // ── PendingEvent CRUD ──

    suspend fun createPendingEvent(
        ctx: Context,
        chatName: String,
        summary: String,
        triggerText: String,
        closureType: ClosureType,
        weight: Float = 0.5f,
        now: Long = System.currentTimeMillis(),
    ): PendingEvent? = withContext(Dispatchers.IO) {
        try {
            val event = PendingEvent(
                id = UUID.randomUUID().toString(),
                chatName = chatName,
                createdAt = now,
                summary = summary,
                triggerText = triggerText.take(60),
                weight = weight,
                closureType = closureType,
            )
            val entity = event.toEntity()
            AppDatabase.get(ctx).pendingEventDao().upsert(entity)
            Log.d(TAG, "created pending event: ${event.summary}")
            event
        } catch (e: Exception) {
            Log.w(TAG, "createPendingEvent failed", e)
            null
        }
    }

    /** 获取某会话下所有未收尾、未归档的事件 */
    suspend fun getActivePendingEvents(ctx: Context, chatName: String): List<PendingEvent> = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().getActiveForChat(chatName).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w(TAG, "getActivePendingEvents failed", e)
            emptyList()
        }
    }

    /** 获取某会话下未尝试收尾的事件（用于主动收尾触发） */
    suspend fun getUnattemptedPendingEvents(ctx: Context, chatName: String): List<PendingEvent> = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().getUnattemptedForChat(chatName).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w(TAG, "getUnattemptedPendingEvents failed", e)
            emptyList()
        }
    }

    /** DebugPage 用：获取最近的事件（含已收尾/已归档） */
    suspend fun getRecentPendingEvents(ctx: Context, chatName: String, limit: Int = 20): List<PendingEvent> = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().getRecentForChat(chatName, limit).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w(TAG, "getRecentPendingEvents failed", e)
            emptyList()
        }
    }

    /** 标记 AI 已尝试收尾 */
    suspend fun markAttempt(ctx: Context, eventId: String, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.get(ctx).pendingEventDao()
            val target = dao.getById(eventId)
            if (target != null) {
                dao.updateAttempt(eventId, target.attemptCount + 1, now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "markAttempt failed", e)
        }
    }

    /** 标记为已收尾（用户回应了相关话题） */
    suspend fun markResolved(ctx: Context, eventId: String) = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().markResolved(eventId)
            Log.d(TAG, "pending event resolved: $eventId")
        } catch (e: Exception) {
            Log.w(TAG, "markResolved failed", e)
        }
    }

    /** 标记为已归档（staleness 过高且 attemptCount > 0） */
    suspend fun markArchived(ctx: Context, eventId: String) = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().markArchived(eventId)
            Log.d(TAG, "pending event archived: $eventId")
        } catch (e: Exception) {
            Log.w(TAG, "markArchived failed", e)
        }
    }

    /** 删除某会话下所有事件（会话被删除时调用） */
    suspend fun deleteForChat(ctx: Context, chatName: String) = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get(ctx).pendingEventDao().deleteForChat(chatName)
        } catch (e: Exception) {
            Log.w(TAG, "deleteForChat failed", e)
        }
    }

    // ── 转换 ──

    private fun PendingEvent.toEntity(): PendingEventEntity {
        return PendingEventEntity(
            id = id,
            chatName = chatName,
            createdAt = createdAt,
            summary = summary,
            triggerText = triggerText,
            weight = weight,
            closureType = closureType.name,
            attemptCount = attemptCount,
            lastAttemptAt = lastAttemptAt,
            resolved = resolved,
            archived = archived,
        )
    }

    private fun PendingEventEntity.toDomain(): PendingEvent {
        val type = runCatching { ClosureType.valueOf(closureType) }.getOrDefault(ClosureType.EXPLANATION)
        return PendingEvent(
            id = id,
            chatName = chatName,
            createdAt = createdAt,
            summary = summary,
            triggerText = triggerText,
            weight = weight,
            closureType = type,
            attemptCount = attemptCount,
            lastAttemptAt = lastAttemptAt,
            resolved = resolved,
            archived = archived,
        )
    }
}
