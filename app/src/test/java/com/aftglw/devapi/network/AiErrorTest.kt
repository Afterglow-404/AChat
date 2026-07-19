package com.aftglw.devapi.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [AiError] 异常分类与用户可读消息的单元测试。
 */
class AiErrorTest {

    @Test
    fun `null throwable returns Unknown`() {
        val err = AiError.fromException(null)
        assertTrue(err is AiError.Unknown)
    }

    @Test
    fun `CancellationException returns Cancelled`() {
        val err = AiError.fromException(kotlinx.coroutines.CancellationException("user"))
        assertTrue(err is AiError.Cancelled)
    }

    @Test
    fun `IOException with Canceled message returns Cancelled`() {
        val err = AiError.fromException(IOException("Canceled"))
        assertTrue(err is AiError.Cancelled)
    }

    @Test
    fun `SocketTimeoutException returns Network with timeout hint`() {
        val err = AiError.fromException(SocketTimeoutException("read timed out"))
        assertTrue(err is AiError.Network)
        assertTrue(err.message.contains("超时"))
    }

    @Test
    fun `UnknownHostException returns Network with host hint`() {
        val err = AiError.fromException(UnknownHostException("api.openai.com"))
        assertTrue(err is AiError.Network)
        assertTrue(err.message.contains("主机"))
    }

    @Test
    fun `ConnectException returns Network`() {
        val err = AiError.fromException(ConnectException("Connection refused"))
        assertTrue(err is AiError.Network)
    }

    @Test
    fun `HTTP 401 IOException returns Auth`() {
        val err = AiError.fromException(IOException("HTTP 401 - Unauthorized - {\"error\":\"invalid api key\"}"))
        assertTrue(err is AiError.Auth)
        assertTrue(err.message.contains("API Key 无效"))
    }

    @Test
    fun `HTTP 403 IOException returns Auth`() {
        val err = AiError.fromException(IOException("HTTP 403 - Forbidden"))
        assertTrue(err is AiError.Auth)
    }

    @Test
    fun `HTTP 429 IOException returns RateLimit`() {
        val err = AiError.fromException(IOException("HTTP 429 - Too Many Requests"))
        assertTrue(err is AiError.RateLimit)
        assertTrue(err.message.contains("频率超限"))
    }

    @Test
    fun `HTTP 500 IOException returns Server`() {
        val err = AiError.fromException(IOException("HTTP 500 - Internal Server Error"))
        assertTrue(err is AiError.Server)
        assertEquals(500, (err as AiError.Server).status)
    }

    @Test
    fun `HTTP 503 IOException returns Server`() {
        val err = AiError.fromException(IOException("HTTP 503 - Service Unavailable"))
        assertTrue(err is AiError.Server)
        assertEquals(503, (err as AiError.Server).status)
    }

    @Test
    fun `HTTP 400 IOException returns Client`() {
        val err = AiError.fromException(IOException("HTTP 400 - Bad Request"))
        assertTrue(err is AiError.Client)
        assertEquals(400, (err as AiError.Client).status)
    }

    @Test
    fun `HTTP 404 IOException returns Client`() {
        val err = AiError.fromException(IOException("HTTP 404 - Not Found"))
        assertTrue(err is AiError.Client)
    }

    @Test
    fun `Non-IOException returns Unknown`() {
        val err = AiError.fromException(RuntimeException("something broke"))
        assertTrue(err is AiError.Unknown)
        assertTrue(err.message.contains("something broke"))
    }

    @Test
    fun `Network error with empty message still produces friendly text`() {
        val err = AiError.fromException(IOException(""))
        assertTrue(err is AiError.Network)
        // 空消息不应导致结尾冒号悬空
        assertTrue(!err.message.endsWith("："))
    }

    @Test
    fun `Offline message is stable`() {
        assertEquals("当前无网络连接", AiError.Offline.message)
    }

    @Test
    fun `NotConfigured message is stable`() {
        assertEquals("未配置 API 地址或 Key", AiError.NotConfigured.message)
    }

    @Test
    fun `Cancelled message is stable`() {
        assertEquals("已取消生成", AiError.Cancelled.message)
    }
}
