package com.aftglw.devapi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Context
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.*
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private fun now() = timeFormat.format(java.util.Date())

private data class DispBubble(
    val text: String,
    val label: String = "",
    val isNarration: Boolean = false,
    val isUser: Boolean = false,
    val isSystem: Boolean = false,
    val time: String = ""
)

/** 中文/混合文本的估计阅读延迟（毫秒/字符） */
private const val CN_READ_MS_PER_CHAR = 60L
private const val EN_READ_MS_PER_CHAR = 30L
/** 保存用的 preference key 前缀 */
private const val PREFS_KEY = "script_progress"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPage(
    script: LingChatScript,
    initialChapter: String = "",
    characterPrompt: String = "",
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    val saveKey = "script_${script.name}"

    // 状态
    val bubbles = remember { mutableStateListOf<DispBubble>() }
    var currentChapter by remember { mutableStateOf(initialChapter.ifEmpty { script.introChapter }) }
    var eventIndex by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf(loadScriptEvents(script, currentChapter)) }
    var waiting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var showChoices by remember { mutableStateOf<List<ScriptChoice>>(emptyList()) }
    var allowFreeInput by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }
    var inputHint by remember { mutableStateOf("") }
    var aiMode by remember { mutableStateOf(false) }
    var currentBgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // 图片展示
    var showPicDialog by remember { mutableStateOf(false) }
    var picDialogBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // 音乐播放器
    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            try { mediaPlayer.release() } catch (_: Exception) {}
        }
    }

    // 自由对话模式
    var freeDlg by remember { mutableStateOf(false) }
    var freeMaxRounds by remember { mutableIntStateOf(-1) }
    var freeRound by remember { mutableIntStateOf(0) }
    var freeEndLine by remember { mutableStateOf("结束") }
    var freeEndPrompt by remember { mutableStateOf("") }
    var freeDlgChar by remember { mutableStateOf("") }
    var freeDlgHint by remember { mutableStateOf("") }
    var freeDlgPromptText by remember { mutableStateOf("") }
    var waitingForContinue by remember { mutableStateOf(false) }

    // 动画：打字指示器
    var dotCount by remember { mutableIntStateOf(1) }
    LaunchedEffect(waiting) {
        while (waiting && !showInput && showChoices.isEmpty()) {
            dotCount = (dotCount % 3) + 1
            delay(400L)
        }
    }

    // 上次显示过的章节名（避免重复）
    var lastChapterTitle by remember { mutableStateOf("") }

    val assetBase = script.assetBasePath
    val listState = rememberLazyListState()

    /** 字符级阅读延迟 */
    fun estimateReadDelay(text: String): Long {
        var cn = 0; var en = 0
        for (c in text) { if (c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F) cn++ else en++ }
        val total = cn * CN_READ_MS_PER_CHAR + en * EN_READ_MS_PER_CHAR
        // 段落感：每 30 字额外加 200ms
        val paragraphBonus = (text.length / 30) * 200L
        // 多行文本：每行额外加 300ms
        val lineCount = text.count { it == '\n' } + 1
        val lineBonus = (lineCount - 1) * 300L
        return (total + paragraphBonus + lineBonus).coerceIn(500L, 4000L)
    }

    fun addBubble(text: String, label: String = "", isNarration: Boolean = false, isUser: Boolean = false, isSystem: Boolean = false) {
        bubbles.add(DispBubble(text = text, label = label, isNarration = isNarration, isUser = isUser, isSystem = isSystem, time = now()))
    }

    fun loadEventsForChapter(name: String): List<ScriptEvent> {
        return ScriptEngine.getChapter(script, name)
    }

    suspend fun callAi(prompt: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                AiServiceFactory.getService().sendMessage(emptyList(), "", prompt)
            } catch (_: Exception) { null }
        }
    }

    /** 保存进度 */
    fun saveProgress() {
        prefs.edit()
            .putString("${saveKey}_chapter", currentChapter)
            .putInt("${saveKey}_event", eventIndex)
            .apply()
    }

    /** 清除保存的进度 */
    fun clearProgress() {
        prefs.edit()
            .remove("${saveKey}_chapter")
            .remove("${saveKey}_event")
            .apply()
    }

    /** 加载保存的进度 */
    fun loadSavedProgress(): Pair<String, Int>? {
        val ch = prefs.getString("${saveKey}_chapter", null) ?: return null
        val ev = prefs.getInt("${saveKey}_event", -1)
        if (ev < 0 || !script.chapters.containsKey(ch)) return null
        return ch to ev
    }

    /** 显示章节标题 */
    fun showChapterTitle(chapterKey: String) {
        val title = script.chapterNames[chapterKey]?.takeIf { it != lastChapterTitle } ?: return
        lastChapterTitle = title
        addBubble(text = "—— $title ——", isNarration = true)
    }

    fun advance() {
        if (finished || waiting) return

        // 跳过被条件排除的事件
        while (eventIndex < events.size) {
            val ev = events[eventIndex]
            if (!ScriptEngine.checkCondition(ev.condition)) { eventIndex++; continue }
            break
        }

        if (eventIndex >= events.size) { finished = true; return }

        val ev = events[eventIndex]
        eventIndex++
        saveProgress()

        when (ev.type) {
            "narration" -> {
                addBubble(text = ev.text, isNarration = true)
                scope.launch {
                    delay(estimateReadDelay(ev.text))
                    waitingForContinue = true
                }
            }

            "dialogue" -> {
                val isUser = ev.character == "你" || ev.character == "用户" || ev.character == "我"
                addBubble(text = ev.text, label = if (isUser) "" else ev.character, isUser = isUser)
                if (ev.emotion.isNotBlank()) {
                    addBubble(text = "（${ev.emotion}）", isNarration = true)
                }
                waitingForContinue = true
            }

            "ai_dialogue" -> {
                val charName = ev.character.ifEmpty { "MAIN" }
                val prompt = buildString {
                    if (characterPrompt.isNotBlank()) append(characterPrompt).append("\n\n")
                    append(ev.prompt.ifEmpty { "请以$charName 的身份自然地回复一句话" })
                }
                waiting = true
                val bubbleIdx = bubbles.size
                bubbles.add(DispBubble(text = "...", label = charName))
                scope.launch {
                    val reply = callAi(prompt) ?: "..."
                    if (bubbleIdx in bubbles.indices) {
                        bubbles[bubbleIdx] = bubbles[bubbleIdx].copy(text = reply)
                    }
                    waiting = false
                    waitingForContinue = true
                }
            }

            "player" -> {
                addBubble(text = ev.text, isUser = true)
                waitingForContinue = true
            }

            "input" -> {
                inputHint = ev.hint.ifEmpty { "请输入..." }
                showInput = true
                waiting = true
            }

            "choices" -> {
                val valid = ev.options.filter { ScriptEngine.checkCondition(it.condition) }
                showChoices = valid
                allowFreeInput = ev.allowFree
                waiting = true
            }

            "free_dialogue" -> {
                freeDlg = true
                freeDlgChar = ev.character.ifEmpty { "MAIN" }
                freeDlgHint = ev.hint.ifEmpty { "自由对话..." }
                freeMaxRounds = ev.maxRounds
                freeRound = 0
                freeEndLine = ev.endLine
                freeEndPrompt = ev.endPrompt
                freeDlgPromptText = buildString {
                    if (characterPrompt.isNotBlank()) append(characterPrompt).append("\n\n")
                    append(ev.dialogPrompt.ifEmpty {
                        if (characterPrompt.isNotBlank()) "请以${freeDlgChar}的身份继续自然地对话"
                        else "你是$freeDlgChar，请自然地对话。"
                    })
                }
                showInput = true
                inputHint = freeDlgHint
            }

            "chapter_end" -> {
                // 分支路由
                if (ev.branches.isNotEmpty()) {
                    val matchedBranch = ev.branches.firstOrNull { ScriptEngine.checkCondition(it.condition) }
                    val fallback = ev.branches.firstOrNull { it.default }
                    val target = matchedBranch ?: fallback
                    if (target != null && script.chapters.containsKey(target.nextChapter)) {
                        if (target.text.isNotBlank()) {
                            addBubble(text = "→ ${target.text}", isSystem = true)
                        }
                        val next = target.nextChapter
                        currentChapter = next
                        events = loadEventsForChapter(next)
                        eventIndex = 0
                        showChapterTitle(next)
                        saveProgress()
                        advance()
                    } else {
                        finished = true
                        clearProgress()
                    }
                } else if (ev.nextChapter.isNotBlank() && script.chapters.containsKey(ev.nextChapter)) {
                    val next = ev.nextChapter
                    currentChapter = next
                    events = loadEventsForChapter(next)
                    eventIndex = 0
                    showChapterTitle(next)
                    saveProgress()
                    advance()
                } else {
                    finished = true
                    clearProgress()
                    // 通关时记录
                    ScriptProgress.markCompleted(ctx, script.name)
                }
            }

            "background" -> {
                loadBackground(ev.imagePath, script, ctx, scope) { currentBgBitmap = it }
                waitingForContinue = true
            }

            "background_effect" -> {
                val effectName = ev.effect.ifEmpty { "背景变化" }
                addBubble(text = "✨ $effectName", isSystem = true)
                waitingForContinue = true
            }

            "modify_character" -> {
                val charName = ev.character.ifEmpty { "角色" }
                val action = when (ev.action) {
                    "show_character" -> "出现在眼前"
                    "hide_character" -> "离开了"
                    else -> ev.action.ifEmpty { "出现了变化" }
                }
                addBubble(text = "⚡ $charName $action", isSystem = true)
                waitingForContinue = true
            }

            "music", "ambient" -> {
                val musicPath = ev.imagePath.ifEmpty { ev.text.ifEmpty { "" } }
                if (musicPath.isNotBlank()) {
                    try {
                        mediaPlayer.reset()
                        val afd = ctx.assets.openFd("$assetBase/Music/$musicPath")
                        mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        mediaPlayer.isLooping = ev.type == "ambient"
                        mediaPlayer.prepare()
                        mediaPlayer.start()
                    } catch (_: Exception) {}
                }
                waitingForContinue = true
            }

            "sound" -> {
                val soundPath = ev.imagePath.ifEmpty { ev.content.ifEmpty { "" } }
                if (soundPath.isNotBlank()) {
                    try {
                        val sp = MediaPlayer()
                        val afd = ctx.assets.openFd("$assetBase/Music/$soundPath")
                        sp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        sp.setOnCompletionListener { try { sp.release() } catch (_: Exception) {} }
                        sp.prepare()
                        sp.start()
                    } catch (_: Exception) {}
                }
                waitingForContinue = true
            }

            "present_pic" -> {
                val picPath = ev.imagePath.ifEmpty { ev.content.ifEmpty { "" } }
                if (picPath.isNotBlank()) {
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            try {
                                val fullPath = "$assetBase/Assets/Images/$picPath"
                                ctx.assets.open(fullPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                            } catch (_: Exception) { null }
                        }
                        if (bmp != null) {
                            picDialogBitmap = bmp
                            showPicDialog = true
                        } else {
                            waitingForContinue = true
                        }
                    }
                } else {
                    waitingForContinue = true
                }
            }

            "set_variable" -> {
                ScriptEngine.applyAction(ScriptAction(
                    type = ev.action.ifEmpty { "set_variable" },
                    content = ev.content.ifEmpty { ev.text }
                ))
                advance()
            }

            else -> advance()
        }
    }

    /** 处理用户输入 */
    fun onUserInput(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return

        showChoices = emptyList()
        allowFreeInput = false
        showInput = false

        when {
            freeDlg -> {
                addBubble(text = input, isUser = true)
                freeRound++
                waiting = true
                val bubbleIdx = bubbles.size
                bubbles.add(DispBubble(text = "...", label = freeDlgChar))
                scope.launch {
                    val reply = callAi(freeDlgPromptText.ifEmpty { "你是$freeDlgChar，请自然地对话。" })
                    val fullText = reply ?: "..."
                    if (bubbleIdx in bubbles.indices) {
                        bubbles[bubbleIdx] = bubbles[bubbleIdx].copy(text = fullText)
                    }
                    val shouldEnd = input.contains(freeEndLine) ||
                            (freeMaxRounds > 0 && freeRound >= freeMaxRounds)
                    if (shouldEnd) {
                        freeDlg = false
                        waiting = false
                        if (freeEndPrompt.isNotBlank()) {
                            val endReply = callAi(freeEndPrompt)
                            if (endReply != null) addBubble(text = endReply, label = freeDlgChar)
                        }
                        advance()
                    } else {
                        waiting = false
                        showInput = true
                        inputHint = freeDlgHint
                    }
                }
            }
            showChoices.isNotEmpty() -> {
                val matched = showChoices.firstOrNull { it.text == input }
                if (matched != null) {
                    addBubble(text = input, isUser = true)
                    matched.actions.forEach { ScriptEngine.applyAction(it) }
                    showChoices = emptyList()
                    waiting = false
                    advance()
                } else if (allowFreeInput) {
                    addBubble(text = input, isUser = true)
                    showChoices = emptyList()
                    waiting = false
                    advance()
                }
            }
            else -> {
                addBubble(text = input, isUser = true)
                waiting = false
                advance()
            }
        }
    }

    /** AI 模式：自由发送消息 */
    fun onAiModeSend(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        addBubble(text = input, isUser = true)
        waitingForContinue = false
        val bubbleIdx = bubbles.size
        val charName = "MAIN"
        bubbles.add(DispBubble(text = "...", label = charName))
        scope.launch {
            val prompt = buildString {
                if (characterPrompt.isNotBlank()) append(characterPrompt).append("\n\n")
                append("请以$charName 的身份自然地回复用户的消息。用户说：$input")
            }
            val reply = callAi(prompt) ?: "..."
            if (bubbleIdx in bubbles.indices) {
                bubbles[bubbleIdx] = bubbles[bubbleIdx].copy(text = reply)
            }
            waitingForContinue = true
        }
    }

    // 启动
    LaunchedEffect(Unit) {
        ScriptEngine.reset()
        val saved = loadSavedProgress()
        if (saved != null) {
            currentChapter = saved.first
            events = loadEventsForChapter(saved.first)
            eventIndex = saved.second
            showChapterTitle(saved.first)
        }
        advance()
    }

    // 自动滚底
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) {
            try { listState.animateScrollToItem(bubbles.size - 1) } catch (_: Exception) {}
        }
    }

    val pageBg = AchatTheme.colors.background
    val pageText = AchatTheme.colors.onSurface
    val pageSurface = AchatTheme.colors.surface

    Box(Modifier.fillMaxSize()) {
        if (currentBgBitmap != null) {
            Image(currentBgBitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().background(if (currentBgBitmap != null) Color.Transparent else pageBg)) {
        // 顶栏
        CenterAlignedTopAppBar(
            title = { Text(script.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pageText) },
            navigationIcon = { IconButton(onClick = {
                saveProgress()
                onBack()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pageText) } },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Text("AI", fontSize = 11.sp, color = if (aiMode) Color(0xFF07C160) else Color.Gray)
                    Switch(checked = aiMode, onCheckedChange = { aiMode = it },
                        modifier = Modifier.padding(horizontal = 4.dp).heightIn(max = 24.dp),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF07C160)))
                    Text("脚本", fontSize = 11.sp, color = if (!aiMode) Color(0xFF07C160) else Color.Gray)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = pageSurface),
            modifier = Modifier.statusBarsPadding()
        )
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        // 气泡列表
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(bubbles, key = { i, _ -> i }) { i, b ->
                when {
                    b.isSystem -> Text(b.text, fontSize = 11.sp, color = pageText.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center, lineHeight = 18.sp)
                    b.isNarration -> Text(b.text, fontSize = 13.sp, color = pageText.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center, lineHeight = 20.sp)
                    b.isUser -> Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.widthIn(max = 270.dp)) {
                            Box(Modifier.background(AchatTheme.colors.chatBubbleMe, AchatTheme.shapes.bubbleMe).padding(12.dp)) {
                                Column {
                                    Text(b.text, fontSize = 15.sp, color = pageText)
                                    if (b.time.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            Text(b.time, fontSize = 10.sp, color = pageText.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(32.dp).clip(CircleShape).background(AchatTheme.colors.primary), contentAlignment = Alignment.Center) {
                            Text("我", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(AchatTheme.colors.divider), contentAlignment = Alignment.Center) {
                            Text(b.label.take(1).ifEmpty { "?" }, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.widthIn(max = 290.dp)) {
                            if (b.label.isNotEmpty()) Text(b.label, fontSize = 11.sp, color = AchatTheme.colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                            Box(Modifier.background(AchatTheme.colors.chatBubbleAi, AchatTheme.shapes.bubbleAi).padding(12.dp)) {
                                Column {
                                    Text(b.text, fontSize = 15.sp, color = pageText)
                                    if (b.time.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            Text(b.time, fontSize = 10.sp, color = pageText.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (waiting && !showInput && showChoices.isEmpty()) {
                val dots = buildString { repeat(dotCount) { append("● ") } }
                item { Box(Modifier.fillMaxWidth().padding(12.dp)) { Text(dots.trimEnd(), fontSize = 11.sp, color = pageText.copy(alpha = 0.3f)) } }
            }
            // 选项
            if (showChoices.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        showChoices.forEach { c ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.75f).clip(AchatTheme.shapes.card),
                                shape = AchatTheme.shapes.card, color = pageSurface, tonalElevation = 2.dp,
                                onClick = { onUserInput(c.text) }
                            ) { Text(c.text, fontSize = 14.sp, color = pageText, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
            // 剧终
            if (finished) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("— 剧终 —", fontSize = 16.sp, color = pageText.copy(alpha = 0.3f)) } }
            }
        }

        // 输入框
        if (showInput && !finished) {
            var inputText by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                    placeholder = { Text(inputHint.ifEmpty { "说些什么吧..." }, fontSize = 14.sp) },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.5f),
                        unfocusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.3f)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onUserInput(inputText); inputText = "" })
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onUserInput(inputText); inputText = "" },
                    modifier = Modifier.background(AchatTheme.colors.primary, RoundedCornerShape(18.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) { Text("发送", fontSize = 14.sp) }
            }
        }

        // AI 模式输入框
        if (aiMode && waitingForContinue && !finished && !showInput && showChoices.isEmpty()) {
            var aiInput by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = aiInput, onValueChange = { aiInput = it },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                    placeholder = { Text("与角色自由对话...", fontSize = 14.sp) },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.5f),
                        unfocusedContainerColor = AchatTheme.colors.divider.copy(alpha = 0.3f)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onAiModeSend(aiInput); aiInput = "" })
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onAiModeSend(aiInput); aiInput = "" },
                    modifier = Modifier.background(AchatTheme.colors.primary, RoundedCornerShape(18.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) { Text("发送", fontSize = 14.sp) }
            }
        }

        // 继续按钮
        if (waitingForContinue) {
            Box(Modifier.fillMaxWidth().clickable { waitingForContinue = false; advance() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("▼ 点击继续", fontSize = 12.sp, color = pageText.copy(alpha = 0.35f))
            }
        }
        // 图片展示弹窗
        if (showPicDialog && picDialogBitmap != null) {
            AlertDialog(
                onDismissRequest = { showPicDialog = false; waitingForContinue = true },
                text = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(picDialogBitmap!!, "插画", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    }
                },
                confirmButton = { TextButton(onClick = { showPicDialog = false; waitingForContinue = true }) { Text("关闭") } }
            )
        }
    }
    }
}

fun loadBackground(path: String, script: LingChatScript, ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, setBg: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit) {
    if (path.isBlank() || script.assetBasePath.isBlank()) return
    scope.launch {
        val bmp = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val fullPath = "${script.assetBasePath}/Assets/Backgrounds/$path"
                ctx.assets.open(fullPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (_: Exception) { null }
        }
        if (bmp != null) setBg(bmp)
    }
}

private fun loadScriptEvents(script: LingChatScript, chapter: String): List<ScriptEvent> {
    return ScriptEngine.getChapter(script, chapter)
}
