package com.aftglw.devapi.feature.chat
import com.aftglw.devapi.core.mood.MoodInfo
import com.aftglw.devapi.core.ai.PromptBuilder
import com.aftglw.devapi.core.mood.PostLLMProcessor
import com.aftglw.devapi.core.storage.ChatHistory
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.mood.MoodDetector
import com.aftglw.devapi.core.voice.VoiceRecorder
import com.aftglw.devapi.core.voice.VoiceSttHelper
import com.aftglw.devapi.core.voice.VoicePlayer
import com.aftglw.devapi.core.voice.VoiceTts
import com.aftglw.devapi.core.voice.CloudTtsService

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.aftglw.devapi.ui.utils.AnimationUtils
import com.aftglw.devapi.ui.utils.StaggeredEntrance
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiServiceFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun now() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

data class Bubble(val text: String, val isMe: Boolean, val time: String = now(), val mood: String? = null, val label: String = "", val stickerPath: String? = null, val voicePath: String? = null, val voiceDuration: Int = 0, val voiceTranscript: String? = null, val imagePath: String? = null)

private sealed class ChatSubPage {
    data object Chat : ChatSubPage()
    data object Info : ChatSubPage()
    data object Diary : ChatSubPage()
    data object Memory : ChatSubPage()
    data object Editor : ChatSubPage()
    data object Worldbook : ChatSubPage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(name: String, persona: String = "", avatarUri: String = "", id: String = "", characterFolder: String = "", thinkingMessage: String = "", showTimestamps: Boolean = true, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val chatKey = id.ifEmpty { name }
    val bubbles = remember { mutableStateListOf<Bubble>() }
    val history = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var currentSubPage by remember { mutableStateOf<ChatSubPage>(ChatSubPage.Chat) }
    // 本地维护 persona 状态：编辑器保存后可即时刷新，无需重进对话
    var currentPersona by remember { mutableStateOf(persona) }
    
    val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val model = remember { prefs.getString("ai_model", "deepsleep-cat") ?: "deepsleep-cat" }

    // 工具调用安全：高风险工具执行前通过 DialogToolGuard 弹窗询问用户
    val toolGuard = remember { com.aftglw.devapi.tools.DialogToolGuard(ctx.applicationContext) }
    val pendingToolReq by toolGuard.pending.collectAsState()

    val chatBgUri = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("chat_bg_uri", "")?.takeIf { it.isNotEmpty() }
    val chatBgBitmap = remember(chatBgUri) {
        chatBgUri?.let {
            try { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            catch (_: Exception) { null }
        }
    }

    LaunchedEffect(Unit) {
        MemoryStore.init(ctx)
        MoodDetector.init(ctx, name)
        com.aftglw.devapi.tools.ToolRegistry.init(ctx)
        com.aftglw.devapi.core.sticker.StickerEngine.init(ctx)
        // 一次性加载历史 + 触发归档检查（异步）
        val saved = ChatHistory.loadEntries(ctx, chatKey)
        bubbles.clear()
        saved.forEach { e ->
            val parts = e.text.split("【顿】").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1) {
                parts.forEach { bubbles.add(Bubble(it, e.isMe, e.time, imagePath = e.imagePath)) }
            } else {
                bubbles.add(
                    Bubble(
                        text = e.text,
                        isMe = e.isMe,
                        time = e.time,
                        imagePath = e.imagePath,
                        voicePath = e.voicePath,
                        voiceDuration = e.voiceDuration,
                        voiceTranscript = e.voiceTranscript
                    )
                )
            }
        }
        history.addAll(saved.map { e ->
            val role = if (e.isMe) "user" else "assistant"
            val content = (e.voiceTranscript?.takeIf { it.isNotBlank() } ?: e.text)
                .replace("【顿】", "").trim()
            val images = if (!e.imagePath.isNullOrEmpty()) listOf(e.imagePath) else emptyList()
            ChatMessage(role, content, images = images)
        })
        ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).edit()
            .putString("last_active_chat", chatKey).apply()
        // 异步：归档检查
        launch(Dispatchers.IO) {
            // 归档检查：用已加载的 saved，不再重复 load
            if (saved.size >= 5) {
                val chunkKey = "chunk:$name:${saved.size}"
                if (MemoryStore.search(ctx, chunkKey, 1, "diary:$name").isEmpty()) {
                    val msgs = saved.takeLast(20)
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    val text = msgs.joinToString("\n") { "${if (it.isMe) "我" else name}：${it.text}" }
                    try {
                        val summary = com.aftglw.devapi.network.AiServiceFactory.getService()
                            .sendMessage(emptyList(), text, "概括这段对话的核心内容和情绪，像日记一样写两句话。")
                        if (!summary.isNullOrBlank()) MemoryStore.save(ctx, "$dateStr $summary", "diary:$name")
                    } catch (_: Exception) {} /* 非关键 */
                }
            }
        }
    }
    // 每次气泡变化时直接跳到底部，无感
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) {
            listState.scrollToItem(bubbles.size - 1)
        }
    }

    // 系统消息
    val sysPrefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val sysEnter = sysPrefs.getBoolean("sysmsg_enter", true)
    val sysTimer = sysPrefs.getBoolean("sysmsg_timer", true)
    LaunchedEffect(Unit) {
        if (sysEnter) bubbles.add(0, Bubble("—— 您正在与 AI 对话 ——", false, "", label = "system"))
        if (sysTimer) {
            delay(7200000L)
            bubbles.add(Bubble("—— 您已连续使用 2 小时，休息一下吧 ——", false, "", label = "system"))
        }
    }

    fun save() {
        // 超过 60 条时归档最旧 20 条
        val isArchiveEnabled = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("auto_archive_$name", true)
        if (bubbles.size > 60 && isArchiveEnabled) {
            val old = bubbles.take(20)
            val text = old.joinToString("\n") { "${if (it.isMe) "我" else "对方"}：${it.text}" }
            bubbles.removeAll(old)
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val summary = com.aftglw.devapi.network.AiServiceFactory.getService()
                        .sendMessage(emptyList(), text, "用两句话概括这段对话。")
                    if (!summary.isNullOrBlank()) MemoryStore.save(ctx, "$dateStr $summary", "diary:$name")
                } catch (_: Exception) {} /* 非关键 */
            }
        }
        ChatHistory.saveEntries(
            ctx,
            chatKey,
            bubbles.filter { it.label != "system" && it.label != "sticker" }.map {
                com.aftglw.devapi.core.storage.ChatHistoryEntry(
                    text = it.text,
                    isMe = it.isMe,
                    time = it.time,
                    imagePath = it.imagePath,
                    voicePath = it.voicePath,
                    voiceDuration = it.voiceDuration,
                    voiceTranscript = it.voiceTranscript
                )
            }
        )
    }

        // 长时记忆 + 人设浓缩：每 10 轮提取
    LaunchedEffect(bubbles.size / 10) {
        if (bubbles.size >= 10 && bubbles.size % 10 == 0) {
            val recent = bubbles.takeLast(10).joinToString("\n") { if (it.isMe) "我: ${it.text}" else "AI: ${it.text}" }
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val full = com.aftglw.devapi.network.AiServiceFactory.getService()
                        .sendMessage(emptyList(), recent, "从对话中提取信息，按以下格式输出：\n---FACTS---\n3-5条关于对方的事实和偏好，每行一条\n---SUMMARY---\n用一句话概括对方的聊天偏好和习惯。例如：'对方喜欢深夜聊天，回复要简短。'")
                    if (!full.isNullOrBlank()) {
                        val parts = full.split("---SUMMARY---")
                        val facts = parts.getOrElse(0) { "" }.replace("---FACTS---", "").trim()
                        val summary = parts.getOrElse(1) { "" }.trim()
                        facts.lines().filter { it.isNotBlank() }.forEach { MemoryStore.save(ctx, it.trim(), "chat:$chatKey") }
                        if (summary.isNotBlank()) {
                            ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).edit()
                                .putString("persona_optimized_$name", summary).apply()
                        }
                    }
                } catch (_: Exception) {} /* 非关键 */
            }
        }
    }

    // 对话优化：每 20 轮
    LaunchedEffect(bubbles.size / 20) {
        val enabled = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("dialogue_optimization_$name", false)
        if (enabled && bubbles.size >= 20 && bubbles.size % 20 == 0) {
            val all = bubbles.joinToString("\n") { if (it.isMe) "我: ${it.text}" else "AI: ${it.text}" }
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val text = com.aftglw.devapi.network.AiServiceFactory.getService()
                            .sendMessage(emptyList(), all, "你是对话分析师。分析这段对话中用户的说话特点：语气偏好、常用词汇、话题倾向、回复长度偏好、什么情况下会沉默或生气。输出一句话总结，不要超过40个字。")
                        if (!text.isNullOrBlank()) {
                            ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).edit()
                                .putString("persona_dialogue_traits_$name", text).apply()
                        }
                    } catch (_: Exception) {} /* 非关键 */
            }
        }
    }

    
    // 记忆检索已改为按需 Agentic Search（通过 recall 工具），不再自动注入

    val optimized = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("persona_optimized_$name", "") ?: ""
    val traits = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("persona_dialogue_traits_$name", "") ?: ""
    // 提取最近 3 条用户消息作为世界书关键词匹配的文本来源
    val recentUserText = remember(bubbles.size) {
        bubbles.asReversed().filter { it.isMe }.take(3).joinToString(" ") { it.text }.take(500)
    }
    val enhancedPersona = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        PromptBuilder.build(ctx, name, currentPersona, "", optimized, traits, recentUserText)
    }

    // 统一的用户消息发送逻辑：text 传入实际文字（给 AI），voiceBubble/imageBubble 非空时用对应气泡展示
    fun sendUserMessage(text: String, voiceBubble: Bubble? = null, imageBubble: Bubble? = null) {
        if (waiting) return
        // 离线检测：非本地模式下若网络不可用，直接提示并不发送
        val isLocalMode = prefs.getString("ai_protocol", "auto") == "local"
        if (!isLocalMode && !com.aftglw.devapi.network.NetworkMonitor.isOnline) {
            android.widget.Toast.makeText(ctx, com.aftglw.devapi.network.AiError.Offline.message, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        waiting = true
        val bubble = imageBubble ?: voiceBubble
        if (bubble != null) {
            bubbles.add(bubble)
        } else {
            bubbles.add(Bubble(text, true))
        }
        // 构造带图片的 ChatMessage（图片挂在 history 末尾的 user 消息上）
        val images = imageBubble?.imagePath?.let { listOf(it) } ?: emptyList()
        history.add(ChatMessage("user", text, images = images))
        save()

        ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).edit()
            .putLong("last_active_$name", System.currentTimeMillis()).apply()
        // 撤回机制
        val proPrefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        val lastProactive = proPrefs.getLong("proactive_last_$name", 0L)
        if (lastProactive > 0 && System.currentTimeMillis() - lastProactive < 3600000) {
            val rejectWords = listOf("别发了","别烦我","别吵","别说了","不要发","别打扰","你好烦","好烦啊","能不能别","够了别说了")
            val rejected = rejectWords.any { text.contains(it) }
            if (rejected) {
                val curLimit = proPrefs.getInt("proactive_daily_limit_$name", 3)
                proPrefs.edit()
                    .putInt("proactive_daily_limit_$name", maxOf(1, curLimit - 1))
                    .putLong("proactive_silence_$name", System.currentTimeMillis() + 86400000)
                    .apply()
            }
            val moodKeywords = listOf("今天","被骂","加班","好累","心情不好","不开心","难受","烦")
            val needCare = moodKeywords.any { text.contains(it) } && !rejected
            if (needCare) {
                proPrefs.edit().putBoolean("proactive_need_care_$name", true).apply()
            }
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            val moodEnabled = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("mood_enabled", false)
            val historyText = bubbles.takeLast(8).joinToString("\n") { "${if (it.isMe) "我" else "AI"}：${it.text}" }
            val mood = if (moodEnabled) { MoodDetector.feed(text, listOf(historyText)) } else MoodInfo(null, null)
            val moodPersona = if (mood.hint != null) "$enhancedPersona\n\n【注意：${mood.hint}】" else enhancedPersona

            // === Agent Loop（委托给 Agent runtime）===
            val agent = com.aftglw.devapi.core.ai.Agent(ctx, toolGuard = toolGuard)
            val result = agent.prompt(
                history = history.toList(),
                userMessage = text,
                systemPrompt = moodPersona,
            )
            val finalReply = result.text.takeIf { it.isNotBlank() }
            val lastError = result.error

            if (finalReply != null) {
                PostLLMProcessor.process(ctx, name, text, finalReply)
                val saveText = finalReply.replace(Regex("【sticker:[^】]+:[^】]+】"), "").trim()
                val historyText = saveText.ifEmpty { "[表情]" }
                ChatHistory.saveEntries(
                    ctx,
                    chatKey,
                    bubbles.filter { it.label != "sticker" }.map {
                        com.aftglw.devapi.core.storage.ChatHistoryEntry(
                            text = it.text,
                            isMe = it.isMe,
                            time = it.time,
                            imagePath = it.imagePath,
                            voicePath = it.voicePath,
                            voiceDuration = it.voiceDuration,
                            voiceTranscript = it.voiceTranscript
                        )
                    } + com.aftglw.devapi.core.storage.ChatHistoryEntry(historyText, false, now())
                )

                withContext(Dispatchers.Main) {
                    fun addSegment(part: String) {
                        val stickerRegex = Regex("【sticker:([^】]+):([^】]+)】")
                        val sm = stickerRegex.find(part)
                        if (sm != null) {
                            val path = com.aftglw.devapi.core.sticker.StickerEngine.match(sm.groupValues[1], sm.groupValues[2])
                            bubbles.add(Bubble("", false, label = "sticker", stickerPath = path))
                        } else {
                            bubbles.add(Bubble(part, false))
                        }
                    }
                    if (finalReply.isNotBlank()) {
                        val stickerRegex = Regex("【sticker:([^】]+):([^】]+)】")
                        val parts = finalReply.split(Regex("【顿】|\\n\\n"))
                            .flatMap { seg -> seg.split(Regex("(?=【sticker:)")) }
                            .map { it.trim() }.filter { it.isNotBlank() }
                            .filter {
                                val sm = stickerRegex.find(it)
                                sm == null || com.aftglw.devapi.core.sticker.StickerEngine.match(sm.groupValues[1], sm.groupValues[2]) != null
                            }
                        if (parts.size > 1) {
                            addSegment(parts[0])
                            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                for (i in 1 until parts.size) {
                                    delay((parts[i].length * 50L).coerceIn(200L, 1500L))
                                    addSegment(parts[i])
                                }
                            }
                        } else {
                            addSegment(parts[0])
                        }
                    }
                    history.add(com.aftglw.devapi.model.ChatMessage("assistant", finalReply))
                    val hotline = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).getBoolean("sysmsg_hotline", true)
                    if (hotline && com.aftglw.devapi.core.mood.MoodDetector.lastMood in listOf("悲伤", "愤怒", "害怕", "厌恶")) {
                        bubbles.add(Bubble("—— 如果您需要帮助，可拨打心理援助热线：12355 ——", false, "", label = "system"))
                    }
                    waiting = false
                }
            } else {
                withContext(Dispatchers.Main) {
                    val errMsg = if (lastError.isNullOrBlank()) {
                        com.aftglw.devapi.network.AiError.Unknown("请检查 API 配置和网络连接").message
                    } else {
                        // 复用 AiError 的字符串分类逻辑（lastError 是 service 抛出的 message）
                        com.aftglw.devapi.network.AiError.fromException(
                            java.io.IOException(lastError)
                        ).message
                    }
                    android.widget.Toast.makeText(ctx, errMsg, android.widget.Toast.LENGTH_SHORT).show()
                    waiting = false
                }
            }
        }
    }

    BackHandler(enabled = currentSubPage != ChatSubPage.Chat) {
        currentSubPage = when (currentSubPage) {
            ChatSubPage.Diary, ChatSubPage.Memory, ChatSubPage.Editor, ChatSubPage.Worldbook -> ChatSubPage.Info
            else -> ChatSubPage.Chat
        }
    }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            val isForward = when {
                initialState == ChatSubPage.Chat && targetState == ChatSubPage.Info -> true
                initialState == ChatSubPage.Info && (targetState == ChatSubPage.Diary || targetState == ChatSubPage.Memory || targetState == ChatSubPage.Editor || targetState == ChatSubPage.Worldbook) -> true
                else -> false
            }
            AnimationUtils.slideHorizontal(forward = isForward)
        },
        label = "chat_subpage"
    ) { subPage ->
        when (subPage) {
            ChatSubPage.Chat -> {
                ChatContent(
                    name = name, bubbles = bubbles, input = input, waiting = waiting, chatKey = chatKey,
                    showTimestamps = showTimestamps,
                    onInfoClick = { currentSubPage = ChatSubPage.Info },
                    onInputChange = { input = it },
                    onSend = {
                        val text = input.trim()
                        if (text.isEmpty() || waiting) return@ChatContent
                        input = ""
                        sendUserMessage(text, null)
                    },
                    onBack = onBack, avatarUri = avatarUri, characterFolder = characterFolder, chatBgBitmap = chatBgBitmap, listState = listState,
                    onSendVoice = { transcript, path, duration ->
                        if (waiting) return@ChatContent
                        val userText = transcript.ifEmpty { "[语音消息]" }
                        val voiceBubble = Bubble(userText, true, voicePath = path, voiceDuration = duration, voiceTranscript = transcript)
                        sendUserMessage(userText, voiceBubble)
                    },
                    onSendImage = { text, imagePath ->
                        if (waiting) return@ChatContent
                        val userText = text.ifEmpty { "[图片]" }
                        val imageBubble = Bubble(text, true, imagePath = imagePath)
                        sendUserMessage(userText, imageBubble = imageBubble)
                    },
                    onCancel = {
                        // 取消当前在飞的 AI 请求：service.cancel() 让 OkHttp Call 抛 IOException("Canceled")
                        try {
                            com.aftglw.devapi.network.AiServiceFactory.getService().cancel()
                        } catch (_: Exception) { /* 忽略：即使取消失败也复位 waiting */ }
                        waiting = false
                    }
                )
            }
            ChatSubPage.Info -> {
                ChatInfoPage(
                    name = name, persona = currentPersona, avatarUri = avatarUri, chatKey = chatKey,
                    msgCount = bubbles.size, model = model,
                    onBack = { currentSubPage = ChatSubPage.Chat },
                    onNavigateToDiary = { currentSubPage = ChatSubPage.Diary },
                    onNavigateToMemory = { currentSubPage = ChatSubPage.Memory },
                    onNavigateToEditor = { currentSubPage = ChatSubPage.Editor },
                    onNavigateToWorldbook = { currentSubPage = ChatSubPage.Worldbook }
                )
            }
            ChatSubPage.Diary -> {
                DiaryPage(name = chatKey, onBack = { currentSubPage = ChatSubPage.Info })
            }
            ChatSubPage.Memory -> {
                MemoryPage(name = chatKey, onBack = { currentSubPage = ChatSubPage.Info })
            }
            ChatSubPage.Editor -> {
                CharacterEditorPage(
                    chatName = name,
                    persona = currentPersona,
                    onBack = { currentSubPage = ChatSubPage.Info },
                    onSaved = { newPersona -> currentPersona = newPersona }
                )
            }
            ChatSubPage.Worldbook -> {
                WorldbookPage(
                    chatName = name,
                    onBack = { currentSubPage = ChatSubPage.Info }
                )
            }
        }
    }

    // 高风险工具调用的用户确认弹窗
    pendingToolReq?.let { req ->
        ToolConfirmDialog(
            request = req,
            onAllow = { toolGuard.respond(req, true) },
            onDeny = { toolGuard.respond(req, false) }
        )
    }
}

