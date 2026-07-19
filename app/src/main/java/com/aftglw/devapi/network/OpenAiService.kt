package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.core.security.SecureKeyStore
import com.aftglw.devapi.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class OpenAiService(context: Context) : AiService {

    private val appCtx = context.applicationContext
    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    /** 当前在飞的 OkHttp Call；供 [cancel] 使用 */
    @Volatile private var currentCall: okhttp3.Call? = null

    override fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    /** 检测当前配置是否指向 DeepSeek API */
    private fun isDeepSeek(): Boolean =
        prefs.getString("ai_api_url", "")?.contains("deepseek", ignoreCase = true) == true

    /** 是否启用 DeepSeek Thinking Mode */
    private fun isDeepSeekThinking(): Boolean =
        isDeepSeek() && prefs.getBoolean("ai_deepseek_thinking", false)

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?, toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = SecureKeyStore.getString(appCtx, "ai_api_key")
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            runBlocking {
                HttpRetry.retrySuspend("OpenAi", maxRetries = requestMaxRetries(baseUrl)) {
                    val messages = buildMessages(history, userMessage, systemPrompt)
                    val body = buildRequestBody(model, messages, streaming = false, tools = buildToolsArray())
                    val request = HttpClient.postJson("$baseUrl/chat/completions", body.toString(),
                        "Authorization" to "Bearer $apiKey")
                    val call = HttpClient.clientFor(baseUrl).newCall(request)
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
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val msgObj = choices.getJSONObject(0).getJSONObject("message")
                        val reasoningContent = msgObj.optString("reasoning_content", "")
                        if (reasoningContent.isNotEmpty()) {
                            android.util.Log.d("OpenAi", "DeepSeek reasoning (${reasoningContent.length} chars)")
                        }
                        var reply = msgObj.optString("content", "").trim()
                        val toolCalls = msgObj.optJSONArray("tool_calls")
                        if (toolCalls != null && toolCalls.length() > 0) {
                            if (toolCallsOut != null) {
                                for (j in 0 until toolCalls.length()) {
                                    val tc = toolCalls.getJSONObject(j)
                                    val func = tc.getJSONObject("function")
                                    val id = tc.optString("id", "")
                                    toolCallsOut.add(com.aftglw.devapi.network.ToolCall(
                                        name = func.getString("name"),
                                        arguments = func.optString("arguments", "{}"),
                                        id = id
                                    ))
                                }
                            } else {
                                val sb = StringBuilder(reply)
                                for (j in 0 until toolCalls.length()) {
                                    val func = toolCalls.getJSONObject(j).getJSONObject("function")
                                    sb.append("【tool:${func.getString("name")} ${func.optString("arguments", "{}")}】")
                                }
                                reply = sb.toString().trim()
                            }
                        }
                        val usage = json.optJSONObject("usage")
                        if (usage != null) {
                            val pt = usage.optInt("prompt_tokens", 0)
                            val ct = usage.optInt("completion_tokens", 0)
                            prefs.edit()
                                .putInt("last_tokens_in", pt)
                                .putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + pt)
                                .putInt("last_tokens_out", ct)
                                .putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + ct)
                                .apply()
                        } else {
                            val estIn = estimateTokenCount(systemPrompt, model) + messages.length() * 4 +
                                estimateTokenCount(userMessage, model)
                            val estOut = reply.length / 4
                            prefs.edit()
                                .putInt("last_tokens_in", estIn)
                                .putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + estIn)
                                .putInt("last_tokens_out", estOut)
                                .putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + estOut)
                                .apply()
                        }
                        reply
                    } else null
                }
            }
        } catch (e: Exception) {
            // 协程取消透传：当外部 cancel 触发 call.cancel() 时，会抛 IOException("Canceled")
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("OpenAi", "sendMessage failed", e)
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: ((String) -> Unit)?,
        toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?
    ) {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = SecureKeyStore.getString(appCtx, "ai_api_key")
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val result = runBlocking {
                HttpRetry.retrySuspend("OpenAi", maxRetries = requestMaxRetries(baseUrl)) {
                    val messages = buildMessages(history, userMessage, systemPrompt)
                    val body = buildRequestBody(model, messages, streaming = true, tools = buildToolsArray())
                    val httpReq = HttpClient.postJson("$baseUrl/chat/completions", body.toString(),
                        "Authorization" to "Bearer $apiKey")
                    val call = HttpClient.clientFor(baseUrl).newCall(httpReq)
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
                    val toolCallAccum = mutableMapOf<Int, StringBuilder>()
                    var thinkingActive = false
                    try {
                        while (reader.readLine().also { line = it } != null) {
                            val text = line ?: continue
                            if (!text.startsWith("data: ")) continue
                            val data = text.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val choices = JSONObject(data).optJSONArray("choices")
                                val delta = choices?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                                val content = delta.optString("content", "")
                                val rContent = delta.optString("reasoning_content", "")
                                if (rContent.isNotEmpty() && !thinkingActive) {
                                    thinkingActive = true
                                    android.util.Log.d("OpenAi", "DeepSeek thinking started")
                                }
                                if (content.isNotEmpty()) { full.append(content); onChunk(content) }
                                val tcArray = delta.optJSONArray("tool_calls")
                                if (tcArray != null) {
                                    for (i in 0 until tcArray.length()) {
                                        val tc = tcArray.getJSONObject(i)
                                        val idx = tc.getInt("index")
                                        val acc = toolCallAccum.getOrPut(idx) { StringBuilder() }
                                        tc.optJSONObject("function")?.let { func ->
                                            val name = func.optString("name", "")
                                            if (name.isNotEmpty()) acc.append(name).append("\n")
                                            acc.append(func.optString("arguments", ""))
                                        }
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    } finally { reader.close(); httpResp.close(); currentCall = null }
                    if (toolCallAccum.isNotEmpty()) {
                        if (toolCallsOut != null) {
                            for ((_, acc) in toolCallAccum) {
                                val parts = acc.toString().split("\n", limit = 2)
                                val tName = parts.getOrElse(0) { "unknown" }
                                val tArgs = parts.getOrElse(1) { "{}" }
                                toolCallsOut.add(com.aftglw.devapi.network.ToolCall(tName, tArgs, ""))
                            }
                            full.toString()
                        } else {
                            val sb = StringBuilder(full.toString().trim())
                            for ((_, acc) in toolCallAccum) {
                                val parts = acc.toString().split("\n", limit = 2)
                                val tName = parts.getOrElse(0) { "unknown" }
                                val tArgs = parts.getOrElse(1) { "{}" }
                                sb.append("【tool:" + tName + " " + tArgs + "】")
                            }
                            sb.toString().trim()
                        }
                    } else {
                        full.toString()
                    }
                }
            }
            onDone(result)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // 用户主动取消：通知上层 onDone 空字符串，不再 onError
                onDone("")
                return
            }
            android.util.Log.w("OpenAi", "sendMessageStream failed", e)
            onError?.invoke(e.message ?: "stream failed")
            onDone("")
        }
    }

    /** A desktop/Wisp debug endpoint should fail immediately when it is stopped. */
    private fun requestMaxRetries(baseUrl: String): Int =
        if (HttpClient.isLikelyLanUrl(baseUrl)) 0 else 3

    private fun buildRequestBody(
        model: String, messages: JSONArray, streaming: Boolean,
        tools: org.json.JSONArray? = null
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("stream", streaming)

        val thinkingMode = isDeepSeekThinking()

        if (thinkingMode) {
            put("extra_body", JSONObject().apply {
                put("thinking", JSONObject().apply { put("type", "enabled") })
            })
            put("reasoning_effort",
                when (prefs.getString("ai_deepseek_reasoning_effort", "high")) {
                    "max" -> "max"
                    else -> "high"
                }
            )
        } else {
            prefs.getString("ai_temperature", null)?.toFloatOrNull()?.let { put("temperature", it) }
            prefs.getString("ai_top_p", null)?.toFloatOrNull()?.let { put("top_p", it) }
            prefs.getString("ai_frequency_penalty", null)?.toFloatOrNull()?.let { put("frequency_penalty", it) }
            prefs.getString("ai_presence_penalty", null)?.toFloatOrNull()?.let { put("presence_penalty", it) }
        }

        prefs.getString("ai_max_tokens", null)?.toIntOrNull()?.let { put("max_completion_tokens", it) }
        prefs.getInt("ai_seed", -1).takeIf { it >= 0 }?.let { put("seed", it) }
        prefs.getString("ai_stop_sequences", null)?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { put("stop", org.json.JSONArray(it)) }
        prefs.getString("ai_response_format", null)?.let { fmt ->
            if (fmt == "json_object") put("response_format", org.json.JSONObject().apply { put("type", "json_object") })
        }
        if (tools != null) put("tools", tools)
    }

    private fun buildToolsArray(): org.json.JSONArray? {
        val allTools = com.aftglw.devapi.tools.ToolRegistry.getAll()
        if (allTools.isEmpty()) return null
        return org.json.JSONArray().apply {
            for (tool in allTools) {
                put(org.json.JSONObject().apply {
                    put("type", "function")
                    put("function", org.json.JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    })
                })
            }
        }
    }

    private fun buildMessages(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String
    ): JSONArray = JSONArray().apply {
        if (systemPrompt.isNotBlank()) {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        }
        val ctxWindow = prefs.getInt("context_window", 0)
        val maxTokens = if (ctxWindow > 0) ctxWindow else if (prefs.getBoolean("long_context_mode", true)) 4096 else 2048
        val recent = mutableListOf<ChatMessage>()
        val modelHint = prefs.getString("ai_model", "") ?: ""
        var tokCount = estimateTokenCount(systemPrompt, modelHint) + estimateTokenCount(userMessage, modelHint) + 10
        for (msg in history.reversed()) {
            val t = estimateTokenCount(msg.content, modelHint) + 4
            // 图片消息额外估算 token：每张图按 256 计（OpenAI 视觉约 85-1700/张）
            val imgTok = if (msg.images.isNotEmpty()) msg.images.size * 256 else 0
            if (tokCount + t + imgTok > maxTokens) break
            tokCount += t + imgTok
            recent.add(0, msg)
        }
        for ((i, msg) in recent.withIndex()) {
            val needReinject = ctxWindow <= 0 && !prefs.getBoolean("long_context_mode", true) && systemPrompt.isNotBlank()
            if (needReinject && i > 0 && i % 10 == 0) {
                put(JSONObject().apply { put("role", "system"); put("content", "【人设提醒】$systemPrompt") })
            }
            put(JSONObject().apply {
                put("role", msg.role)
                put("content", buildMessageContent(msg))
            })
        }
        put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
    }

    /**
     * 构造消息 content：纯文本返回字符串；含图片返回 OpenAI Vision 内容块数组。
     * 数组格式：[{"type":"text","text":...}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]
     */
    private fun buildMessageContent(msg: ChatMessage): Any = if (msg.images.isEmpty()) {
        msg.content
    } else {
        JSONArray().apply {
            if (msg.content.isNotBlank()) {
                put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
            }
            for (imgPath in msg.images) {
                val base64 = encodeImageBase64(imgPath) ?: return@apply
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64")
                    })
                })
            }
        }
    }

    /** 读取图片文件并编码为 base64；失败返回 null */
    private fun encodeImageBase64(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (!file.exists() || file.length() == 0L) return null
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
