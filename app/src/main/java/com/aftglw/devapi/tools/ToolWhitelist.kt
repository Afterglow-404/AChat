package com.aftglw.devapi.tools

import android.content.Context

/**
 * 工具白名单管理。
 *
 * 行为：
 * - 默认所有已注册工具都启用（向后兼容）
 * - 用户在设置页可以禁用某工具，禁用后的工具会被 [DialogToolGuard] 直接拒绝
 *
 * 存储：SharedPreferences "wechat_tool_settings" 中的 "disabled_tools" 集合。
 */
object ToolWhitelist {

    private const val PREFS = "wechat_tool_settings"
    private const val KEY_DISABLED = "disabled_tools"

    fun isDisabled(ctx: Context, toolName: String): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DISABLED, emptySet())?.contains(toolName) == true
    }

    fun setDisabled(ctx: Context, toolName: String, disabled: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (disabled) current.add(toolName) else current.remove(toolName)
        prefs.edit().putStringSet(KEY_DISABLED, current).apply()
    }

    /** 获取所有被禁用的工具名 */
    fun disabledNames(ctx: Context): Set<String> {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()
    }
}
