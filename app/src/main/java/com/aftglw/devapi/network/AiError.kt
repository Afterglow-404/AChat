package com.aftglw.devapi.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * AI 请求错误的分类封装。
 *
 * 统一从异常中识别错误类型，并提供用户可读的提示文案。
 * 调用方（ChatScreen / GroupChatScreen）根据此类型决定 Toast 内容与是否提示重试。
 */
sealed class AiError(open val message: String) {
    /** 未配置 API URL/Key，或本地模式无模型 */
    object NotConfigured : AiError("未配置 API 地址或 Key")
    /** 401/403 - 鉴权失败 */
    data class Auth(val detail: String) : AiError("API Key 无效或无权限${suffix(detail)}")
    /** 429 - 限流（已在 HttpRetry 内自动重试，到这一层说明重试用尽） */
    data class RateLimit(val detail: String) : AiError("请求频率超限，已自动重试${suffix(detail)}")
    /** 客户端主动取消 */
    object Cancelled : AiError("已取消生成")
    /** 网络/连接超时（含 SocketTimeoutException、UnknownHostException、ConnectException） */
    data class Network(val detail: String) : AiError("网络异常${suffix(detail)}")
    /** 5xx 服务端错误 */
    data class Server(val status: Int, val detail: String) : AiError("AI 服务器错误 $status${suffix(detail)}")
    /** 4xx 其他客户端错误 */
    data class Client(val status: Int, val detail: String) : AiError("请求错误 $status${suffix(detail)}")
    /** 离线（无可用网络） */
    object Offline : AiError("当前无网络连接")
    /** 其他未知错误 */
    data class Unknown(val detail: String) : AiError("AI 请求失败${suffix(detail)}")

    companion object {
        /** 从异常推断错误类型。 */
        fun fromException(e: Throwable?): AiError {
            if (e == null) return Unknown("未知错误")
            val msg = e.message ?: ""
            // 协程取消（理论上不应被这里捕获，兜底处理）
            if (e is kotlinx.coroutines.CancellationException) return Cancelled
            // OkHttp 主动 cancel 会抛 IOException("Canceled")
            if (msg.contains("Canceled", ignoreCase = true)) return Cancelled
            // IOException 同时承载 HTTP 错误和纯网络异常
            if (e is IOException) {
                val httpMatch = Regex("^HTTP (\\d+)").find(msg)
                if (httpMatch != null) {
                    val code = httpMatch.groupValues[1].toIntOrNull() ?: 0
                    val snippet = msg.take(160)
                    return when (code) {
                        401, 403 -> Auth(snippet)
                        429 -> RateLimit(snippet)
                        in 500..599 -> Server(code, snippet)
                        in 400..499 -> Client(code, snippet)
                        else -> Unknown(snippet)
                    }
                }
                // 纯网络异常
                return when (e) {
                    is SocketTimeoutException -> Network("请求超时（已自动重试）")
                    is UnknownHostException -> Network("无法解析主机，请检查 API 地址或网络")
                    is ConnectException -> Network("无法连接到服务器")
                    else -> Network(msg.take(120))
                }
            }
            return Unknown(msg.take(120))
        }
    }
}

/** 详情非空时加 "："前缀，否则空字符串 */
private fun suffix(detail: String): String =
    if (detail.isBlank()) "" else "：${detail.take(120)}"
