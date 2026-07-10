package com.aftglw.devapi.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端单例，供所有 AI 服务使用。
 *
 * 统一管理连接池、超时和公共配置，避免每个请求新建连接。
 */
object HttpClient {

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 快捷方法：构建 POST JSON 请求 */
    fun postJson(url: String, jsonBody: String, vararg headers: Pair<String, String>): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        return builder.build()
    }

    /** 执行请求并返回响应体文本，失败抛 IOException */
    fun execute(request: okhttp3.Request): String {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code} - ${response.message} - ${body.take(200)}")
        }
        response.close()
        return body
    }
}
