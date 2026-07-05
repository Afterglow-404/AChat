package com.aftglw.devapi

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

/**
 * NTP 时间同步 + 时区管理
 * 用于修正系统时间偏差，给 AI 提供精确的时间上下文
 */
object TimeService {
    private const val NTP_SERVER = "pool.ntp.org"
    private var timeOffset: Long = 0  // 系统时间与 NTP 时间的差值（毫秒）
    private var lastSync: Long = 0
    private var timezoneId: String? = null

    fun getCurrentTime(ctx: Context): Long {
        syncIfNeeded(ctx)
        
        return if (timeOffset != 0L) System.currentTimeMillis() + timeOffset else System.currentTimeMillis()
    }

    fun getFormattedTime(ctx: Context): String {
        val timezone = getTimezone(ctx)
        val sdf = SimpleDateFormat("yyyy年M月d日 HH:mm:ss EEEE", Locale.CHINESE)
        sdf.timeZone = timezone
        return sdf.format(Date(getCurrentTime(ctx)))
    }

    fun getTimezone(ctx: Context): TimeZone {
        val tzId = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .getString("timezone_id", TimeZone.getDefault().id) ?: TimeZone.getDefault().id
        return TimeZone.getTimeZone(tzId)
    }

    fun getTimeOfDay(ctx: Context): String {
        val cal = Calendar.getInstance(getTimezone(ctx))
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 6 -> "凌晨"
            hour < 9 -> "早上"
            hour < 12 -> "上午"
            hour < 14 -> "中午"
            hour < 18 -> "下午"
            else -> "晚上"
        }
    }

    private fun syncIfNeeded(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSync < 3600000) return 
        lastSync = now
        Thread {
            try {
                val ntpTime = queryNtpTime()
                if (ntpTime > 0) {
                    timeOffset = ntpTime - System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // NTP 失败，使用系统时间
            }
        }.start()
    }

    private fun queryNtpTime(): Long {
        val sock = DatagramSocket()
        sock.soTimeout = 5000
        val address = InetAddress.getByName(NTP_SERVER)
        val buf = ByteArray(48)
        buf[0] = 0x1B.toByte()  // NTP request header
        val packet = DatagramPacket(buf, buf.size, address, 123)
        sock.send(packet)
        sock.receive(packet)
        sock.close()

        // Parse originate timestamp (bytes 40-43 + 44-47 for seconds and fraction)
        val seconds = ((buf[40].toLong() and 0xFF) shl 24) or
                ((buf[41].toLong() and 0xFF) shl 16) or
                ((buf[42].toLong() and 0xFF) shl 8) or
                (buf[43].toLong() and 0xFF)
        val fraction = ((buf[44].toLong() and 0xFF) shl 24) or
                ((buf[45].toLong() and 0xFF) shl 16) or
                ((buf[46].toLong() and 0xFF) shl 8) or
                (buf[47].toLong() and 0xFF)

        // NTP epoch starts at 1900-01-01, Unix epoch at 1970-01-01
        val ntpToUnix = 2208988800L
        return (seconds - ntpToUnix) * 1000 + (fraction * 1000 / 0x100000000L)
    }

    fun setTimezone(ctx: Context, tzId: String) {
        ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
            .putString("timezone_id", tzId).apply()
        timezoneId = tzId
    }

    fun getAvailableTimezones(): List<Pair<String, String>> {
        return listOf(
            "Asia/Shanghai" to "中国标准时间 (UTC+8)",
            "Asia/Tokyo" to "日本标准时间 (UTC+9)",
            "Asia/Seoul" to "韩国标准时间 (UTC+9)",
            "Asia/Singapore" to "新加坡时间 (UTC+8)",
            "Asia/Hong_Kong" to "香港时间 (UTC+8)",
            "Asia/Taipei" to "台北时间 (UTC+8)",
            "America/New_York" to "美东时间 (UTC-5)",
            "America/Chicago" to "美中时间 (UTC-6)",
            "America/Los_Angeles" to "美西时间 (UTC-8)",
            "Europe/London" to "伦敦时间 (UTC+0)",
            "Europe/Paris" to "巴黎时间 (UTC+1)",
            "Australia/Sydney" to "悉尼时间 (UTC+10)",
            "Pacific/Auckland" to "新西兰时间 (UTC+12)",
        )
    }
}
