package com.aftglw.devapi.network

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class ClaudeAiService(context: Context) : AiService {

    private val prefs = context.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    override fun sendMessage(history: List<ChatMessage>, userMessage: String, systemPrompt: String, onError: ((String) -> Unit)?, toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?): String? {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return try {
            HttpRetry.retry("Claude") {
            val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = false, tools = buildToolsArray())
            val request = HttpClient.postJson("$baseUrl/messages", body.toString(),
                "x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
            val response = HttpClient.execute(request)
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
                prefs.edit()
                    .putInt("last_tokens_in", estIn)
                    .putInt("total_tokens_in", prefs.getInt("total_tokens_in", 0) + estIn)
                    .putInt("last_tokens_out", reply.length / 4)
                    .putInt("total_tokens_out", prefs.getInt("total_tokens_out", 0) + reply.length / 4)
                    .apply()
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
        onChunk: (String) -> Unit, onDone: (String) -> Unit,
        onError: ((String) -> Unit)?,
        toolCallsOut: MutableList<com.aftglw.devapi.network.ToolCall>?
    ) {
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet"
        if (baseUrl.isEmpty() || apiKey.isEmpty()) { onDone(""); return }

        try {
            val result = HttpRetry.retry("Claude") {
            val body = buildRequestBody(history, userMessage, systemPrompt, model, streaming = true, tools = buildToolsArray())
            val httpReq = HttpClient.postJson("$baseUrl/messages", body.toString(),
                "x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
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
            } finally { reader.close(); httpResp.close() }
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
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
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

private fun messagesLength(body: JSONObject) = body.optJSONArray("messages")?.length() ?: 0 * 4
