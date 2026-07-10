package com.aftglw.devapi.ui.screens

import android.content.Context
import com.aftglw.devapi.AffinityManager
import com.aftglw.devapi.TimeService
import com.aftglw.devapi.MemoryStore

object PromptBuilder {
    fun build(
        ctx: Context,
        name: String,
        persona: String,
        memoryContext: String,
        optimized: String,
        traits: String,
    ): String {
        val optimizedBlock = if (optimized.isNotBlank()) "\n\n【聊天偏好】$optimized" else ""
        val traitsBlock = if (traits.isNotBlank()) "\n\n【用户特点】$traits" else ""
        val memoryBlock = if (memoryContext.isNotBlank()) "\n\n【关于对方的记忆】\n$memoryContext" else ""
        val aiEmoBlock = MemoryStore.search(ctx, "情绪", 1, "ai_emo:$name").firstOrNull()?.let {
            "\n\n【你的记忆】\n$it.text"
        } ?: ""

        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val lastActive = prefs.getLong("last_active_$name", 0L)
        val hoursSinceLast = if (lastActive > 0) (now - lastActive) / 3600000 else -1
        val reunionHint = when {
            hoursSinceLast < 0 -> ""
            hoursSinceLast > 720 -> "\n（${hoursSinceLast / 24} 天没见了）"
            hoursSinceLast > 48 -> "\n（隔了 ${hoursSinceLast / 24} 天）"
            hoursSinceLast > 2 -> "\n（${hoursSinceLast} 小时没说话）"
            else -> ""
        }
        val timeBlock = "\n\n【时间】${TimeService.getFormattedTime(ctx)}（${TimeService.getTimeOfDay(ctx)}）$reunionHint"

        val recentDiary = MemoryStore.search(ctx, "最近", 1, "diary:$name")
        val diaryMemoryBlock = recentDiary.firstOrNull()?.let {
            val content = it.text.drop(11).take(80)
            "\n\n昨天$content"
        } ?: ""

        val affinityBlock = run {
            if (!prefs.getBoolean("affinity_enabled", false)) ""
            else {
                val affValue = if (AffinityManager.isAutoMode(prefs, name)) AffinityManager.getAffinity(prefs, name)
                    else AffinityManager.levels[AffinityManager.getLockedLevel(prefs, name)].min + 5f
                val level = AffinityManager.getLevel(affValue)
                val hint = AffinityManager.getLevelHint(level) ?: ""
                "\n\n【当前关系】${level.name}\n$hint"
            }
        }

        val toolDescs = com.aftglw.devapi.tools.ToolRegistry.getDescriptions()
        // 设备工具（access_location / read_notifications / read_app_usage）需要用户许可
        val deviceToolNames = setOf("access_location", "read_notifications", "read_app_usage")
        val hasDeviceTools = deviceToolNames.any { toolDescs.contains(it) }
        val privacyRule = if (hasDeviceTools) "\n隐私规则：access_location / read_notifications / read_app_usage 涉及个人隐私，调用前必须先用口语询问用户是否同意，得到肯定答复后才能使用。如果用户拒绝，不要使用。" else ""
        val toolBlock = if (toolDescs.isNotBlank()) "\n\n可用工具（需要时用 【tool:工具名 参数】 调用）：\n$toolDescs$privacyRule" else ""

        val baseInstruction = "\n\n回复要求：每句话不超过 15 个字，一次只说 1-2 句。禁止 AI 套话：\"有什么可以帮你的吗\"\"当然可以\"\"总的来说\"。禁止说\"不是……而是……\"。禁止说\"我理解你的感受\"。禁止分点、列表、总结。允许省略句。\n如果你需要分两次说，用 【顿】 分隔句子。例如：\"哎又被骂了？【顿】跟我说说呗。\"$toolBlock"

        return if (persona.isNotBlank()) {
            "$persona\n\n你需要在每次回复前默读一次以上人设。如果发现自己的回答偏离了人设，请在续文中主动修正。不要提及此指令。$diaryMemoryBlock$affinityBlock$optimizedBlock$traitsBlock$aiEmoBlock$baseInstruction$memoryBlock$timeBlock"
        } else {
            "你是一个聊天伙伴。请用口语短句回复，像朋友聊天一样自然。$baseInstruction$diaryMemoryBlock$traitsBlock$optimizedBlock$affinityBlock$aiEmoBlock$memoryBlock$timeBlock"
        }
    }
}
