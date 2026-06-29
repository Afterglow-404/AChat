package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ClaudeAiService(private val context: Context) : AiService {

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String): String? {
        val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"

        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            // Claude 格式：system 是顶层字段，messages 只含 user/assistant
            val messages = JSONArray()
            val longContext = prefs.getBoolean("long_context_mode", true)
            val recent = history.takeLast(if (longContext) 20 else 10)
            for (msg in recent) {
                messages.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })

            val body = JSONObject().apply {
                put("model", model)
                if (systemPrompt.isNotBlank()) {
                    put("system", systemPrompt)
                }
                put("messages", messages)
                put("max_tokens", 4096)
            }

            val conn = URL("$baseUrl/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            val reply = json.optJSONObject("content")?.optString("text", "")
                ?: json.optJSONArray("content")?.optJSONObject(0)?.optString("text", "")
                ?: json.optString("text", "")
            if (reply.isNotBlank()) {
                prefs.edit().putInt("last_tokens_in", (body.toString().length / 4)).apply()
                prefs.edit().putInt("last_tokens_out", (reply.length / 4)).apply()
                reply.trim()
            } else null
        } catch (e: Exception) {
            /* 非关键，API 失败不影响聊天 */
            null
        }
    }
}
