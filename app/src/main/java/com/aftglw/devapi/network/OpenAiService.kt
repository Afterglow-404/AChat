package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiService(context: Context) : AiService {

    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            val messages = buildMessages(history, userMessage, systemPrompt)
            val body = buildRequestBody(model, messages, streaming = false)
            val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

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
                    prefs.edit().putInt("last_tokens_in", usage.optInt("prompt_tokens", 0)).apply()
                    prefs.edit().putInt("last_tokens_out", usage.optInt("completion_tokens", 0)).apply()
                } else {
                    prefs.edit().putInt("last_tokens_in", (body.toString().length / 4)).apply()
                    prefs.edit().putInt("last_tokens_out", (reply.length / 4)).apply()
                }
                reply
            } else null
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
            val messages = buildMessages(history, userMessage, systemPrompt)
            val body = buildRequestBody(model, messages, streaming = true)
            val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val reader = conn.inputStream.bufferedReader()
            var line: String?
            val full = StringBuilder()
            try {
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    if (!text.startsWith("data: ")) continue
                    val data = text.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val delta = try {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content", "") ?: ""
                    } catch (_: Exception) { "" }
                    if (delta.isNotEmpty()) { full.append(delta); onChunk(delta) }
                }
            } finally { reader.close(); conn.disconnect() }
            onDone(full.toString())
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
        val windowSize = if (ctxWindow > 0) ctxWindow else if (prefs.getBoolean("long_context_mode", true)) 20 else 10
        val recent = history.takeLast(windowSize)
        for ((i, msg) in recent.withIndex()) {
            if (ctxWindow <= 0 && !prefs.getBoolean("long_context_mode", true) && i > 0 && i % 10 == 0 && systemPrompt.isNotBlank()) {
                put(JSONObject().apply { put("role", "system"); put("content", "【人设提醒】$systemPrompt") })
            }
            put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
        }
        put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
    }
}
