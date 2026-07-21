package com.aftglw.devapi.tools

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次工具确认请求。
 *
 * @property tool 工具实例
 * @property argsJson 参数 JSON（用于展示给用户）
 * @property riskLevel 风险等级
 * @property response 调用方挂起等待的 deferred；UI 应在用户点击后调用 [response].complete(...)
 */
data class ToolConfirmationRequest(
    val tool: AiTool,
    val argsJson: String,
    val riskLevel: RiskLevel,
    val response: CompletableDeferred<Boolean>
)

/**
 * 通过 Compose StateFlow 桥接 UI 的工具确认守卫。
 *
 * 工作流程：
 * 1. Agent 在执行工具前调用 [confirm]
 * 2. [confirm] 检查白名单 → 已禁用则直接返回 false
 * 3. LOW 风险直接放行；MEDIUM/HIGH 创建 [ToolConfirmationRequest] 推送到 [_pending] flow
 * 4. UI（ChatScreen）订阅 [pending]，弹出 AlertDialog，用户点击后 complete deferred
 * 5. [confirm] 返回用户的选择
 *
 * 这种方式让 Agent 协程能干净地挂起等待 UI 输入，无需在 Agent 中耦合 Compose 状态。
 */
class DialogToolGuard(
    private val appContext: Context
) : ToolGuard {

    private val _pending = MutableStateFlow<ToolConfirmationRequest?>(null)
    val pending: StateFlow<ToolConfirmationRequest?> = _pending.asStateFlow()

    override suspend fun confirm(tool: AiTool, argsJson: String): Boolean {
        // 1. 白名单检查
        if (ToolWhitelist.isDisabled(appContext, tool.name)) return false

        // 2. LOW 风险直接放行
        if (tool.riskLevel == RiskLevel.LOW) return true

        // 3. MEDIUM/HIGH 弹窗确认
        val deferred = CompletableDeferred<Boolean>()
        val req = ToolConfirmationRequest(tool, argsJson, tool.riskLevel, deferred)
        _pending.value = req
        return deferred.await().also {
            // 消费完毕后清空
            if (_pending.value === req) _pending.value = null
        }
    }

    /** UI 调用：用户点了允许/拒绝 */
    fun respond(request: ToolConfirmationRequest, allow: Boolean) {
        request.response.complete(allow)
    }
}

/**
 * 限制型守卫：高于 [maxAllowedRisk] 的工具自动拒绝，其余自动放行。
 *
 * 用于群聊等无人值守场景 — 群成员的 AI 互相调用时不应弹窗打扰用户，
 * 但也不应让它们静默执行 HIGH 风险工具（如发通知、运行 shell）。
 *
 * 默认 [maxAllowedRisk] = [RiskLevel.MEDIUM]，即只允许 LOW + MEDIUM。
 *
 * 例外：[OPEN_BOOK_TOOL_NAMES] 中的隐私敏感工具（位置/通知/应用使用统计）
 * 即使风险等级为 MEDIUM 也一律拒绝 —— 这些工具要求用户停留在屏幕前确认，
 * 与群聊"无人值守"语义冲突。
 */
class RestrictedToolGuard(
    private val appContext: Context,
    private val maxAllowedRisk: RiskLevel = RiskLevel.MEDIUM
) : ToolGuard {

    override suspend fun confirm(tool: AiTool, argsJson: String): Boolean {
        // 白名单仍然生效
        if (ToolWhitelist.isDisabled(appContext, tool.name)) return false
        // 隐私敏感工具在群聊无人值守场景下一律拒绝
        if (tool.name in OPEN_BOOK_TOOL_NAMES) return false
        return tool.riskLevel.ordinal <= maxAllowedRisk.ordinal
    }
}
