package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class OpenAiService(context: Context) : AiService {

    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            HttpRetry.retry("OpenAi") {
            val messages = buildMessages(history, userMessage, systemPrompt)
            val body = buildRequestBody(model, messages, streaming = false)
            val request = HttpClient.postJson("$baseUrl/chat/completions", body.toString(),
                "Authorization" to "Bearer $apiKey")
            val response = HttpClient.execute(request)

            val json = JSONObject(response)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val msgObj = choices.getJSONObject(0).getJSONObject("message")
                var reply = msgObj.optString("content", "").trim()
                val toolCalls = msgObj.optJSONArray("tool_calls")
                if (toolCalls != null && toolCalls.length() > 0) {
                    val sb = StringBuilder(reply)
                    for (j in 0 until toolCalls.length()) {
                        val func = toolCalls.getJSONObject(j).getJSONObject("function")
                        sb.append("【tool:${func.getString("name")} ${func.optString("arguments", "{}")}】")
                    }
                    reply = sb.toString().trim()
                }
                val usage = json.optJSONObject("usage")
                if (usage != null) {
                    prefs.edit().putInt("last_tokens_in", usage.optInt("prompt_tokens", 0)).putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + usage.optInt("prompt_tokens", 0)).apply()
                    prefs.edit().putInt("last_tokens_out", usage.optInt("completion_tokens", 0)).putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + usage.optInt("completion_tokens", 0)).apply()
                } else {
                    prefs.edit().putInt("last_tokens_in", (body.toString().length / 4)).putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + (body.toString().length / 4)).apply()
                    prefs.edit().putInt("last_tokens_out", (reply.length / 4)).putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + (reply.length / 4)).apply()
                }
                reply
            } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("OpenAi", "sendMessage failed", e)
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: ((String) -> Unit)?
    ) {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val result = HttpRetry.retry("OpenAi") {
            val messages = buildMessages(history, userMessage, systemPrompt)
            val body = buildRequestBody(model, messages, streaming = true)
            val httpReq = HttpClient.postJson("$baseUrl/chat/completions", body.toString(),
                "Authorization" to "Bearer $apiKey")
            val httpResp = HttpClient.client.newCall(httpReq).execute()
            if (!httpResp.isSuccessful) {
                val errBody = httpResp.body?.string() ?: ""
                httpResp.close()
                throw java.io.IOException("HTTP ${httpResp.code} - $errBody")
            }
            val reader = httpResp.body?.byteStream()?.bufferedReader()
                ?: throw java.io.IOException("No response body")
            var line: String?
            val full = StringBuilder()
            val toolCallAccum = mutableMapOf<Int, StringBuilder>()
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
            } finally { reader.close(); httpResp.close() }
            if (toolCallAccum.isNotEmpty()) {
                val sb = StringBuilder(full.toString().trim())
                for ((_, acc) in toolCallAccum) {
                    val parts = acc.toString().split("\n", limit = 2)
                    val tName = parts.getOrElse(0) { "unknown" }
                    val tArgs = parts.getOrElse(1) { "{}" }
                    sb.append("tool:" + tName + " " + tArgs + "")
                }
                sb.toString().trim()
            } else {
                full.toString()
            }
            }
            onDone(result)
        } catch (e: Exception) {
            android.util.Log.w("OpenAi", "sendMessageStream failed", e)
            onDone("")
        }
    }

    private fun buildRequestBody(
        model: String, messages: JSONArray, streaming: Boolean,
        tools: org.json.JSONArray? = null
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("stream", streaming)
        // 从 SharedPreferences 读取可选生成参数（UI 设置后可动态生效）
        prefs.getString("ai_temperature", null)?.toFloatOrNull()?.let { put("temperature", it) }
        prefs.getString("ai_top_p", null)?.toFloatOrNull()?.let { put("top_p", it) }
        prefs.getString("ai_max_tokens", null)?.toIntOrNull()?.let { put("max_completion_tokens", it) }
        prefs.getInt("ai_seed", -1).takeIf { it >= 0 }?.let { put("seed", it) }
        prefs.getString("ai_frequency_penalty", null)?.toFloatOrNull()?.let { put("frequency_penalty", it) }
        prefs.getString("ai_presence_penalty", null)?.toFloatOrNull()?.let { put("presence_penalty", it) }
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
            if (tokCount + t > maxTokens) break
            tokCount += t
            recent.add(0, msg)
        }
        for ((i, msg) in recent.withIndex()) {
            val needReinject = ctxWindow <= 0 && !prefs.getBoolean("long_context_mode", true) && systemPrompt.isNotBlank()
            if (needReinject && i > 0 && i % 10 == 0) {
                put(JSONObject().apply { put("role", "system"); put("content", "【人设提醒】$systemPrompt") })
            }
            put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
        }
        put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
    }
}
