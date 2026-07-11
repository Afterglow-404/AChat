package com.aftglw.devapi.feature.script

import android.content.Context

/**
 * 剧本通关记录管理。
 * 记录玩家已完成的剧本，用于解锁链判断和 BrowserPage 展示。
 */
object ScriptProgress {

    private const val PREFS_NAME = "script_progress"
    private const val COMPLETED_KEY = "completed_scripts"

    /** 标记剧本为已通关 */
    fun markCompleted(ctx: Context, scriptId: String) {
        val set = getCompleted(ctx).toMutableSet()
        set.add(scriptId)
        save(ctx, set)
    }

    /** 检查剧本是否已通关 */
    fun isCompleted(ctx: Context, scriptId: String): Boolean {
        return scriptId in getCompleted(ctx)
    }

    /** 检查解锁条件是否满足 */
    fun isUnlocked(ctx: Context, condition: UnlockCondition): Boolean {
        return when (condition.type) {
            "adventure_completed" -> condition.adventureFolder in getCompleted(ctx)
            else -> true
        }
    }

    /** 检查剧本的所有解锁条件是否满足 */
    fun isScriptUnlocked(ctx: Context, conditions: List<UnlockCondition>): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { isUnlocked(ctx, it) }
    }

    /** 获取所有已通关 ID */
    fun getCompleted(ctx: Context): Set<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(COMPLETED_KEY, "") ?: ""
        return if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 重置单个剧本的通关记录 */
    fun resetScript(ctx: Context, scriptId: String) {
        val set = getCompleted(ctx).toMutableSet()
        set.remove(scriptId)
        save(ctx, set)
    }

    /** 重置所有进度（调试用） */
    fun resetAll(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(COMPLETED_KEY).apply()
    }

    private fun save(ctx: Context, set: Set<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(COMPLETED_KEY, set.joinToString(",")).apply()
    }
}
