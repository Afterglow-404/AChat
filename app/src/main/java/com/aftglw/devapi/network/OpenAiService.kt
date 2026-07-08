package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiService(private val context: Context) : AiService {

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String): String? {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            val messages = buildMessages(history, userMessage, systemPrompt, prefs)
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
            }
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
                val reply = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
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
            null
        }
    }

    override fun sendMessageStream(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit
    ) {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val messages = buildMessages(history, userMessage, systemPrompt, prefs)
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("stream", true)
            }
            val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 0
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
        } catch (e: Exception) { onDone("") }
    }

    private fun buildMessages(
        history: List<ChatMessage>, userMessage: String, systemPrompt: String,
        prefs: android.content.SharedPreferences
    ): JSONArray = JSONArray().apply {
        if (systemPrompt.isNotBlank()) {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        }
        val longContext = prefs.getBoolean("long_context_mode", true)
        val recent = history.takeLast(if (longContext) 20 else 10)
        for ((i, msg) in recent.withIndex()) {
            if (!longContext && i > 0 && i % 10 == 0 && systemPrompt.isNotBlank()) {
                put(JSONObject().apply { put("role", "system"); put("content", "【人设提醒】$systemPrompt") })
            }
            put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
        }
        put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
    }
}
