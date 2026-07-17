package com.aftglw.devapi.core.ai

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiService
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 独立的 Agent runtime，封装多轮 tool-use 推理循环。
 *
 * 使用方式：
 * ```
 * val agent = Agent(context)
 * agent.onChunk = { chunk -> /* 流式展示 */ }
 * val result = agent.prompt(history, userMessage, systemPrompt)
 * ```
 *
 * 当前已接入的调用方：
 * - ChatScreen（主聊天）
 */
class Agent(
    private val ctx: Context,
    private val service: AiService = AiServiceFactory.getService(),
    /** 最大工具调用轮数（含首轮） */
    val maxToolRounds: Int = 5,
) {
    /** 流式文本块回调（仅首轮触发） */
    var onChunk: ((String) -> Unit)? = null

    /** 工具执行开始时回调 */
    var onToolStart: ((name: String, args: String) -> Unit)? = null

    /** 工具执行完成时回调 */
    var onToolEnd: ((name: String, result: String) -> Unit)? = null

    /**
     * 执行 Agent 推理循环。
     *
     * @param history 对话历史（不含本次用户消息）
     * @param userMessage 用户本次输入
     * @param systemPrompt 系统提示词
     * @return AgentResult，含最终文本和工具调用记录
     */
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

            if (round == 0) {
                // 首轮流式输出
                service.sendMessageStream(
                    loopHistory.toList(), currentMsg, systemPrompt,
                    onChunk = { chunk ->
                        sb.append(chunk)
                        onChunk?.invoke(chunk)
                    },
                    onDone = { full -> latch.complete(full.ifEmpty { null }) },
                    onError = { err -> lastError = err }
                )
            } else {
                // 后续轮次非流式（工具结果回环，通常简短）
                val reply = withContext(Dispatchers.IO) {
                    service.sendMessage(
                        loopHistory.toList(), currentMsg, systemPrompt,
                        onError = { err -> lastError = err }
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

            // 提取所有 【tool:xxx】 调用
            val toolMatches = Regex("""【tool:(\w+)\s*(.*?)】""").findAll(reply).toList()
            if (toolMatches.isEmpty()) {
                // 无工具调用 → 这就是最终回复
                return AgentResult(reply.trim(), toolCalls)
            }

            // 执行所有工具
            val cleanText = reply.replace(Regex("""【tool:\w+\s*.*?】"""), "").trim()
            val results = mutableListOf<String>()
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

            // 将中间消息加入 loopHistory，下一轮 LLM 可感知
            if (cleanText.isNotBlank()) {
                loopHistory.add(ChatMessage("assistant", cleanText))
            }
            // 工具结果作为下一轮 userMessage 传入，避免末尾空消息
            currentMsg = "工具返回结果：\n${results.joinToString("\n")}"
        }

        // 超过最大轮次仍未返回纯文本
        return AgentResult(
            text = "",
            toolCalls = toolCalls,
            error = "Agent 循环超过上限 ${maxToolRounds} 轮"
        )
    }
}

/** Agent 的一次执行结果 */
data class AgentResult(
    /** 最终回复文本（空字符串表示异常） */
    val text: String,
    /** 本轮调用的工具记录 */
    val toolCalls: List<ToolCallInfo> = emptyList(),
    /** 错误信息（null 表示成功） */
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && text.isNotBlank()
}

/** 单次工具调用记录 */
data class ToolCallInfo(
    val name: String,
    val args: String,
    val result: String,
)
