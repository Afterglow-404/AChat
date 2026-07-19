package com.aftglw.devapi.tools

/**
 * 工具风险等级。
 *
 * - [LOW]：纯读取或本地计算，无副作用（如时间、电量、计算器、笔记读取）
 * - [MEDIUM]：读取隐私数据或联网（如位置、通知、应用使用、网络搜索）
 * - [HIGH]：有外部副作用或执行任意代码（如发消息、运行 shell、HTTP 调用外部服务）
 *
 * 高风险工具默认会触发用户确认弹窗，白名单未启用的工具会被直接拒绝。
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * 工具调用前的拦截器。
 *
 * 实现方可以在 [confirm] 中弹窗询问用户、检查白名单、记录审计日志等。
 * 返回 false 表示拒绝执行该工具。
 */
interface ToolGuard {
    /**
     * @param tool 工具实例
     * @param argsJson 参数 JSON 字符串（用于展示给用户）
     * @return true 允许执行，false 拒绝
     */
    suspend fun confirm(tool: AiTool, argsJson: String): Boolean
}

/** 默认实现：LOW/MEDIUM 自动通过，HIGH 也自动通过（保持兼容；UI 层应替换为 DialogToolGuard） */
object NoOpToolGuard : ToolGuard {
    override suspend fun confirm(tool: AiTool, argsJson: String): Boolean = true
}
