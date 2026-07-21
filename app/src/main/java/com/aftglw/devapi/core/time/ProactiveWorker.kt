package com.aftglw.devapi.core.time

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 主动消息 WorkManager Worker。
 *
 * 替代原 [ProactiveReceiver]（BroadcastReceiver + AlarmManager）方案：
 * - AlarmManager.setInexactRepeating 在 Doze 模式下被严重延迟，且需要单独的 BroadcastReceiver
 * - WorkManager 由系统统一调度，更省电，且与 CoroutineWorker 天然集成协程
 *
 * 首次执行通过 [ProactiveScheduler.enqueue] 延迟 30s 触发（避免 app 启动时立即跑 IO）。
 * 后续执行可通过 WorkManager 的 PeriodicWorkRequest 或外部再次调用 enqueue 实现。
 *
 * doWork() 直接委托给 [ProactiveScheduler.runOnce]，所有逻辑保留在原 object 中。
 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // P0.1：先跑 dry-run（只读，基于 AffectiveField 输出建议，不发送消息）
            // 放在 runOnce 之前，即使 runOnce 失败也能记录建议
            try {
                ProactiveScheduler.dryRunScan(applicationContext)
            } catch (e: Exception) {
                // dry-run 失败不影响 runOnce
                android.util.Log.w("ProactiveWorker", "dryRunScan failed", e)
            }
            // 实际发送（由 proactive_enabled_$chat 开关控制）
            ProactiveScheduler.runOnce(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // 主动消息非关键功能，失败不重试（避免频繁唤醒造成耗电）
            Result.failure()
        }
    }
}
