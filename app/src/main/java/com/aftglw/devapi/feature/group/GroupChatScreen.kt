package com.aftglw.devapi.feature.group

import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMessage
import com.aftglw.devapi.model.GroupChatMode
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.core.ai.Agent
import com.aftglw.devapi.core.character.BuiltInCharacterLoader
import com.aftglw.devapi.core.voice.VoicePlayer
import com.aftglw.devapi.core.voice.VoiceRecorder
import com.aftglw.devapi.core.voice.VoiceSttHelper
import com.aftglw.devapi.tools.ToolRegistry
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MAX_AUTO_REPLY_ROUNDS = 5
private const val MAX_TURN_LLM_CALLS = 8
private const val MAX_GROUP_TURN_DURATION_MS = 60_000L
private const val MAX_MEMBER_CALL_DURATION_MS = 30_000L
private const val BUILD_HISTORY_MAX_MESSAGES = 20
private const val PROACTIVE_POLL_INTERVAL_MS = 30_000L
private const val THINK_DELAY_MIN_MS = 500L
private const val THINK_DELAY_MAX_MS = 3_000L
private const val MEMORY_SEARCH_TOP_K = 3
private const val JUDGE_CONVERSATION_LOOKBACK = 6
private const val LOW_WILLINGNESS_CONSECUTIVE_LIMIT = 2

/**
 * 群聊聊天页面。
 *
 * 用户发送消息后，群成员按轮次顺序依次回复（一圈一人）。
 * 每个成员使用各自的角色人设作为 system prompt。
 */
