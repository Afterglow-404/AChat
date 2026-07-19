package com.aftglw.devapi.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 指数退避重试工具。
 *
 * 对 [block] 中抛出的可重试异常自动重试，初始等待 1s 后依次翻倍（1→2→4s）。
 * 可重试条件：连接/读写超时、Socket 异常、HTTP 429（限流）、HTTP 5xx（服务端错误）。
 */
object HttpRetry {

    private const val MAX_RETRIES = 3
    private const val INITIAL_DELAY_MS = 1000L

    /** 从 [checkResponse] 抛出的异常消息中提取 HTTP 状态码 */
    private val HTTP_STATUS_REGEX = Regex("^HTTP (\\d+)")

    /** 判断一个异常是否值得重试 */
    private fun isRetryable(e: Exception): Boolean {
        // OkHttp 主动取消（Call.cancel()）：不重试
        if (e is IOException && (e.message ?: "").contains("Canceled", ignoreCase = true)) return false
        // 网络层异常
        if (e is ConnectException || e is SocketException || e is SocketTimeoutException) return true
        if (e is IOException) {
            val msg = e.message ?: ""
            // checkResponse() 格式: "HTTP <code> - ..."
            val code = HTTP_STATUS_REGEX.find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code != null) {
                // 429 限流 或 5xx 服务端错误 → 可重试
                return code == 429 || code in 500..599
            }
            // 没有状态码的 IOException（纯网络问题）→ 可重试
            return true
        }
        return false
    }

    /**
     * 以指数退避方式执行 [block]。
     * - 成功 → 返回 block 的返回值
     * - 不可重试的异常 → 直接抛出
     * - 可重试且超过最大次数 → 抛出最后一次异常
     *
     * 注意：此方法同步阻塞，会占用线程；协程环境请使用 [retrySuspend]。
     */
    fun <T> retry(
        tag: String = "HttpRetry",
        block: () -> T
    ): T {
        var delayMs = INITIAL_DELAY_MS
        var lastEx: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                lastEx = e
                if (attempt == MAX_RETRIES || !isRetryable(e)) {
                    android.util.Log.w(tag, "Final failure after $attempt retries: ${e.message}")
                    throw e
                }
                android.util.Log.w(tag, "Attempt ${attempt + 1}/$MAX_RETRIES failed, retry in ${delayMs}ms: ${e.message}")
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                delayMs *= 2
            }
        }
        throw lastEx ?: Exception("retry failed")
    }

    /**
     * 协程版指数退避重试，使用 [delay] 而非 Thread.sleep，不阻塞线程。
     *
     * - 成功 → 返回 block 的返回值
     * - 不可重试的异常 → 直接抛出
     * - 可重试且超过最大次数 → 抛出最后一次异常
     * - 协程取消（CancellationException）→ 立即抛出，不重试
     */
    suspend fun <T> retrySuspend(
        tag: String = "HttpRetry",
        maxRetries: Int = MAX_RETRIES,
        initialDelayMs: Long = INITIAL_DELAY_MS,
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs
        var lastEx: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastEx = e
                if (attempt == maxRetries || !isRetryable(e)) {
                    android.util.Log.w(tag, "Final failure after $attempt retries: ${e.message}")
                    throw e
                }
                android.util.Log.w(tag, "Attempt ${attempt + 1}/$maxRetries failed, retry in ${delayMs}ms: ${e.message}")
                delay(delayMs)
                delayMs *= 2
            }
        }
        throw lastEx ?: Exception("retry failed")
    }

    /**
     * 检查 HttpURLConnection 的响应码，在非 2xx 时抛出 IOException。
     * 在 retry block 中调用此方法可让 [retry] 识别 429/5xx 并重试。
     */
    fun checkResponse(conn: HttpURLConnection): HttpURLConnection {
        val code = conn.responseCode
        if (code in 200..299) return conn
        val body = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        conn.disconnect()
        throw IOException("HTTP $code - ${conn.responseMessage} - $body")
    }
}
