package com.aftglw.devapi.core.ai

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiService
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.network.ToolCall
import com.aftglw.devapi.tools.AiTool
import com.aftglw.devapi.tools.NoOpToolGuard
import com.aftglw.devapi.tools.ToolGuard
import com.aftglw.devapi.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * 独立的 Agent runtime，封装多轮 tool-use 推理循环。
 *
 * 原生 function calling 优先（OpenAI/Claude），正则兜底（旧版 API）。
 *
 * @param toolGuard 工具调用前的拦截器，用于白名单检查和高风险工具的用户确认。
 *                  默认 [NoOpToolGuard] 全部放行；UI 层应注入 [com.aftglw.devapi.tools.DialogToolGuard]
 *                  以启用弹窗确认。
 * @param toolTimeoutMs 单次工具执行的超时时间，超时则返回错误。默认 30 秒。
 */
class Agent(
    private val ctx: Context,
    private val service: AiService = AiServiceFactory.getService(),
    /** 最大工具调用轮数（含首轮） */
    val maxToolRounds: Int = 5,
    private val toolGuard: ToolGuard = NoOpToolGuard,
    private val toolTimeoutMs: Long = 30_000L,
) {
    var onChunk: ((String) -> Unit)? = null
    var onToolStart: ((name: String, args: String) -> Unit)? = null
    var onToolEnd: ((name: String, result: String) -> Unit)? = null
    /** 工具被拒绝（白名单或用户拒绝）时回调，UI 可据此提示 */
    var onToolDenied: ((name: String, reason: String) -> Unit)? = null

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
                // service.sendMessage 自 Phase 3 起改为 suspend，内部已用 HttpRetry.retrySuspend；
                // Agent.prompt() 调用方已在 Dispatchers.IO 协程中，此处无需再包 withContext(IO)
                val reply = service.sendMessage(
                    loopHistory.toList(), currentMsg, systemPrompt,
                    onError = { err -> lastError = err },
                    toolCallsOut = nativeToolCalls
                )
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
                    val tool = ToolRegistry.get(tc.name) ?: continue
                    // 原生 tool_call 参数是 JSON，直接解析
                    val args = try {
                        if (tc.arguments.trimStart().startsWith("{")) {
                            JSONObject(tc.arguments)
                        } else {
                            withContext(Dispatchers.IO) { tool.parseTextArgs(tc.arguments) }
                        }
                    } catch (_: Exception) { org.json.JSONObject() }

                    val result = executeToolGuarded(tool, tc.arguments, args)
                    results.add("【${tc.name}】$result")
                    toolCalls.add(ToolCallInfo(tc.name, tc.arguments, result))
                }
            } else {
                // 正则兜底
                val toolMatches = Regex("""【tool:(\w+)\s*(.*?)】""").findAll(reply).toList()
                cleanText = reply.replace(Regex("""【tool:\w+\s*.*?】"""), "").trim()
                for (m in toolMatches) {
                    val toolName = m.groupValues[1]
                    val rawArgs = m.groupValues[2].trim()
                    val tool = ToolRegistry.get(toolName) ?: continue
                    val args = withContext(Dispatchers.IO) { tool.parseTextArgs(rawArgs) }
                    val result = executeToolGuarded(tool, rawArgs, args)
                    results.add("【$toolName】$result")
                    toolCalls.add(ToolCallInfo(toolName, rawArgs, result))
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

    /**
     * 带守卫和超时的工具执行。
     *
     * 流程：
     * 1. 调用 [toolGuard.confirm] 检查白名单 + 用户确认
     * 2. 拒绝时返回提示并触发 [onToolDenied]
     * 3. 通过后用 [withTimeout] 包裹执行，超时返回错误
     * 4. 前后回调 [onToolStart] / [onToolEnd]
     */
    private suspend fun executeToolGuarded(tool: AiTool, argsRaw: String, args: JSONObject): String {
        // 守卫：白名单 + 用户确认
        val allowed = try {
            toolGuard.confirm(tool, argsRaw)
        } catch (e: Exception) {
            onToolDenied?.invoke(tool.name, "守卫异常：${e.message}")
            return "❌ 工具 ${tool.name} 守卫检查异常：${e.message}"
        }
        if (!allowed) {
            onToolDenied?.invoke(tool.name, "用户已拒绝或工具被禁用")
            return "❌ 工具 ${tool.name} 未获授权（用户拒绝或已被禁用）"
        }

        onToolStart?.invoke(tool.name, argsRaw)
        val result = try {
            withTimeout(toolTimeoutMs) {
                withContext(Dispatchers.IO) { tool.execute(ctx, args) }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "❌ 工具 ${tool.name} 执行超时（${toolTimeoutMs}ms）"
        } catch (e: Exception) {
            "❌ 工具 ${tool.name} 执行失败：${e.message}"
        }
        onToolEnd?.invoke(tool.name, result)
        return result
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
