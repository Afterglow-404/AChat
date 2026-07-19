package com.aftglw.devapi.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [HttpRetry.retrySuspend] 的协程重试逻辑测试。
 */
class HttpRetryTest {

    @Test
    fun `success on first attempt returns immediately`() = runBlocking {
        var calls = 0
        val result = HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries on retryable IOException then succeeds`() = runBlocking {
        var calls = 0
        val result = HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
            calls++
            if (calls < 3) throw SocketTimeoutException("timeout")
            "ok-after-retry"
        }
        assertEquals("ok-after-retry", result)
        assertEquals(3, calls)
    }

    @Test
    fun `retries on HTTP 429 then succeeds`() = runBlocking {
        var calls = 0
        val result = HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
            calls++
            if (calls < 2) throw IOException("HTTP 429 - Too Many Requests")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `retries on HTTP 500 then succeeds`() = runBlocking {
        var calls = 0
        val result = HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
            calls++
            if (calls < 2) throw IOException("HTTP 500 - Internal Server Error")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `non-retryable HTTP 401 throws immediately without retry`() = runBlocking {
        var calls = 0
        val ex = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
                    calls++
                    throw IOException("HTTP 401 - Unauthorized")
                }
            }
        }
        assertTrue(ex.message!!.contains("401"))
        assertEquals(1, calls)
    }

    @Test
    fun `exhausts retries then throws last exception`() = runBlocking {
        var calls = 0
        val ex = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                HttpRetry.retrySuspend<String>(maxRetries = 2, initialDelayMs = 1) {
                    calls++
                    throw IOException("HTTP 500 - Internal Server Error")
                }
            }
        }
        // maxRetries=2 → 1 + 2 = 3 attempts
        assertEquals(3, calls)
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `non-IOException non-retryable throws immediately`() = runBlocking {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
                    calls++
                    throw IllegalStateException("bad state")
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `Canceled IOException is not retried`() = runBlocking {
        var calls = 0
        val ex = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
                    calls++
                    throw IOException("Canceled")
                }
            }
        }
        assertTrue(ex.message!!.contains("Canceled"))
        assertEquals(1, calls)
    }

    @Test
    fun `CancellationException is rethrown immediately without retry`() = runBlocking {
        var calls = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                HttpRetry.retrySuspend<String>(maxRetries = 3, initialDelayMs = 1) {
                    calls++
                    throw kotlinx.coroutines.CancellationException("parent cancelled")
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `parent coroutine cancellation propagates through retrySuspend`() = runBlocking {
        var calls = 0
        val job = async {
            HttpRetry.retrySuspend<String>(maxRetries = 5, initialDelayMs = 1000) {
                calls++
                // 第一次调用挂起足够久，让外部取消能命中
                delay(2000)
                "ok"
            }
        }
        delay(100) // 等任务进入 retrySuspend
        job.cancelAndJoin()
        // 任务被取消，不应继续重试
        assertTrue(calls <= 1)
    }
}
