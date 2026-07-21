package com.aftglw.devapi.core.ai
import com.aftglw.devapi.core.affect.AffectiveEngine
import com.aftglw.devapi.core.time.TimeService
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.mood.AffinityManager
import com.aftglw.devapi.core.worldbook.WorldbookStore

import android.content.Context

object PromptBuilder {
    private val DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

    suspend fun build(
        ctx: Context,
        name: String,
        persona: String,
        memoryContext: String,
        optimized: String,
        traits: String,
        recentUserText: String = "",
    ): String {
        val optimizedBlock = if (optimized.isNotBlank()) "\n\n【聊天偏好】$optimized" else ""
        val traitsBlock = if (traits.isNotBlank()) "\n\n【用户特点】$traits" else ""
        val memoryBlock = if (memoryContext.isNotBlank()) "\n\n【关于对方的记忆】\n$memoryContext" else ""
        // 反思产物：对话本质洞察 + AI 自身情绪记忆，合并为【你的记忆】块注入
        // 改用 listRecentByTopic 按 timestamp 倒序确定性取最新一条，避免 embedding 检索的不确定性
        val insightText = MemoryStore.listRecentByTopic("insight:$name", 1).firstOrNull()?.text ?: ""
        val aiEmoText = MemoryStore.listRecentByTopic("ai_emo:$name", 1).firstOrNull()?.text ?: ""
        val reflectionBlock = buildString {
            if (insightText.isNotBlank() || aiEmoText.isNotBlank()) {
                append("\n\n【你的记忆】")
                if (insightText.isNotBlank()) append("\n对话本质：$insightText")
                if (aiEmoText.isNotBlank()) append("\n你的情绪：$aiEmoText")
            }
        }

        // 世界书：常驻条目 + 关键词命中条目，按优先级拼装
        val worldbookText = WorldbookStore.matchForPrompt(ctx, name, recentUserText)
        val worldbookBlock = if (worldbookText.isNotBlank()) "\n\n【世界观】\n$worldbookText" else ""

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

        val recentDiaries = MemoryStore.listRecentByTopic("diary:$name", 3)
        val diaryMemoryBlock = recentDiaries.firstOrNull()?.let {
            val parts = it.text.split(" ", limit = 2)
            if (parts.size >= 2) {
                val dateStr = parts[0]
                val text = parts[1].take(80)
                val days = try {
                    val date = DATE_FMT.parse(dateStr) ?: return@let ""
                    val diff = (System.currentTimeMillis() - date.time) / 86400000L
                    diff.toInt()
                } catch (_: Exception) { -1 }
                val prefix = when (days) {
                    0 -> "今天"
                    1 -> "昨天"
                    else -> "$days 天前"
                }
                "\n\n$prefix：$text"
            } else {
                "\n\n昨天${it.text.take(80)}"
            }
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

        // AffectiveField 块（P0 新增，设计文档 2.2.5 + 2.3.5 + 14.4.3）
        // 注入：4 维场状态 + RhythmSensor stateHint 三层 + PendingEvents 软提醒
        val affectiveBlock = run {
            val snapshot = AffectiveEngine.snapshot(ctx, name)
            snapshot.toPromptBlocks()
        }

        val toolDescs = com.aftglw.devapi.tools.ToolRegistry.getDescriptions()
        // 设备工具（access_location / read_notifications / read_app_usage）需要用户许可
        val deviceToolNames = setOf("access_location", "read_notifications", "read_app_usage")
        val hasDeviceTools = deviceToolNames.any { com.aftglw.devapi.tools.ToolRegistry.get(it) != null }
        val privacyRule = if (hasDeviceTools) "\n隐私规则：access_location / read_notifications / read_app_usage 涉及个人隐私，调用前必须先用口语询问用户是否同意，得到肯定答复后才能使用。如果用户拒绝，不要使用。" else ""
        val toolBlock = if (toolDescs.isNotBlank()) "\n\n可用工具（需要时用 【tool:工具名 参数】 调用）：\n$toolDescs$privacyRule" else ""

        val stickerHint = "\n你可以用 【sticker:呆猫八条:标签】 来发贴纸表达情绪，例如：【sticker:呆猫八条:委屈】【sticker:呆猫八条:开心】。单独发一行效果更好。支持标签：开心、委屈、无语、疲惫、思考、可爱、激动、期待、震惊、生气、倔强、贴贴等。"
        val baseInstruction = "\n\n回复要求：每句话不超过 15 个字，一次只说 1-2 句。禁止 AI 套话：\"有什么可以帮你的吗\"\"当然可以\"\"总的来说\"。禁止说\"不是……而是……\"。禁止说\"我理解你的感受\"。禁止分点、列表、总结。允许省略句。\n如果你需要分两次说，用 【顿】 分隔句子。例如：\"哎又被骂了？【顿】跟我说说呗。\"$stickerHint$toolBlock"

        return if (persona.isNotBlank()) {
            "$persona\n\n你需要在每次回复前默读一次以上人设。如果发现自己的回答偏离了人设，请在续文中主动修正。不要提及此指令。$diaryMemoryBlock$affinityBlock$affectiveBlock$optimizedBlock$traitsBlock$reflectionBlock$worldbookBlock$baseInstruction$memoryBlock$timeBlock"
        } else {
            "你是一个聊天伙伴。请用口语短句回复，像朋友聊天一样自然。$baseInstruction$worldbookBlock$diaryMemoryBlock$traitsBlock$optimizedBlock$affinityBlock$affectiveBlock$reflectionBlock$memoryBlock$timeBlock"
        }
    }
}
