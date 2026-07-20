package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.core.security.SecureKeyStore
import com.aftglw.devapi.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class ClaudeAiService(context: Context) : AiService {

    private val appCtx = context.applicationContext
    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    /** 当前在飞的 OkHttp Call；供 [cancel] 使用 */
    @Volatile private var currentCall: okhttp3.Call? = null

    /** token 计数内存累加缓冲：减少 SharedPreferences 磁盘写入次数 */
    private var pendingTokenWrites = 0
    @Volatile private var pendingTokenInDelta = 0
    @Volatile private var pendingTokenOutDelta = 0

    override fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    /** 批量 flush 累积的 token 计数到 SharedPreferences */
    private fun flushTokenCounts() {
        if (pendingTokenWrites == 0) return
        prefs.edit()
            .putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + pendingTokenInDelta)
            .putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + pendingTokenOutDelta)
            .apply()
        pendingTokenInDelta = 0
        pendingTokenOutDelta = 0
        pendingTokenWrites = 0
    }

    /** 退出前调用，确保累积 token 计数落盘 */
    fun shutdown() {
        flushTokenCounts()
    }

    override suspend fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?, toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = SecureKeyStore.getString(appCtx, "ai_api_key")
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            HttpRetry.retrySuspend("Claude") {
                val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = false, tools = buildToolsArray())
                val request = HttpClient.postJson("$baseUrl/messages", body.toString(),
                    "x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                val call = HttpClient.client.newCall(request)
                currentCall = call
                val response = try {
                    call.execute()
                } finally {
                    currentCall = null
                }
                val respBody = try {
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        throw java.io.IOException("HTTP ${response.code} - ${response.message} - $errBody")
                    }
                    response.body?.string() ?: ""
                } finally {
                    response.close()
                }
                val json = JSONObject(respBody)
                val contentArr = json.optJSONArray("content")
                var reply = ""
                if (contentArr != null) {
                    val sb = StringBuilder()
                    for (i in 0 until contentArr.length()) {
                        val block = contentArr.getJSONObject(i)
                        val type = block.optString("type", "")
                        if (type == "text") {
                            sb.append(block.optString("text", ""))
                        } else if (type == "tool_use") {
                            val name = block.getString("name")
                            val args = block.optJSONObject("input")?.toString() ?: "{}"
                            if (toolCallsOut != null) {
                                toolCallsOut.add(ToolCall(name, args, ""))
                            } else {
                                sb.append("【tool:${name} ${args}】")
                            }
                        }
                    }
                    reply = sb.toString().trim()
                }
                if (reply.isNotBlank()) {
                    val estIn = estimateTokenCount(systemPrompt, model) + messagesLength(body) + estimateTokenCount(userMessage, model)
                    val estOut = reply.length / 4
                    prefs.edit()
                        .putInt("last_tokens_in", estIn)
                        .putInt("last_tokens_out", estOut)
                        .apply()
                    pendingTokenInDelta += estIn
                    pendingTokenOutDelta += estOut
                    pendingTokenWrites++
                    if (pendingTokenWrites >= 10) flushTokenCounts()
                    reply.trim()
                } else null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("Claude", "sendMessage failed", e)
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        onChunk: (String) -> Unit, onDone: (String) -> Unit,
        onError: ((String) -> Unit)?,
        toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?
    ) {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = SecureKeyStore.getString(appCtx, "ai_api_key")
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val result = runBlocking {
                // 流式失败不重试，避免半句话+重新开始的 UX 问题
                HttpRetry.retrySuspendNoRetry("Claude-stream") {
                    val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = true, tools = buildToolsArray())
                    val httpReq = HttpClient.postJson("$baseUrl/messages", body.toString(),
                        "x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                    val call = HttpClient.client.newCall(httpReq)
                    currentCall = call
                    val httpResp = try {
                        call.execute()
                    } catch (e: Exception) {
                        currentCall = null
                        throw e
                    }
                    if (!httpResp.isSuccessful) {
                        val errBody = httpResp.body?.string() ?: ""
                        httpResp.close()
                        currentCall = null
                        throw java.io.IOException("HTTP ${httpResp.code} - $errBody")
                    }
                    val reader = httpResp.body?.byteStream()?.bufferedReader()
                        ?: run { httpResp.close(); currentCall = null; throw java.io.IOException("No response body") }
                    var line: String?
                    val full = StringBuilder()
                    var pendingEvent = ""
                    var toolName = ""
                    var toolIdx = -1
                    val toolArgs = StringBuilder()
                    try {
                        while (reader.readLine().also { line = it } != null) {
                            val text = line ?: continue
                            when {
                                text.startsWith("event: ") -> pendingEvent = text.removePrefix("event: ").trim()
                                text.startsWith("data: ") -> {
                                    val data = text.removePrefix("data: ").trim()
                                    when (pendingEvent) {
                                        "content_block_delta" -> {
                                            val delta = try {
                                                JSONObject(data).optJSONObject("delta")?.optString("text", "") ?: ""
                                            } catch (_: Exception) { "" }
                                            if (delta.isNotEmpty()) { full.append(delta); onChunk(delta) }
                                        }
                                        "content_block_start" -> {
                                            try {
                                                val block = JSONObject(data).optJSONObject("content_block")
                                                if (block != null && block.optString("type") == "tool_use") {
                                                    toolName = block.optString("name", "")
                                                    toolIdx = JSONObject(data).optInt("index", -1)
                                                    toolArgs.setLength(0)
                                                    val initInput = block.optJSONObject("input")?.toString() ?: ""
                                                    if (initInput.isNotEmpty()) toolArgs.append(initInput)
                                                }
                                            } catch (_: Exception) { }
                                        }
                                        "input_json_delta" -> {
                                            try {
                                                toolArgs.append(JSONObject(data).optJSONObject("delta")?.optString("partial_json", "") ?: "")
                                            } catch (_: Exception) { }
                                        }
                                        "content_block_stop" -> {
                                            if (toolName.isNotEmpty()) {
                                                if (toolCallsOut != null) {
                                                    toolCallsOut.add(ToolCall(toolName, toolArgs.toString(), ""))
                                                } else {
                                                    full.append("【tool:" + toolName + " " + toolArgs.toString() + "】")
                                                }
                                                toolName = ""
                                                toolArgs.setLength(0)
                                            }
                                        }
                                    }
                                    pendingEvent = ""
                                }
                            }
                        }
                    } finally { reader.close(); httpResp.close(); currentCall = null }
                    full.toString()
                }
            }
            onDone(result)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                onDone("")
                return
            }
            android.util.Log.w("Claude", "sendMessageStream failed", e)
            onError?.invoke(e.message ?: "stream failed")
            onDone("")
        }
    }

    private fun buildRequestBody(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        model: String, streaming: Boolean,
        tools: org.json.JSONArray? = null
    ): JSONObject {
        val messages = JSONArray().apply {
            val ctxWindow = prefs.getInt("context_window", 0)
            val maxTokens = if (ctxWindow > 0) ctxWindow else if (prefs.getBoolean("long_context_mode", true)) 4096 else 2048
            val recent = mutableListOf<ChatMessage>()
            val modelHint = prefs.getString("ai_model", "") ?: ""
            var tokCount = estimateTokenCount(systemPrompt, modelHint) + estimateTokenCount(userMessage, modelHint) + 10
            for (msg in history.reversed()) {
                val t = estimateTokenCount(msg.content, modelHint) + 4
                // 图片消息额外估算 token：每张图按 256 计
                val imgTok = if (msg.images.isNotEmpty()) msg.images.size * 256 else 0
                if (tokCount + t + imgTok > maxTokens) break
                tokCount += t + imgTok
                recent.add(0, msg)
            }
            for (msg in recent) {
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", buildClaudeContent(msg))
                })
            }
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }

        val maxTokens = prefs.getString("ai_max_tokens", null)?.toIntOrNull() ?: 4096

        return JSONObject().apply {
            put("model", model)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", messages)
            put("max_tokens", maxTokens)
            if (streaming) put("stream", true)
            if (tools != null) put("tools", tools)
            prefs.getString("ai_temperature", null)?.toFloatOrNull()?.let { put("temperature", it) }
            prefs.getString("ai_top_p", null)?.toFloatOrNull()?.let { put("top_p", it) }
            prefs.getInt("ai_seed", -1).takeIf { it >= 0 }?.let { put("seed", it) }
        }
    }

    /**
     * 构造 Claude 消息 content：纯文本返回字符串；含图片返回 Claude 内容块数组。
     * Claude 格式：[{"type":"text","text":...}, {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}}]
     */
    private fun buildClaudeContent(msg: ChatMessage): Any = if (msg.images.isEmpty()) {
        msg.content
    } else {
        JSONArray().apply {
            if (msg.content.isNotBlank()) {
                put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
            }
            for (imgPath in msg.images) {
                val base64 = encodeImageBase64(imgPath) ?: continue
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", base64)
                    })
                })
            }
        }
    }

    private fun encodeImageBase64(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (!file.exists() || file.length() == 0L) return null
            val out = java.io.ByteArrayOutputStream(file.length().toInt().coerceAtMost(1024 * 1024))
            android.util.Base64OutputStream(out, android.util.Base64.NO_WRAP).use { b64 ->
                file.inputStream().use { it.copyTo(b64, 64 * 1024) }
            }
            out.toString(Charsets.US_ASCII.name())
        } catch (_: Exception) { null }
    }

    private fun buildToolsArray(): org.json.JSONArray? {
        val allTools = com.aftglw.devapi.tools.ToolRegistry.getAll()
        if (allTools.isEmpty()) return null
        return org.json.JSONArray().apply {
            for (tool in allTools) {
                put(org.json.JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.inputSchema)
                })
            }
        }
    }
}

private fun messagesLength(body: JSONObject) = (body.optJSONArray("messages")?.length() ?: 0) * 4
