package com.aftglw.devapi

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.aftglw.devapi.core.debug.CrashHandler
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.voice.LocalSenseVoiceSttProvider
import com.aftglw.devapi.core.voice.LocalWhisperSttProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局 Application：负责异步预热数据库 + 内存压力时释放 native 资源。
 *
 * 旧版 AppDatabase.build() 在主线程同步调用 LegacyMigrator.migrateIfFirstRun（内部 runBlocking），
 * 首次启动迁移全量历史数据时阻塞数秒。现改为在 onCreate 的 IO 协程中异步执行。
 */
class WispApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 全局崩溃日志保护（最先初始化，确保后续异常也能被捕获）
        CrashHandler.init(this)

        // 异步预热数据库 + 执行一次性迁移（不阻塞主线程）
        appScope.launch {
            try {
                AppDatabase.preInit(this@WispApplication)
            } catch (e: Exception) {
                Log.e("WispApplication", "Database preInit failed", e)
            }
        }
    }

    /**
     * 内存压力回调：释放 STT/TTS 等 native 资源。
     * TRIM_MEMORY_MODERATE 时释放 STT recognizer（150-250MB native 内存）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            try {
                // 释放 STT recognizer（150-250MB native 内存）
                LocalWhisperSttProvider.releaseNative()
                LocalSenseVoiceSttProvider.releaseNative()
            } catch (e: Exception) {
                Log.e("WispApplication", "STT shutdown failed", e)
            }
        }
    }
}
