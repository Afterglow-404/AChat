package com.aftglw.devapi.core.debug

import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 崩溃时将堆栈 + 设备信息写入 cacheDir/log/crash_*.txt，
 * 然后交给系统默认处理器完成进程终止。
 *
 * 初始化：在 WispApplication.onCreate() 中调用 CrashHandler.init(context)。
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val MAX_CRASH_FILES = 5

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(applicationContext: android.content.Context) {
        if (defaultHandler != null) return // already initialized

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(applicationContext, thread, throwable)
            } catch (e: Exception) {
                // 保存日志自身失败时，至少写到 logcat
                Log.e(TAG, "Failed to save crash log", e)
            } finally {
                // 交给系统默认处理器（显示 ANR/崩溃对话框 或 静默终止）
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "CrashHandler initialized")
    }

    private fun saveCrashLog(context: android.content.Context, thread: Thread, throwable: Throwable) {
        val logDir = File(context.cacheDir, "log").apply { mkdirs() }

        // 清理旧崩溃日志，最多保留 MAX_CRASH_FILES 个
        val existing = logDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
        existing?.sortedByDescending { it.lastModified() }?.drop(MAX_CRASH_FILES - 1)?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val crashFile = File(logDir, "crash_$timestamp.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== Wisp Crash Report ===")
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            pw.println("Thread: ${thread.name} (id=${thread.id}, priority=${thread.priority})")
            pw.println("Device: ${Build.MODEL} (${Build.MANUFACTURER})")
            pw.println("OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("App PID: ${Process.myPid()}")
            pw.println("App Version: ${getVersionName(context)}")
            pw.println()
            pw.println("=== Stack Trace ===")
            throwable.printStackTrace(pw)

            // 打印 cause chain
            var cause = throwable.cause
            while (cause != null) {
                pw.println()
                pw.println("Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.printStackTrace(pw)
                cause = cause.cause
            }
        }

        crashFile.writeText(sw.toString())
        Log.e(TAG, "Crash log saved to ${crashFile.absolutePath}")
    }

    private fun getVersionName(context: android.content.Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
