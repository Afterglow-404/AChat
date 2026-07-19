package com.aftglw.devapi.core.debug

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Builds a shareable diagnostic file without attempting to read other apps' logs. */
object DebugLogExporter {
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024
    private const val MAX_LOG_LINES = 2000

    fun export(context: Context): File {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val logDir = File(context.cacheDir, "log").apply { mkdirs() }
        val logFile = File(logDir, "Wisp_debug_log.txt")
        val out = StringBuilder()

        out.appendLine("=== Wisp Debug Log ===")
        out.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())}")
        out.appendLine("Device: ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        out.appendLine("App PID: ${Process.myPid()}")
        out.appendLine("API endpoint: ${redactEndpoint(prefs.getString("ai_api_url", ""))}")
        out.appendLine("Mood: ${prefs.getBoolean("mood_enabled", false)}")
        out.appendLine("Affinity: ${prefs.getBoolean("affinity_enabled", false)}")
        out.appendLine("Protocol: ${com.aftglw.devapi.network.AiServiceFactory.getProtocolName()}")
        out.appendLine()
        out.appendLine("=== Application Logcat ===")
        out.append(captureOwnLogcat())

        logFile.writeText(out.toString())
        return logFile
    }

    private fun captureOwnLogcat(): String {
        val process = try {
            ProcessBuilder(
                "logcat", "-d", "-t", MAX_LOG_LINES.toString(),
                "-v", "threadtime", "--pid=${Process.myPid()}"
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "Unable to start logcat: ${e.message}\n"
        }

        return try {
            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (output.length + line.length + 1 > MAX_LOG_BYTES) break
                    output.appendLine(line)
                }
            }
            process.waitFor(2, TimeUnit.SECONDS)
            if (output.isEmpty()) "No application logcat output was available.\n" else output.toString()
        } catch (e: Exception) {
            "Unable to read logcat: ${e.message}\n"
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun redactEndpoint(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "<not configured>"
        return try {
            val uri = URI(value)
            val host = uri.host ?: return "<configured>"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme ?: "<unknown>"}://$host$port"
        } catch (_: Exception) {
            "<configured>"
        }
    }
}