/**
 * 工具调用确认弹窗。展示工具名、风险等级和参数，要求用户明确允许或拒绝。
 */
@Composable
private fun ToolConfirmDialog(
    request: com.aftglw.devapi.tools.ToolConfirmationRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val riskColor = when (request.riskLevel) {
        com.aftglw.devapi.tools.RiskLevel.HIGH -> Color(0xFFD32F2F)
        com.aftglw.devapi.tools.RiskLevel.MEDIUM -> Color(0xFFEF6C00)
        com.aftglw.devapi.tools.RiskLevel.LOW -> Color.Gray
    }
    val riskText = when (request.riskLevel) {
        com.aftglw.devapi.tools.RiskLevel.HIGH -> "高风险"
        com.aftglw.devapi.tools.RiskLevel.MEDIUM -> "中风险"
        com.aftglw.devapi.tools.RiskLevel.LOW -> "低风险"
    }
    AlertDialog(
        onDismissRequest = onDeny,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工具调用确认", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = riskColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        riskText,
                        color = riskColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column {
                Text("AI 想要调用工具：", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Text(request.tool.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(request.tool.description, fontSize = 12.sp, color = Color.Gray)
                if (request.argsJson.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("参数：", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        request.argsJson.take(500),
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 200.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAllow, colors = ButtonDefaults.buttonColors(containerColor = riskColor)) {
                Text("允许")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("拒绝", color = Color.Gray) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatContent(
    name: String,
    bubbles: List<Bubble>,
    input: String,
    waiting: Boolean,
    showTimestamps: Boolean = true,
    onInfoClick: () -> Unit = {},
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendVoice: (transcript: String, path: String, durationSec: Int) -> Unit = { _, _, _ -> },
    onSendImage: (text: String, imagePath: String) -> Unit = { _, _ -> },
    onCancel: () -> Unit = {},
    onBack: () -> Unit,
    avatarUri: String = "",
    characterFolder: String = "",
    chatKey: String = "",
    chatBgBitmap: ImageBitmap? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val localInput = remember(input) { mutableStateOf(input) }
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    // 语音录制状态
    val recorder = remember { VoiceRecorder(ctx) }
    val sttHelper = remember { VoiceSttHelper(ctx) }
    val voicePlayer = remember { VoicePlayer(ctx) }
    val tts = remember { VoiceTts(ctx) }
    val cloudTts = remember { CloudTtsService(ctx) }
    val ttsEngine = remember { prefs.getString("tts_engine", "local") ?: "local" }
    // 配置 TtsProviderManager：进入聊天页时按用户选的引擎初始化降级链
    LaunchedEffect(ttsEngine) {
        com.aftglw.devapi.core.voice.TtsProviderManager.configure(ctx, ttsEngine)
    }
    DisposableEffect(Unit) {
        onDispose {
            sttHelper.stop()
            recorder.cancel()
            voicePlayer.stop()
            tts.shutdown()
            com.aftglw.devapi.core.voice.TtsProviderManager.shutdown()
        }
    }
    // 应用 TTS 语速/音调（从设置读取）— 仅本地引擎生效
    LaunchedEffect(Unit) {
        tts.updateParams(
            rate = prefs.getFloat("tts_rate", 1.0f),
            pitch = prefs.getFloat("tts_pitch", 1.0f)
        )
    }
    var isRecording by remember { mutableStateOf(false) }
    var voicePermissionGranted by remember {
        mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var playingVoicePath by remember { mutableStateOf<String?>(null) }
    var playingTtsId by remember { mutableStateOf<String?>(null) }
    val ttsEnabled = prefs.getBoolean("tts_enabled", false)
    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voicePermissionGranted = granted
        if (!granted) Toast.makeText(ctx, "需要麦克风权限才能发语音", Toast.LENGTH_SHORT).show()
    }
    // 图片选择器：从相册选图，压缩后保存到 filesDir/chat_images/
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var plusMenuExpanded by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            com.aftglw.devapi.core.storage.ImageUtil.savePickedImage(ctx, uri) { file ->
                pendingImagePath = file.absolutePath
            }
        }
    }
    // 拍照：TakePicture 需要先准备一个 FileProvider Uri，拍完再压缩落盘
    var cameraPermissionGranted by remember {
        mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            // 拍照成功：把 Uri 内容压缩到内部存储
            com.aftglw.devapi.core.storage.ImageUtil.savePickedImage(ctx, uri) { file ->
                pendingImagePath = file.absolutePath
            }
        }
        pendingCameraUri = null
    }
    // 启动相机的辅助函数：创建 FileProvider Uri 并启动 TakePicture
    val launchCamera: () -> Unit = {
        try {
            val dir = java.io.File(ctx.filesDir, "chat_images").apply { mkdirs() }
            val file = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(ctx, "无法启动相机: ${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            Toast.makeText(ctx, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        } else {
            // 权限刚授予：立即触发拍照流程
            launchCamera()
        }
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        if (chatBgBitmap != null) {
            Image(chatBgBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val chatContentBgModifier = if (chatBgBitmap != null) {
            Modifier.background(Color.White.copy(alpha = 0.75f))
        } else {
            when (AchatTheme.colors.themeId) {
                "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
                "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
                else -> Modifier.background(AchatTheme.colors.background)
            }
        }
        Column(Modifier.fillMaxSize().imePadding().then(chatContentBgModifier)) {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val resolvedAvatar = remember(characterFolder, MoodDetector.lastMood) {
                            com.aftglw.devapi.core.mood.MoodAvatarMapper.resolve(characterFolder, MoodDetector.lastMood) ?: avatarUri
                        }
                        if (resolvedAvatar.isNotEmpty()) {
                            val topBmp = remember(resolvedAvatar) {
                                com.aftglw.devapi.core.character.BuiltInCharacterLoader.loadAvatarBitmap(ctx, resolvedAvatar)?.asImageBitmap()
                            }
                            if (topBmp != null) {
                                Image(topBmp, null, Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Text(
                            if (waiting && bubbles.isNotEmpty()) "对方正在输入..." else name,
                            color = AchatTheme.colors.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AchatTheme.typography.title
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onBack, Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = onInfoClick) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "详情", tint = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding()
                    .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.headerDoubleRule() else Modifier)
                    .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider) else Modifier)
            )
            HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(bubbles, key = { i, _ -> i }) { idx, b ->
                    var menuExpanded by remember { mutableStateOf(false) }
                    var showImagePreview by remember { mutableStateOf(false) }
                    val isNew = bubbles.size - idx <= 1
                    
                    StaggeredEntrance(index = idx, visible = true) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (b.isMe) Arrangement.End else Arrangement.Start
                        ) {
                        if (!b.isMe && b.label != "system") {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(AchatTheme.colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                val bubbleAvatar = remember(characterFolder, MoodDetector.lastMood) {
                                    com.aftglw.devapi.core.mood.MoodAvatarMapper.resolve(characterFolder, MoodDetector.lastMood) ?: avatarUri
                                }
                                if (bubbleAvatar.isNotEmpty()) {
                                    val bmp = remember(bubbleAvatar) {
                                        com.aftglw.devapi.core.character.BuiltInCharacterLoader.loadAvatarBitmap(ctx, bubbleAvatar)?.asImageBitmap()
                                    }
                                    if (bmp != null) {
                                        Image(bmp, null, Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                    } else {
                                        Text(name.take(1), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text(name.take(1), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (b.label == "system") {
                            // 系统消息：居中灰字
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(b.text, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        } else if (b.label == "sticker" && b.stickerPath != null) {
                            // 贴纸消息：AI 侧图片气泡
                            Box {
                                Box(
                                    Modifier.background(
                                        AchatTheme.colors.chatBubbleAi,
                                        AchatTheme.shapes.bubbleAi
                                    ).then(
                                        if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier
                                    ).then(
                                        if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, idx) else Modifier
                                    ).padding(8.dp)
                                ) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                                            .data("file:///android_asset/${b.stickerPath}")
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "贴纸",
                                        modifier = Modifier.size(120.dp)
                                    )
                                }
                            }
                        } else if (b.imagePath != null) {
                            // 图片消息气泡：用户发送的图片
                            Box {
                                Box(
                                    Modifier.combinedClickable(
                                        onClick = { showImagePreview = true },
                                        onLongClick = { menuExpanded = true }
                                    ).background(
                                        if (b.isMe) AchatTheme.colors.chatBubbleMe else AchatTheme.colors.chatBubbleAi,
                                        if (b.isMe) AchatTheme.shapes.bubbleMe else AchatTheme.shapes.bubbleAi
                                    ).then(
                                        if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier
                                    ).then(
                                        if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, idx) else Modifier
                                    ).padding(4.dp).widthIn(max = 220.dp)
                                ) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                                            .data(java.io.File(b.imagePath))
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "图片",
                                        modifier = Modifier.sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("查看大图") },
                                        onClick = { menuExpanded = false; showImagePreview = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("保存到相册") },
                                        onClick = {
                                            try {
                                                val src = java.io.File(b.imagePath)
                                                if (src.exists()) {
                                                    val bis = java.io.BufferedInputStream(java.io.FileInputStream(src))
                                                    val bitmap = android.graphics.BitmapFactory.decodeStream(bis)
                                                    bis.close()
                                                    if (bitmap != null) {
                                                        android.provider.MediaStore.Images.Media.insertImage(
                                                            ctx.contentResolver, bitmap, "wisp_${System.currentTimeMillis()}.jpg", "Wisp chat image"
                                                        )
                                                        Toast.makeText(ctx, "已保存到相册", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show()
                                            }
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                            // 图片全屏预览
                            if (showImagePreview) {
                                androidx.compose.ui.window.Dialog(
                                    onDismissRequest = { showImagePreview = false }
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().clickable { showImagePreview = false },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        coil.compose.AsyncImage(
                                            model = coil.request.ImageRequest.Builder(ctx)
                                                .data(java.io.File(b.imagePath))
                                                .crossfade(true).build(),
                                            contentDescription = "大图预览",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                            }
                        } else if (b.voicePath != null) {
                            // 语音消息气泡：播放按钮 + 时长 + 转写文字
                            Box {
                                Box(
                                    Modifier.combinedClickable(
                                        onClick = {
                                            val path = b.voicePath
                                            if (playingVoicePath == path) {
                                                voicePlayer.stop()
                                                playingVoicePath = null
                                            } else {
                                                voicePlayer.play(
                                                    path = path,
                                                    onStart = { playingVoicePath = path },
                                                    onComplete = { playingVoicePath = null }
                                                )
                                            }
                                        },
                                        onLongClick = { menuExpanded = true }
                                    ).background(
                                        if (b.isMe) AchatTheme.colors.chatBubbleMe else AchatTheme.colors.chatBubbleAi,
                                        if (b.isMe) AchatTheme.shapes.bubbleMe else AchatTheme.shapes.bubbleAi
                                    ).then(
                                        if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier
                                    ).then(
                                        if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, idx) else Modifier
                                    ).padding(12.dp).widthIn(max = 240.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (playingVoicePath == b.voicePath) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                if (playingVoicePath == b.voicePath) "暂停" else "播放",
                                                tint = AchatTheme.colors.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            // 简易波形条（用固定数量的竖条表示）
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                repeat(8) { i ->
                                                    val h = when (i) {
                                                        0, 7 -> 6.dp
                                                        1, 6 -> 10.dp
                                                        2, 5 -> 14.dp
                                                        else -> 18.dp
                                                    }
                                                    Box(
                                                        Modifier.size(width = 3.dp, height = h)
                                                            .background(
                                                                AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                                                CircleShape
                                                            )
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "${b.voiceDuration}\"",
                                                fontSize = 12.sp,
                                                color = AchatTheme.colors.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        // 转写文字（如果有）
                                        if (!b.voiceTranscript.isNullOrEmpty()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                b.voiceTranscript,
                                                fontSize = 13.sp,
                                                color = AchatTheme.colors.onSurface,
                                                fontFamily = AchatTheme.typography.body
                                            )
                                        }
                                        if (showTimestamps && b.time.isNotEmpty()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                b.time,
                                                fontSize = 10.sp,
                                                color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                                fontFamily = AchatTheme.typography.mono
                                            )
                                        }
                                    }
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("复制文字") },
                                        onClick = {
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("chat", b.voiceTranscript ?: ""))
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        } else {
                        Box {
                            Box(
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { menuExpanded = true }
                                ).background(
                                    if (b.isMe) AchatTheme.colors.chatBubbleMe else AchatTheme.colors.chatBubbleAi,
                                    if (b.isMe) AchatTheme.shapes.bubbleMe else AchatTheme.shapes.bubbleAi
                                ).then(
                                    if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier
                                ).then(
                                    if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, idx) else Modifier
                                ).padding(12.dp).widthIn(max = 240.dp)
                            ) {
                                Column {
                                    if (b.label.isNotEmpty() && !b.isMe) {
                                        Text(b.label, fontSize = 11.sp, color = AchatTheme.colors.primary, fontWeight = FontWeight.Bold, fontFamily = AchatTheme.typography.title)
                                        Spacer(Modifier.height(2.dp))
                                    }
                                    Text(b.text, fontSize = 15.sp, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 情绪可视化
                                        val moodVis = prefs.getBoolean("mood_visualization", false) && prefs.getBoolean("mood_enabled", false)
                                        if (b.isMe && b == bubbles.lastOrNull() && moodVis) {
                                            val moodEmoji = when (com.aftglw.devapi.core.mood.MoodDetector.lastMood) {
                                                "开心" -> "😊"; "悲伤" -> "😢"; "愤怒" -> "😠"
                                                "害怕" -> "😨"; "惊讶" -> "😮"; "厌恶" -> "🤢"
                                                "中性" -> "😐"; else -> null
                                            }
                                            if (moodEmoji != null) {
                                                Text(moodEmoji, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                                            }
                                        }
                                        if (showTimestamps && b.time.isNotEmpty()) {
                                            Text(b.time, fontSize = 10.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.mono)
                                        }
                                        // AI 回复 TTS 播放按钮
                                        if (!b.isMe && ttsEnabled && b.text.isNotEmpty()) {
                                            val ttsId = "tts_${idx}_${b.time.hashCode()}"
                                            val isPlayingThis = playingTtsId == ttsId
                                            Spacer(Modifier.width(4.dp))
                                            IconButton(
                                                onClick = {
                                                    if (isPlayingThis) {
                                                        // 统一停止（Manager 会路由到当前活跃 Provider）
                                                        com.aftglw.devapi.core.voice.TtsProviderManager.stop()
                                                        voicePlayer.stop()  // 兼容旧 voicePlayer 路径
                                                        tts.stop()
                                                        playingTtsId = null
                                                    } else {
                                                        // 统一调用 TtsProviderManager：自动按引擎选择 + 失败降级
                                                        // voiceId 由 TtsVoiceRouter 按当前引擎 + 角色名解析（避免跨引擎污染）
                                                        // 用户在设置页选的 tts_voice 通过 tts_voice_<engine>_<characterName> 命名空间隔离
                                                        playingTtsId = ttsId
                                                        scope.launch {
                                                            val outcome = com.aftglw.devapi.core.voice.TtsProviderManager.speak(
                                                                ctx = ctx,
                                                                text = b.text,
                                                                utteranceId = ttsId,
                                                                characterName = name.takeIf { it.isNotEmpty() },
                                                                onStart = { playingTtsId = ttsId },
                                                                onDone = { success ->
                                                                    if (playingTtsId == ttsId) playingTtsId = null
                                                                    if (!success) {
                                                                        Toast.makeText(ctx, "TTS 播放失败", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            )
                                                            if (outcome is com.aftglw.devapi.core.voice.TtsOutcome.Failed) {
                                                                playingTtsId = null
                                                                Toast.makeText(ctx, "TTS 失败: ${outcome.reason.take(60)}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    if (isPlayingThis) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                                    if (isPlayingThis) "停止" else "朗读",
                                                    tint = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制") },
                                    onClick = {
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("chat", b.text))
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("收藏") },
                                    onClick = {
                                        val existing = com.aftglw.devapi.core.memory.MemoryStore.search(ctx, b.text, 1, "starred:$name")
                                        if (existing.any { it.text == b.text }) {
                                            com.aftglw.devapi.core.memory.MemoryStore.deleteByText(b.text, "starred:$name")
                                            android.widget.Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                                        } else {
                                            com.aftglw.devapi.core.memory.MemoryStore.save(ctx, b.text, "starred:$name")
                                            android.widget.Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show()
                                        }
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                        if (b.isMe) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(AchatTheme.colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                val myAvatar = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                                    .getString("profile_avatar_uri", "")?.takeIf { it.isNotEmpty() }
                                if (myAvatar != null) {
                                    val myBmp = remember(myAvatar) {
                                        try { BitmapFactory.decodeFile(myAvatar)?.asImageBitmap() }
                                        catch (_: Exception) { null }
                                    }
                                    if (myBmp != null) {
                                        Image(myBmp, null, Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                    } else {
                                        Text("Me", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Me", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
                        }
        }

            // 录音提示条
            if (isRecording) {
                Box(
                    Modifier.fillMaxWidth().background(Color(0xCC000000))
                        .navigationBarsPadding().padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "🎤 正在录音... 松开发送",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // 待发图片预览（选图后、发送前）
            if (pendingImagePath != null) {
                Box(
                    Modifier.fillMaxWidth().background(AchatTheme.colors.surface)
                        .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))) {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(ctx)
                                    .data(java.io.File(pendingImagePath)).crossfade(true).build(),
                                contentDescription = "待发图片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "已选图片，输入文字后点发送${if (localInput.value.isBlank()) "（或直接发送）" else ""}",
                            fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { pendingImagePath = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "取消", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(AchatTheme.colors.surface)
                    .navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "+" 按钮：点击展开相册/拍照菜单
                Box {
                    Box(
                        Modifier.size(36.dp).background(
                            AchatTheme.colors.divider.copy(alpha = 0.5f), CircleShape
                        ).clickable(enabled = !waiting && !isRecording) { plusMenuExpanded = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, "添加", tint = AchatTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = plusMenuExpanded, onDismissRequest = { plusMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("相册") },
                            onClick = {
                                plusMenuExpanded = false
                                imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            onClick = {
                                plusMenuExpanded = false
                                if (cameraPermissionGranted) launchCamera()
                                else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    localInput.value, { localInput.value = it; onInputChange(it) }, Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                    placeholder = { Text(if (isRecording) "正在录音..." else "说些什么吧...") },
                    shape = RoundedCornerShape(22.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.5f),
                        unfocusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.3f)
                    ),
                    enabled = !waiting && !isRecording
                )
                Spacer(Modifier.width(8.dp))
                // 等待 AI 回复时显示"停止"按钮（红色），取消当前请求
                if (waiting) {
                    TextButton(
                        onClick = { onCancel() },
                        Modifier.background(Color(0xFFFA5151), RoundedCornerShape(18.dp)),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) {
                        Text("停止")
                    }
                } else if (localInput.value.isNotEmpty() || pendingImagePath != null) {
                    // 文本非空 或 有待发图片 时显示发送
                    TextButton(
                        onClick = {
                            val text = localInput.value.trim()
                            val imgPath = pendingImagePath
                            if (text.isEmpty() && imgPath == null) return@TextButton
                            if (imgPath != null) {
                                pendingImagePath = null
                                localInput.value = ""
                                onInputChange("")
                                onSendImage(text, imgPath)
                            } else {
                                onSend()
                            }
                        },
                        Modifier.background(AchatTheme.colors.primary, RoundedCornerShape(18.dp)),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) {
                        Text("发送")
                    }
                } else {
                    Box(
                        Modifier.size(44.dp).background(
                            if (isRecording) Color(0xFFFA5151) else AchatTheme.colors.primary,
                            CircleShape
                        ).then(
                            if (voicePermissionGranted && !waiting) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            val file = recorder.start()
                                            if (file != null) {
                                                isRecording = true
                                                val released = tryAwaitRelease()
                                                isRecording = false
                                                if (!released) {
                                                    recorder.cancel()
                                                    return@detectTapGestures
                                                }
                                                val duration = recorder.stop()
                                                if (duration > 0 && file.exists()) {
                                                    // STT 转写（异步）
                                                    sttHelper.start(
                                                        onResult = { text ->
                                                            onSendVoice(text, file.absolutePath, duration)
                                                            sttHelper.stop()
                                                        },
                                                        onError = {
                                                            // STT 失败也发送空文字（只有音频）
                                                            onSendVoice("", file.absolutePath, duration)
                                                            Toast.makeText(ctx, "语音转文字失败，仅发送音频", Toast.LENGTH_SHORT).show()
                                                            sttHelper.stop()
                                                        }
                                                    )
                                                } else {
                                                    Toast.makeText(ctx, "录音失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier.clickable {
                                    recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            "语音",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    val sampleBubbles = listOf(
        Bubble("Hello!", false, "14:20"),
        Bubble("Hi! How can I help you today?", true, "14:20"),
        Bubble("I'm looking for some information about Jetpack Compose.", false, "14:21"),
        Bubble("Sure! Jetpack Compose is Android's modern toolkit for building native UI.", true, "14:21")
    )
    MaterialTheme {
        ChatContent(
            name = "John Doe",
            bubbles = sampleBubbles,
            input = "Hello",
            waiting = false,
            onInputChange = {},
            onSend = {},
            onBack = {}
        )
    }
}
