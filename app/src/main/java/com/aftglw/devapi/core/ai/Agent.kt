package com.aftglw.devapi.core.ai

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiService
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.network.ToolCall
import com.aftglw.devapi.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 独立的 Agent runtime，封装多轮 tool-use 推理循环。
 *
 * 原生 function calling 优先（OpenAI/Claude），正则兜底（旧版 API）。
 */
class Agent(
    private val ctx: Context,
    private val service: AiService = AiServiceFactory.getService(),
    /** 最大工具调用轮数（含首轮） */
    val maxToolRounds: Int = 5,
) {
    var onChunk: ((String) -> Unit)? = null
    var onToolStart: ((name: String, args: String) -> Unit)? = null
    var onToolEnd: ((name: String, result: String) -> Unit)? = null

    suspend fun prompt(
        history: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String = "",
    ): AgentResult {
        val loopHistory = history.toMutableList()
        var currentMsg = userMessage
        val toolCalls = mutableListOf<ToolCallInfo>()
        var lastError: String? = null

        for (round in 0 until maxToolRounds) {
            val sb = StringBuilder()
            val latch = CompletableDeferred<String?>()
            lastError = null
            val nativeToolCalls = mutableListOf<ToolCall>()

            if (round == 0) {
                service.sendMessageStream(
                    loopHistory.toList(), currentMsg, systemPrompt,
                    onChunk = { chunk ->
                        sb.append(chunk)
                        onChunk?.invoke(chunk)
                    },
                    onDone = { full -> latch.complete(full.ifEmpty { null }) },
                    onError = { err -> lastError = err },
                    toolCallsOut = nativeToolCalls
                )
            } else {
                val reply = withContext(Dispatchers.IO) {
                    service.sendMessage(
                        loopHistory.toList(), currentMsg, systemPrompt,
                        onError = { err -> lastError = err },
                        toolCallsOut = nativeToolCalls
                    )
                }
                if (reply != null) sb.append(reply)
                latch.complete(if (reply.isNullOrEmpty()) null else reply)
            }

            val reply = latch.await()
            if (reply == null) {
                return AgentResult(
                    text = "",
                    toolCalls = toolCalls,
                    error = lastError ?: "第 ${round + 1} 轮 LLM 调用无返回"
                )
            }

            // 原生 tool_calls 优先
            if (nativeToolCalls.isEmpty() && !Regex("""【tool:""").containsMatchIn(reply)) {
                return AgentResult(reply.trim(), toolCalls)
            }

            val cleanText: String
            val results = mutableListOf<String>()

            if (nativeToolCalls.isNotEmpty()) {
                cleanText = reply.trim()
                for (tc in nativeToolCalls) {
                    val tool = ToolRegistry.get(tc.name)
                    if (tool != null) {
                        onToolStart?.invoke(tc.name, tc.arguments)
                        // 原生 tool_call 参数是 JSON，直接解析
                        val args = try {
                            if (tc.arguments.trimStart().startsWith("{")) {
                                JSONObject(tc.arguments)
                            } else {
                                withContext(Dispatchers.IO) { tool.parseTextArgs(tc.arguments) }
                            }
                        } catch (_: Exception) { org.json.JSONObject() }
                        val result = withContext(Dispatchers.IO) { tool.execute(ctx, args) }
                        results.add("【${tc.name}】$result")
                        toolCalls.add(ToolCallInfo(tc.name, tc.arguments, result))
                        onToolEnd?.invoke(tc.name, result)
                    }
                }
            } else {
                // 正则兜底
                val toolMatches = Regex("""【tool:(\w+)\s*(.*?)】""").findAll(reply).toList()
                cleanText = reply.replace(Regex("""【tool:\w+\s*.*?】"""), "").trim()
                for (m in toolMatches) {
                    val toolName = m.groupValues[1]
                    val rawArgs = m.groupValues[2].trim()
                    val tool = ToolRegistry.get(toolName)
                    if (tool != null) {
                        onToolStart?.invoke(toolName, rawArgs)
                        val args = withContext(Dispatchers.IO) { tool.parseTextArgs(rawArgs) }
                        val result = withContext(Dispatchers.IO) { tool.execute(ctx, args) }
                        results.add("【$toolName】$result")
                        toolCalls.add(ToolCallInfo(toolName, rawArgs, result))
                        onToolEnd?.invoke(toolName, result)
                    }
                }
            }

            if (cleanText.isNotBlank()) {
                loopHistory.add(ChatMessage("assistant", cleanText))
            }
            currentMsg = "工具返回结果：\n${results.joinToString("\n")}"
        }

        return AgentResult(
            text = "",
            toolCalls = toolCalls,
            error = "Agent 循环超过上限 ${maxToolRounds} 轮"
        )
    }
}

data class AgentResult(
    val text: String,
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && text.isNotBlank()
}

data class ToolCallInfo(
    val name: String,
    val args: String,
    val result: String,
)
