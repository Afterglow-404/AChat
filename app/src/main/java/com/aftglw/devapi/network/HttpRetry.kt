package com.aftglw.devapi.network

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

    /** 判断一个异常是否值得重试 */
    private fun isRetryable(e: Exception): Boolean {
        // 网络层异常
        if (e is ConnectException || e is SocketException || e is SocketTimeoutException) return true
        if (e is IOException) return true

        // HTTP 状态码检查（通过 HttpURLConnection 传入的异常消息解析，或直接传入状态码）
        val msg = e.message ?: ""
        when {
            msg.contains("HTTP 429") || msg.contains("429") -> return true
            msg.contains("HTTP 5") || msg.contains("HTTP 50") -> return true
            msg.contains("502") || msg.contains("503") || msg.contains("504") -> return true
        }
        return false
    }

    /**
     * 以指数退避方式执行 [block]。
     * - 成功 → 返回 block 的返回值
     * - 不可重试的异常 → 直接抛出
     * - 可重试且超过最大次数 → 抛出最后一次异常
     */
    fun <T> retry(
        tag: String = "HttpRetry",
        block: () -> T
    ): T {
        var delay = INITIAL_DELAY_MS
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
                android.util.Log.w(tag, "Attempt ${attempt + 1}/$MAX_RETRIES failed, retry in ${delay}ms: ${e.message}")
                try { Thread.sleep(delay) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                delay *= 2
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
