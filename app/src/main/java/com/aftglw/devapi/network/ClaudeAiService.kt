package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ClaudeAiService(context: Context) : AiService {

    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            HttpRetry.retry("Claude") {
            val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = false)
            val conn = URL("$baseUrl/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true; conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            HttpRetry.checkResponse(conn)
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(response)
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
                        sb.append("【tool:${name} ${args}】")
                    }
                }
                reply = sb.toString().trim()
            }
            if (reply.isNotBlank()) {
                prefs.edit().putInt("last_tokens_in", (body.toString().length / 4)).apply()
                prefs.edit().putInt("last_tokens_out", (reply.length / 4)).apply()
                reply.trim()
            } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("Claude", "sendMessage failed", e)
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        onChunk: (String) -> Unit, onDone: (String) -> Unit,
        onError: ((String) -> Unit)?
    ) {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val result = HttpRetry.retry("Claude") {
            val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = true)
            val conn = URL("$baseUrl/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true; conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            HttpRetry.checkResponse(conn)
            val reader = conn.inputStream.bufferedReader()
            var line: String?
            val full = StringBuilder()
            try {
                var pendingEvent = ""
            var toolName = ""
            var toolIdx = -1
            val toolArgs = StringBuilder()
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
                                        full.append("【tool:" + toolName + " " + toolArgs.toString() + "】")
                                        toolName = ""
                                        toolArgs.setLength(0)
                                    }
                                }
                            }
                            pendingEvent = ""
                        }
                    }
                }
            } finally { reader.close(); conn.disconnect() }
            full.toString()
            }
            onDone(result)
        } catch (e: Exception) {
            android.util.Log.w("Claude", "sendMessageStream failed", e)
            onDone("")
        }
    }

    private fun buildRequestBody(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        model: String, streaming: Boolean
    ): JSONObject {
        val messages = JSONArray().apply {
            val longContext = prefs.getBoolean("long_context_mode", true)
            val recent = history.takeLast(if (longContext) 20 else 10)
            for (msg in recent) { put(JSONObject().apply { put("role", msg.role); put("content", msg.content) }) }
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }

        // 从 SharedPreferences 读取 max_tokens（默认 4096）
        val maxTokens = prefs.getString("ai_max_tokens", null)?.toIntOrNull() ?: 4096

        return JSONObject().apply {
            put("model", model)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", messages)
            put("max_tokens", maxTokens)
            if (streaming) put("stream", true)
            // 可选生成参数
            prefs.getString("ai_temperature", null)?.toFloatOrNull()?.let { put("temperature", it) }
            prefs.getString("ai_top_p", null)?.toFloatOrNull()?.let { put("top_p", it) }
            prefs.getInt("ai_seed", -1).takeIf { it >= 0 }?.let { put("seed", it) }
        }
    }
}