@Composable
fun GroupChatScreen(
    group: GroupChat,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val recorder = remember { VoiceRecorder(ctx) }
    val sttHelper = remember { VoiceSttHelper(ctx) }
    val voicePlayer = remember { VoicePlayer(ctx) }
    var isRecording by remember { mutableStateOf(false) }
    var playingVoicePath by remember { mutableStateOf<String?>(null) }
    var voicePermissionGranted by remember {
        mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voicePermissionGranted = granted
        if (!granted) {
            Toast.makeText(ctx, "需要麦克风权限才能发送语音", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            sttHelper.stop()
            recorder.cancel()
            voicePlayer.stop()
        }
    }

    // 消息历史
    val messages = remember { mutableStateListOf<GroupChatMessage>() }
    var messagesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(group.id) {
        messagesLoaded = false
        val loaded = withContext(Dispatchers.IO) {
            GroupChatManager.loadMessages(ctx, group.id)
        }
        messages.clear()
        messages.addAll(loaded)
        messagesLoaded = true
    }

    // 轮次索引：当前该哪个成员发言（0 = 第一个成员）
    val roundRobinIndex = remember { mutableStateOf(0) }

    // 输入状态
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var retryingMessage by remember { mutableStateOf<GroupChatMessage?>(null) }
    /** 非空时表示某个成员正在打字（链式接话过程中显示） */
    var typingMember by remember { mutableStateOf<String?>(null) }
    /** debug 面板开关 */
    var showDebug by remember { mutableStateOf(false) }
    /** 最近一次判断结果 */
    var lastJudgeResult by remember { mutableStateOf("") }

    // 加载成员头像（兼容 asset:// 内置角色头像与普通文件路径）
    var memberAvatars by remember { mutableStateOf<Map<String, androidx.compose.ui.graphics.ImageBitmap?>>(emptyMap()) }

    // 成员人设缓存
    var memberPersonas by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(group.members) {
        val loaded = withContext(Dispatchers.IO) {
            val avatars = group.members.associateWith { name ->
                val uri = GroupChatManager.getMemberAvatarUri(ctx, name)
                if (uri.isNotEmpty()) {
                    BuiltInCharacterLoader.loadAvatarBitmap(ctx, uri)?.asImageBitmap()
                } else null
            }
            val personas = group.members.associateWith { GroupChatManager.getMemberPersona(ctx, it) }
            avatars to personas
        }
        memberAvatars = loaded.first
        memberPersonas = loaded.second
    }

    val activeMembers = group.members.filter { group.memberEnabled[it] != false }

    // ── 多模态图片输入状态 ──
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var plusMenuExpanded by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                com.aftglw.devapi.core.storage.ImageUtil.savePickedImage(
                    ctx, uri,
                    onSaved = { file -> pendingImagePath = file.absolutePath },
                    onError = { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }
    var cameraPermissionGranted by remember {
        mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            scope.launch {
                com.aftglw.devapi.core.storage.ImageUtil.savePickedImage(
                    ctx, uri,
                    onSaved = { file -> pendingImagePath = file.absolutePath },
                    onError = { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
                )
            }
        }
        pendingCameraUri = null
    }
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
            launchCamera()
        }
    }

    // ── 历史消息构建（callMember 与主动插话共用）──
    /** 只取最近 [BUILD_HISTORY_MAX_MESSAGES] 条消息，避免长对话导致 prompt 膨胀 */
    fun buildHistory(): List<ChatMessage> = messages.takeLast(BUILD_HISTORY_MAX_MESSAGES).map { m ->
        val displayName = if (m.isMe) "你" else m.from
        ChatMessage(
            role = if (m.isMe) "user" else "assistant",
            content = if (m.isMe) m.text else "[$displayName]: ${m.text}",
            images = if (m.isMe && m.imagePath != null) listOf(m.imagePath) else emptyList()
        )
    }

    fun memberSystemPrompt(
        name: String,
        memoryBlock: String = "",
        toolsBlock: String = "",
        groupAtmosphereBlock: String = "",
    ): String {
        val p = memberPersonas[name] ?: ""
        return buildString {
            append(p)
            if (p.isNotBlank()) appendLine()
            appendLine()
            appendLine("你在一个群聊中，群名：${group.name}")
            appendLine("群成员：${activeMembers.joinToString("、")}")
            appendLine("你的角色名是：$name")
            appendLine("你可以引用和回应其他成员的话，像真人一样在群聊中和大家互动。")
            appendLine("请以 $name 的身份回复，不要说'作为AI'之类的话。")
            appendLine("用括号描述你的动作和表情，例如【高兴】【叹气】。")
            // P2.2b: 群整体氛围注入（不改变角色音色，只影响语气倾向）
            if (groupAtmosphereBlock.isNotEmpty()) append(groupAtmosphereBlock)
            if (memoryBlock.isNotEmpty()) append(memoryBlock)
            if (toolsBlock.isNotEmpty()) append(toolsBlock)
        }
    }

    /**
     * P2.2b: 构建群整体氛围的可读提示块。
     *
     * 调用 AffectiveEngine.snapshotGroup 读取群级 field（派生或覆盖），
     * 转成自然语言注入 systemPrompt，让成员感知"群里现在的气氛"。
     *
     * 设计原则：
     * - 不改变角色音色身份（voice_id 由 TtsVoiceRouter 独立路由）
     * - 只在有实际数据时注入（全 0 的默认 field 跳过，避免误导）
     * - 中性氛围不注入（warmth/tension/drift 都在 [-0.2, 0.2] 中性区间时不提示）
     */
    suspend fun buildGroupAtmosphereBlock(): String {
        return try {
            val snapshot = com.aftglw.devapi.core.affect.AffectiveEngine.snapshotGroup(
                ctx, group.id, activeMembers,
            )
            // 无成员样本时跳过（避免"派生自 0 个成员"的误导）
            if (snapshot.observedMemberCount == 0) return ""
            val f = snapshot.field
            // 中性区间跳过：所有维度都在 [-0.2, 0.2] 时不提示
            val isNeutral = listOf(f.tension, f.warmth, f.anticipation, f.drift)
                .all { it in -0.2f..0.2f }
            if (isNeutral) return ""
            buildString {
                appendLine()
                appendLine("【群聊当前氛围】")
                appendLine("整体张力：${f.tensionLabel}（${"%.2f".format(f.tension)}）")
                appendLine("整体温度：${f.warmthLabel}（${"%.2f".format(f.warmth)}）")
                if (f.anticipation > 0.3f) {
                    appendLine("有人在期待回应：${f.anticipationLabel}")
                }
                appendLine("整体走向：${f.driftLabel}")
                // 氛围驱动的语气建议（不改变音色，只影响语气倾向）
                val hints = mutableListOf<String>()
                if (f.tension > 0.4f) hints.add("气氛有些紧张，说话注意分寸")
                else if (f.tension > 0.2f) hints.add("气氛略紧张")
                if (f.warmth > 0.4f) hints.add("大家关系融洽，可以更放松")
                else if (f.warmth < -0.2f) hints.add("大家比较生疏，不要过于亲昵")
                if (hints.isNotEmpty()) {
                    appendLine("语气建议：${hints.joinToString("；")}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GroupChat", "buildGroupAtmosphereBlock failed", e)
            ""
        }
    }

    /**
     * 统一的成员调用入口（首位发言 / 接话 / 主动插话共用）。
     * 走 Agent runtime → 具备工具调用能力（RestrictedToolGuard 自动拒绝 HIGH 风险）+ 群记忆检索。
     */
    suspend fun callMember(
        name: String,
        userInput: String,
        // P1.3: 用于关系场分析的用户原始消息；为 null 时不更新 AffectiveField（接话成员/图片消息）
        userMessageForAffect: String? = null,
        // P2.2b: 群整体氛围块（由调用方在轮次入口预算一次，传给本轮所有 callMember）
        groupAtmosphereBlock: String = "",
    ): String? {
        val t = System.currentTimeMillis()
        android.util.Log.d("GroupChat", "callMember: $name, inputLen=${userInput.length}, historySize=${messages.size}")
        typingMember = name
        try {
            // 检索该成员的个人记忆 + 群级共享记忆（合并去重）
            val mems = withContext(Dispatchers.IO) {
                GroupMemoryStore.searchForMember(ctx, group.id, name, userInput, topK = MEMORY_SEARCH_TOP_K)
            }
            val memoryBlock = if (mems.isNotEmpty()) {
                buildString {
                    appendLine()
                    appendLine("【你对这个群和用户的记忆】")
                    mems.forEach { appendLine("- ${it.text}") }
                }
            } else ""
            // 工具描述（仅当 ToolRegistry 有注册工具时附加）
            val tools = ToolRegistry.getAll()
            val toolsBlock = if (tools.isNotEmpty()) {
                buildString {
                    appendLine()
                    appendLine("【可调用工具】")
                    appendLine(ToolRegistry.getDescriptions())
                    appendLine("调用格式：【tool:工具名 参数】；非必要时不要调用工具。")
                }
            } else ""
            // 在切 IO 前快照 systemPrompt + 消息历史，避免 IO 线程读取 Compose 快照状态
            val systemPrompt = memberSystemPrompt(name, memoryBlock, toolsBlock, groupAtmosphereBlock)
            val historyForCall = buildHistory()
            return withContext(Dispatchers.IO) {
                // 使用 Agent runtime，让成员具备工具调用能力
                // 群聊无人值守场景：注入 RestrictedToolGuard，HIGH 风险工具自动拒绝
                val guard = com.aftglw.devapi.tools.RestrictedToolGuard(ctx.applicationContext)
                val agent = Agent(ctx, toolGuard = guard)
                // 单成员调用独立超时：避免某个成员卡住占用整轮 60s
                val result = try {
                    withTimeout(MAX_MEMBER_CALL_DURATION_MS) {
                        agent.prompt(
                            history = historyForCall,
                            userMessage = userInput,
                            systemPrompt = systemPrompt
                        )
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    android.util.Log.w("GroupChat", "Member $name call timed out after ${MAX_MEMBER_CALL_DURATION_MS}ms")
                    return@withContext null
                }
                if (result.toolCalls.isNotEmpty()) {
                    android.util.Log.d("GroupChat", "Member $name used tools: ${result.toolCalls.map { it.name }}")
                }
                val reply = if (result.isSuccess) result.text
                    else if (result.text.isNotBlank()) result.text
                    else null

                // P1.3: 群聊关系场隔离
                // 命名空间 chatKey = "group_${groupId}_${memberName}"，与 GroupMemoryStore.topicFor 对齐
                // 避免与单聊/其他群的同名角色冲突；接话成员不更新关系场（非直接回应用户）
                if (reply != null && !userMessageForAffect.isNullOrBlank()) {
                    try {
                        val chatKey = "group_${group.id}_$name"
                        // 每个成员独立 eventId（带 memberName 后缀），避免同轮多成员被幂等误杀
                        val memberEventId = "${java.util.UUID.randomUUID()}_$name"
                        com.aftglw.devapi.core.mood.PostLLMProcessor.process(
                            ctx, chatKey, userMessageForAffect, reply, memberEventId,
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("GroupChat", "Affective update failed for $name in group ${group.id}", e)
                    }
                }
                reply
            }.also { reply ->
                android.util.Log.d("GroupChat", "callMember result: $name, replyLen=${reply?.length ?: 0}, ${System.currentTimeMillis() - t}ms")
            }
        } catch (e: Exception) {
            android.util.Log.w("GroupChat", "Member $name reply failed after ${System.currentTimeMillis() - t}ms", e)
            // 仅在非取消场景下提示错误（用户主动取消时不弹 Toast）
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            val err = com.aftglw.devapi.network.AiError.fromException(e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "$name：${err.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            return null
        } finally {
            typingMember = null
        }
    }

    // 发送消息（可携带图片路径）
    fun sendMessage(
        text: String,
        imagePath: String? = null,
        voicePath: String? = null,
        voiceDuration: Int = 0,
        voiceTranscript: String? = null
    ) {
        if ((text.isBlank() && imagePath == null && voicePath == null) || sending || !messagesLoaded) return
        // 离线检测：网络不可用时直接提示并不发送（本地 GGUF 推理已下线，无需跳过）
        if (!com.aftglw.devapi.network.NetworkMonitor.isOnline) {
            android.widget.Toast.makeText(ctx, com.aftglw.devapi.network.AiError.Offline.message, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Validate the turn before adding it to the visible history or database.
        val preflightText = text.ifBlank { voiceTranscript.orEmpty() }
        val preflightMention = MentionParser.firstMention(preflightText, activeMembers)
        if (activeMembers.isEmpty()) {
            android.widget.Toast.makeText(ctx, "请先添加至少一名启用的群成员", Toast.LENGTH_SHORT).show()
            return
        }
        if (group.mode == GroupChatMode.MENTION_ONLY && preflightMention == null) {
            android.widget.Toast.makeText(ctx, "请先 @ 一位群成员再发送", Toast.LENGTH_SHORT).show()
            return
        }

        sending = true
        val t0 = System.currentTimeMillis()

        val displayText = when {
            voicePath != null -> voiceTranscript?.takeIf { it.isNotBlank() } ?: "[语音消息]"
            text.isBlank() && imagePath != null -> "[图片]"
            else -> text
        }
        val userMsg = GroupChatMessage(
            text = displayText,
            from = "user",
            time = GroupChatManager.now(),
            isMe = true,
            imagePath = imagePath,
            voicePath = voicePath,
            voiceDuration = voiceDuration,
            voiceTranscript = voiceTranscript
        )
        messages.add(userMsg)
        inputText = ""
        pendingImagePath = null

        sendJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                GroupChatManager.appendMessage(ctx, group.id, userMsg)
                }
            val replied = mutableSetOf<String>()
            var totalAutoRounds = 0
            // P2.2b: 本轮开始前预算一次群整体氛围块，传给首位 + 接话链所有 callMember
            val groupAtmosphereBlock = buildGroupAtmosphereBlock()
            // Bound the complete automated turn, including delays and follow-up calls.
            withTimeout(MAX_GROUP_TURN_DURATION_MS) {
            // @提及决定首位发言人：若用户文本中提及了某成员，则由其先回；
            // 否则按 round-robin 轮询。
            val mentioned = MentionParser.firstMention(displayText, activeMembers)
            if (activeMembers.isEmpty()) {
                Toast.makeText(ctx, "群聊还没有成员", Toast.LENGTH_SHORT).show()
                return@withTimeout
            }
            if (group.mode == GroupChatMode.MENTION_ONLY && mentioned == null) {
                Toast.makeText(ctx, "请 @ 一位群成员后再发送", Toast.LENGTH_SHORT).show()
                return@withTimeout
            }
            val firstSpeaker = when (group.mode) {
                GroupChatMode.ROUND_ROBIN -> activeMembers[roundRobinIndex.value % activeMembers.size]
                else -> mentioned ?: activeMembers[roundRobinIndex.value % activeMembers.size]
            }
            var lastSpeaker = firstSpeaker
            var lastReply: String? = null

            android.util.Log.d("GroupChat", "Turn start: firstSpeaker=$firstSpeaker, mentioned=$mentioned, members=${group.members}, roundIdx=${roundRobinIndex.value}")

            // 第一步：首位成员回复
            // P1.3: 传 text（用户原始消息）作为关系场分析素材；图片消息(text="")时为 null 跳过关系场更新
            // P2.2b: 传入本轮预算的 groupAtmosphereBlock
            val r1 = callMember(
                firstSpeaker, displayText,
                userMessageForAffect = text.ifBlank { null },
                groupAtmosphereBlock = groupAtmosphereBlock,
            )
            if (r1 != null) {
                val msg1 = GroupChatMessage(r1, firstSpeaker, GroupChatManager.now())
                messages.add(msg1)
                withContext(Dispatchers.IO) {
                    GroupChatManager.appendMessage(ctx, group.id, msg1)
                }
                replied.add(firstSpeaker)
                lastReply = r1

                // 保存用户消息：写入群级共享记忆 + 被 @ 成员个人记忆（异步，不阻塞主流程）
                if (text.isNotBlank() && text != "[图片]") {
                    scope.launch(Dispatchers.IO) {
                        try {
                            GroupMemoryStore.saveUserMessage(ctx, group.id, mentioned, text)
                        } catch (e: Exception) {
                            android.util.Log.w("GroupChat", "Memory save failed for group ${group.id}", e)
                        }
                    }
                }

                // 继续判断其他人是否需要接话（最多 5 轮自动推进）
                var autoRounds = 0
                // LLM 调用预算：每轮最多 8 次（首位 callMember + 5 自动轮 decide/callMember + 余量）。
                // decide 与 callMember 各消耗 1 次，超出则停止链式推进，防止刷屏与 30-60s 阻塞。
                // 预留 2 次（1 次 decide + 1 次 callMember），不足 2 则停止。
                var llmCallBudget = MAX_TURN_LLM_CALLS
                // 低意愿连续计数器：连续 N 次 LLM 返回 <0.3 的意愿，直接停止（节省 LLM 调用）
                var consecutiveLowWillingness = 0
                val WILLINGNESS_THRESHOLD = SpeakerDecision.DEFAULT_WILLINGNESS_THRESHOLD
                while (group.mode == GroupChatMode.FREE && autoRounds < MAX_AUTO_REPLY_ROUNDS && llmCallBudget >= 2) {
                    llmCallBudget--  // decide 占用 1 次
                    typingMember = "判断中…"
                    // 在切 IO 前快照对话内容，避免 IO 线程读取 Compose 快照状态
                    val conversationSnapshot = messages.toList()
                    val decision: SpeakerDecision = withContext(Dispatchers.IO) {
                        NextSpeakerJudge.decide(
                            memberNames = activeMembers,
                            repliedNames = replied,
                            lastSpeaker = lastSpeaker,
                            lastMessage = lastReply ?: "",
                            conversation = conversationSnapshot.takeLast(JUDGE_CONVERSATION_LOOKBACK).joinToString("\n") {
                                val label = if (it.isMe) "你" else it.from
                                "$label: ${it.text}"
                            }
                        )
                    }
                    val next = decision.name
                    if (next == null || next !in activeMembers) {
                        lastJudgeResult = "stop: ${decision.reason}"
                        android.util.Log.d("GroupChat", "Stop chaining: ${decision.reason}")
                        break
                    }
                    if (decision.willingness < WILLINGNESS_THRESHOLD) {
                        lastJudgeResult = "low(${decision.willingness}): ${decision.reason}"
                        android.util.Log.d("GroupChat", "Stop chaining: willingness=${decision.willingness} < $WILLINGNESS_THRESHOLD, reason=${decision.reason}")
                        break
                    }
                    // 低意愿连续计数：<0.3 连续 N 次则提前停止（节省 LLM 调用）
                    if (decision.willingness < 0.3f) {
                        consecutiveLowWillingness++
                        if (consecutiveLowWillingness >= LOW_WILLINGNESS_CONSECUTIVE_LIMIT) {
                            lastJudgeResult = "low_consecutive: ${decision.reason}"
                            android.util.Log.d("GroupChat", "Stop chaining: $consecutiveLowWillingness consecutive low willingness")
                            break
                        }
                    } else {
                        consecutiveLowWillingness = 0
                    }
                    autoRounds++
                    totalAutoRounds++
                    lastJudgeResult = "$next(${decision.willingness})"

                    android.util.Log.d("GroupChat", "decideNextSpeaker: auto round=$totalAutoRounds, next=$next, willingness=${decision.willingness}, budget=$llmCallBudget, replied=${replied.toList()}")

                    // 接话前模拟思考时间（随意愿分动态调整：意愿高→快接话，意愿低→多想想）
                    typingMember = next
                    val thinkDelay = if (decision.willingness >= 0.8f) {
                        THINK_DELAY_MIN_MS + kotlin.random.Random.nextLong(500L)       // 0.5-1s
                    } else if (decision.willingness >= 0.6f) {
                        THINK_DELAY_MIN_MS + kotlin.random.Random.nextLong(1500L)      // 0.5-2s
                    } else {
                        1500L + kotlin.random.Random.nextLong(1500L)                   // 1.5-3s
                    }
                    kotlinx.coroutines.delay(thinkDelay)

                    // 构建适合该成员的 userMessage（基于最近对话）
                    val contextPrompt = "${activeMembers.joinToString("、")}正在群聊中交谈。话题是最近的消息。轮到 $next 发言了。请自然接话。"

                    llmCallBudget--  // callMember 占用 1 次
                    val rN = callMember(next, contextPrompt, groupAtmosphereBlock = groupAtmosphereBlock)
                    if (rN != null) {
                        val msgN = GroupChatMessage(rN, next, GroupChatManager.now())
                        messages.add(msgN)
                        withContext(Dispatchers.IO) {
                            GroupChatManager.appendMessage(ctx, group.id, msgN)
                        }
                        replied.add(next)
                        lastSpeaker = next
                        lastReply = rN
                    } else {
                        val failed = GroupChatMessage(
                            text = "\u56de\u590d\u5931\u8d25",
                            from = next,
                            time = GroupChatManager.now(),
                            isError = true,
                            retryPrompt = contextPrompt
                        )
                        messages.add(failed)
                        withContext(Dispatchers.IO) {
                            GroupChatManager.appendMessage(ctx, group.id, failed)
                        }
                        break
                    }
                }
            } else {
                val failed = GroupChatMessage(
                    text = "\u56de\u590d\u5931\u8d25",
                    from = firstSpeaker,
                    time = GroupChatManager.now(),
                    isError = true,
                    retryPrompt = displayText
                )
                messages.add(failed)
                withContext(Dispatchers.IO) {
                    GroupChatManager.appendMessage(ctx, group.id, failed)
                }
            }
            }

            // 更新群聊摘要
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null) {
                val summary = if (lastMsg.isMe) lastMsg.text.take(30)
                    else "${lastMsg.from}: ${lastMsg.text.take(30)}"
                withContext(Dispatchers.IO) {
                    GroupChatManager.saveGroup(ctx, group.copy(lastMessage = summary, time = lastMsg.time))
                }
            }
                roundRobinIndex.value = (roundRobinIndex.value + 1) % activeMembers.size
                val total = System.currentTimeMillis() - t0
                android.util.Log.d("GroupChat", "Turn done: replied=${replied.toList()}, autoRounds=$totalAutoRounds, ${total}ms")
            } catch (e: TimeoutCancellationException) {
                try { AiServiceFactory.getService().cancel() } catch (ce: Exception) { android.util.Log.w("GroupChat", "cancel failed", ce) }
                android.util.Log.w("GroupChat", "Turn timed out after ${MAX_GROUP_TURN_DURATION_MS}ms")
                Toast.makeText(ctx, "\u7fa4\u804a\u56de\u590d\u8d85\u65f6\uff0c\u8bf7\u91cd\u8bd5", Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("GroupChat", "Turn cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.w("GroupChat", "Turn failed", e)
                Toast.makeText(ctx, "群聊回复失败，请稍后重试", Toast.LENGTH_SHORT).show()
            } finally {
                sending = false
                typingMember = null
            }
        }
    }

    /** Retry one persisted member failure without resending the user's message. */
    fun retryMember(failed: GroupChatMessage) {
        val prompt = failed.retryPrompt?.takeIf { it.isNotBlank() } ?: return
        if (!failed.isError || sending) return

        sending = true
        retryingMessage = failed
        sendJob = scope.launch {
            var succeeded = false
            try {
                withTimeout(MAX_GROUP_TURN_DURATION_MS) {
                    // P2.2b: 重试也注入当前氛围（状态可能已变化）
                    val retryAtmosphereBlock = buildGroupAtmosphereBlock()
                    val reply = callMember(failed.from, prompt, groupAtmosphereBlock = retryAtmosphereBlock)
                    if (!reply.isNullOrBlank()) {
                        val message = GroupChatMessage(reply, failed.from, GroupChatManager.now())
                        withContext(Dispatchers.IO) {
                            GroupChatManager.deleteFailedMessage(ctx, group.id, failed)
                            GroupChatManager.appendMessage(ctx, group.id, message)
                        }
                        messages.remove(failed)
                        messages.add(message)
                        succeeded = true
                    }
                }
                if (!succeeded) {
                    Toast.makeText(ctx, "\u91cd\u8bd5\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5", Toast.LENGTH_SHORT).show()
                }
            } catch (e: TimeoutCancellationException) {
                try { AiServiceFactory.getService().cancel() } catch (ce: Exception) { android.util.Log.w("GroupChat", "cancel failed", ce) }
                Toast.makeText(ctx, "\u91cd\u8bd5\u8d85\u65f6\uff0c\u5931\u8d25\u6d88\u606f\u4ecd\u4fdd\u7559", Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("GroupChat", "Retry failed for ${failed.from}", e)
                Toast.makeText(ctx, "\u91cd\u8bd5\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5", Toast.LENGTH_SHORT).show()
            } finally {
                retryingMessage = null
                sending = false
                typingMember = null
            }
        }
    }

    // ── 主动插话：用户停留在群聊页面时，定期判断是否需要某成员自发插话 ──
    // lastActivityMs 基于最后一条消息的实际时间戳，切群再回来不会误触发
    var lastActivityMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(messages.size, sending) {
        // 有新消息时：基于最后一条消息的时间推算 idle 时长
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.time.isNotEmpty()) {
            // time 字段格式 "HH:mm"，无法还原绝对时间戳，用当前时间保守估算
            lastActivityMs = System.currentTimeMillis()
        } else {
            lastActivityMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(group.id, sending) {
        // 用户停留且当前未在发送时，每 30 秒轮询一次
        while (true) {
            kotlinx.coroutines.delay(PROACTIVE_POLL_INTERVAL_MS)
            if (sending) continue
            if (messages.isEmpty()) continue
            val nowMs = System.currentTimeMillis()
            if (!GroupProactiveScheduler.shouldTrigger(messages, lastActivityMs, nowMs)) continue
            val lastSpeaker = messages.lastOrNull()?.let { if (it.isMe) null else it.from }
            val speaker = GroupProactiveScheduler.pickSpeaker(activeMembers, lastSpeaker) ?: continue
            android.util.Log.d("GroupChat", "Proactive trigger: speaker=$speaker, idle=${nowMs - lastActivityMs}ms")
            sending = true
            val spontaneousHint = GroupProactiveScheduler.buildSpontaneousHint(speaker)
            // P2.2b: 主动插话也是群聊发言，注入当前氛围
            val proactiveAtmosphereBlock = buildGroupAtmosphereBlock()
            try {
                // 复用 callMember：走 Agent runtime + 工具 + 群记忆检索，与正常发言链路一致
                val reply = callMember(speaker, spontaneousHint, groupAtmosphereBlock = proactiveAtmosphereBlock)
                if (!reply.isNullOrBlank()) {
                    val msg = GroupChatMessage(reply, speaker, GroupChatManager.now())
                    messages.add(msg)
                    withContext(Dispatchers.IO) {
                        GroupChatManager.appendMessage(ctx, group.id, msg)
                    }
                    val summary = "${speaker}: ${reply.take(30)}"
                    withContext(Dispatchers.IO) {
                        GroupChatManager.saveGroup(ctx, group.copy(lastMessage = summary, time = msg.time))
                    }
                } else {
                    val failed = GroupChatMessage(
                        text = "\u56de\u590d\u5931\u8d25",
                        from = speaker,
                        time = GroupChatManager.now(),
                        isError = true,
                        retryPrompt = spontaneousHint
                    )
                    messages.add(failed)
                    withContext(Dispatchers.IO) {
                        GroupChatManager.appendMessage(ctx, group.id, failed)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("GroupChat", "Proactive failed: $speaker", e)
            } finally {
                typingMember = null
                sending = false
                lastActivityMs = System.currentTimeMillis()
            }
        }
    }

    // ── UI ──

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        // 顶栏
        Surface(
            Modifier.fillMaxWidth().statusBarsPadding(),
            color = AchatTheme.colors.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        group.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AchatTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showDebug = !showDebug }
                    )
                    Text(
                        "${activeMembers.size}/${group.members.size} 人 · ${group.mode.title}",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onOpenInfo) {
                    Icon(Icons.Filled.MoreVert, "群信息", tint = AchatTheme.colors.onSurface)
                }
            }
        }

        // debug 面板（点击群名切换）
        AnimatedVisibility(visible = showDebug) {
            Surface(
                Modifier.fillMaxWidth(),
                color = AchatTheme.colors.surface.copy(alpha = 0.95f),
                shadowElevation = 1.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        buildString {
                            append("🔧 round=${roundRobinIndex.value} next=${activeMembers.getOrNull(roundRobinIndex.value % activeMembers.size) ?: "?"}")
                            append(" | judge=$lastJudgeResult")
                            append(" | msgs=${messages.size}")
                            append(" | members=${activeMembers.joinToString(",")}")
                        },
                        fontSize = 10.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.6f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }

        // 消息列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            state = listState
        ) {
            items(messages, key = { it.id }) { msg ->
                GroupChatBubble(
                    message = msg,
                    memberAvatar = memberAvatars[msg.from],
                    allMembers = activeMembers,
                    voicePlayer = voicePlayer,
                    playingVoicePath = playingVoicePath,
                    onPlayingVoicePathChange = { playingVoicePath = it },
                    onRetry = msg.takeIf { it.isError }?.let { failed -> { retryMember(failed) } },
                    retrying = retryingMessage == msg
                )
                Spacer(Modifier.height(6.dp))
            }

            // 打字指示器
            if (typingMember != null) {
                item {
                    Row(
                        Modifier.padding(start = 44.dp, top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(AchatTheme.colors.primary)
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(AchatTheme.colors.primary.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(AchatTheme.colors.primary.copy(alpha = 0.3f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (typingMember == "判断中…") "判断中…"
                            else "${typingMember} 正在输入…",
                            fontSize = 12.sp,
                            color = AchatTheme.colors.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // 自动滚动到底部
            item(key = "scroll_helper") {
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            }
        }

        // 输入栏
        Surface(
            Modifier.fillMaxWidth(),
            color = AchatTheme.colors.surface,
            shadowElevation = 8.dp
        ) {
            // 待发图片预览条
            if (pendingImagePath != null) {
                Box(
                    Modifier.fillMaxWidth().background(AchatTheme.colors.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            "已选图片，输入文字后点发送${if (inputText.isBlank()) "（或直接发送）" else ""}",
                            fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { pendingImagePath = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "取消", tint = AchatTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "+" 按钮：相册 / 拍照
                Box {
                    Box(
                        Modifier.size(36.dp).background(
                            AchatTheme.colors.divider.copy(alpha = 0.5f), CircleShape
                        ).clickable(enabled = !sending) { plusMenuExpanded = true },
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
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AchatTheme.colors.background)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text("说点什么... @某人可指定先回", color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = LocalTextStyle.current.copy(
                            color = AchatTheme.colors.onSurface,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(AchatTheme.colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending
                    )
                    // @提及候选弹窗
                    val active = MentionParser.activeMentionQuery(inputText, activeMembers)
                    if (active != null) {
                        val (_, query) = active
                        val candidates = activeMembers.filter { it.startsWith(query) && it != query }
                        DropdownMenu(
                            expanded = candidates.isNotEmpty(),
                            onDismissRequest = {}
                        ) {
                            candidates.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name, fontSize = 14.sp) },
                                    onClick = {
                                        // 把末尾的 "@query" 替换为 "@name "
                                        val atIdx = inputText.lastIndexOf('@')
                                        if (atIdx >= 0) {
                                            inputText = inputText.substring(0, atIdx) + "@$name "
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    // 等待回复时显示"停止"按钮（红色），取消当前请求
                    IconButton(
                        onClick = {
                            sendJob?.cancel()
                            sendJob = null
                            try { AiServiceFactory.getService().cancel() } catch (ce: Exception) { android.util.Log.w("GroupChat", "cancel failed", ce) }
                            sending = false
                            typingMember = null
                        },
                        modifier = Modifier.size(40.dp).background(Color(0xFFFA5151), CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            "停止",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    val canSend = messagesLoaded && (inputText.isNotBlank() || pendingImagePath != null)
                    if (canSend) IconButton(
                        onClick = { sendMessage(inputText.trim(), pendingImagePath) },
                        enabled = canSend,
                        modifier = Modifier.size(40.dp).background(
                            if (canSend) AchatTheme.colors.primary
                            else AchatTheme.colors.onSurface.copy(alpha = 0.1f),
                            CircleShape
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            "发送",
                            tint = if (canSend) Color.White
                            else AchatTheme.colors.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                        }
                    else Box(
                        Modifier.size(40.dp)
                            .background(
                                if (isRecording) Color(0xFFFA5151) else AchatTheme.colors.primary,
                                CircleShape
                            )
                            .then(
                                if (voicePermissionGranted && messagesLoaded) {
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
                                                        sttHelper.start(
                                                            onResult = { transcript ->
                                                                sendMessage(
                                                                    transcript,
                                                                    voicePath = file.absolutePath,
                                                                    voiceDuration = duration,
                                                                    voiceTranscript = transcript
                                                                )
                                                                sttHelper.stop()
                                                            },
                                                            onError = {
                                                                sendMessage(
                                                                    "",
                                                                    voicePath = file.absolutePath,
                                                                    voiceDuration = duration
                                                                )
                                                                sttHelper.stop()
                                                                Toast.makeText(ctx, "语音转文字失败，已发送音频", Toast.LENGTH_SHORT).show()
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
                        Icon(Icons.Filled.Mic, "语音", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupChatBubble(
    message: GroupChatMessage,
    memberAvatar: androidx.compose.ui.graphics.ImageBitmap?,
    allMembers: List<String>,
    voicePlayer: VoicePlayer,
    playingVoicePath: String?,
    onPlayingVoicePathChange: (String?) -> Unit,
    onRetry: (() -> Unit)?,
    retrying: Boolean
) {
    val isMe = message.isMe
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bubbleColor = if (isMe) AchatTheme.colors.primary.copy(alpha = 0.15f)
        else if (message.isError) Color(0xFFFFF0F0)
        else AchatTheme.colors.surface
    val textColor = AchatTheme.colors.onSurface
    val ctx = LocalContext.current
    var showImagePreview by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val hasImage = message.imagePath != null
    val hasVoice = message.voicePath != null
    val hasText = !hasVoice && message.text.isNotEmpty() && message.text != "[图片]"

    fun toggleVoice() {
        val path = message.voicePath ?: return
        if (playingVoicePath == path) {
            voicePlayer.stop()
            onPlayingVoicePathChange(null)
        } else {
            voicePlayer.play(
                path = path,
                onStart = { onPlayingVoicePathChange(path) },
                onComplete = { onPlayingVoicePathChange(null) }
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        // 非自己的消息显示发言人名字
        if (!isMe && message.from.isNotEmpty()) {
            Text(
                message.from,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = if (isMe) 0.dp else 44.dp, bottom = 2.dp)
            )
        }

        Row(
            modifier = Modifier.widthIn(max = 280.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
        ) {
            if (!isMe) {
                // 成员头像
                val avatarBmp = memberAvatar
                if (avatarBmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBmp,
                        contentDescription = message.from,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(AchatTheme.colors.primary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            message.from.take(1),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AchatTheme.colors.onSurface
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 4.dp,
                            bottomEnd = if (isMe) 4.dp else 16.dp
                        ))
                        .background(bubbleColor)
                        .then(if (hasImage && !hasText) Modifier.padding(4.dp) else Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        .combinedClickable(
                            onClick = {
                                if (hasImage) showImagePreview = true else if (hasVoice) toggleVoice()
                            },
                            onLongClick = { if (hasImage) menuExpanded = true }
                        )
                ) {
                    if (hasImage) {
                        Box {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(ctx)
                                    .data(java.io.File(message.imagePath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "图片",
                                modifier = Modifier.sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
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
                                            val src = java.io.File(message.imagePath)
                                            if (src.exists()) {
                                                val bis = java.io.BufferedInputStream(java.io.FileInputStream(src))
                                                val bitmap = android.graphics.BitmapFactory.decodeStream(bis)
                                                bis.close()
                                                if (bitmap != null) {
                                                    android.provider.MediaStore.Images.Media.insertImage(
                                                        ctx.contentResolver, bitmap, "wisp_group_${System.currentTimeMillis()}.jpg", "Wisp group image"
                                                    )
                                                    Toast.makeText(ctx, "已保存到相册", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("GroupChatScreen", "save to gallery failed", e)
                                            Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (hasVoice) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (playingVoicePath == message.voicePath) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playingVoicePath == message.voicePath) "暂停" else "播放",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(8) { i ->
                                    val height = when (i) {
                                        0, 7 -> 6.dp
                                        1, 6 -> 10.dp
                                        2, 5 -> 14.dp
                                        else -> 18.dp
                                    }
                                    Box(
                                        Modifier.size(width = 3.dp, height = height)
                                            .background(textColor.copy(alpha = 0.5f), CircleShape)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${message.voiceDuration}\"",
                                fontSize = 12.sp,
                                color = textColor.copy(alpha = 0.6f)
                            )
                        }
                        val transcript = message.voiceTranscript?.takeIf { it.isNotBlank() }
                            ?: message.text.takeIf { it.isNotBlank() && it != "[语音消息]" }
                        if (!transcript.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                highlightMentions(transcript, allMembers, AchatTheme.colors.primary),
                                fontSize = 13.sp,
                                color = textColor,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    if (hasText) {
                        if (hasImage) Spacer(Modifier.height(6.dp))
                        Text(
                            highlightMentions(message.text, allMembers, AchatTheme.colors.primary),
                            fontSize = 14.sp,
                            color = textColor,
                            lineHeight = 20.sp
                        )
                    }
                }
                if (message.isError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    ) {
                        if (retrying) {
                            Text(
                                "\u6b63\u5728\u91cd\u8bd5\u2026",
                                fontSize = 11.sp,
                                color = Color(0xFFD14343)
                            )
                        }
                        if (!retrying && onRetry != null) {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "\u91cd\u8bd5",
                                    tint = Color(0xFFD14343),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                // 时间戳
                if (message.time.isNotEmpty()) {
                    Text(
                        message.time,
                        fontSize = 10.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                    )
                }
            }

            if (isMe) {
                Spacer(Modifier.width(6.dp))
                // 用户默认头像占位
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(AchatTheme.colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("我", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    // 图片全屏预览
    if (hasImage && showImagePreview) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showImagePreview = false }
        ) {
            Box(
                Modifier.fillMaxSize().clickable { showImagePreview = false },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(ctx)
                        .data(java.io.File(message.imagePath))
                        .crossfade(true).build(),
                    contentDescription = "大图预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * 把消息文本中的 @提及渲染为高亮（primary 色 + 加粗）。
 * 仅高亮 [members] 中真实存在的成员名。基础文字颜色由 Text.color 兜底。
 */
private fun highlightMentions(
    text: String,
    members: List<String>,
    primaryColor: Color
): AnnotatedString = buildAnnotatedString {
    if (members.isEmpty() || text.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    val candidateSet = members.toSet()
    val regex = Regex("@([\\p{IsHan}A-Za-z0-9_]+)")
    var cursor = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val name = match.groupValues[1]
        if (name in candidateSet) {
            withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
                append(match.value)
            }
        } else {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
