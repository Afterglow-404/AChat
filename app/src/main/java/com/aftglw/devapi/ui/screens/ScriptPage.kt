package com.aftglw.devapi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

private data class DispBubble(
    val text: String,
    val label: String = "",
    val isNarration: Boolean = false,
    val isUser: Boolean = false,
    val isSystem: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPage(script: LingChatScript, initialChapter: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

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
    // 自由对话模式
    var freeDlg by remember { mutableStateOf(false) }
    var freeMaxRounds by remember { mutableIntStateOf(-1) }
    var freeRound by remember { mutableIntStateOf(0) }
    var freeEndLine by remember { mutableStateOf("结束") }
    var freeEndPrompt by remember { mutableStateOf("") }
    var freeDlgChar by remember { mutableStateOf("") }
    var freeDlgHint by remember { mutableStateOf("") }
    var freeDlgPromptText by remember { mutableStateOf("") }
    var currentBgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val listState = rememberLazyListState()

    fun addBubble(text: String, label: String = "", isNarration: Boolean = false, isUser: Boolean = false, isSystem: Boolean = false) {
        bubbles.add(DispBubble(text = text, label = label, isNarration = isNarration, isUser = isUser, isSystem = isSystem))
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

        when (ev.type) {
            "narration" -> {
                addBubble(text = ev.text, isNarration = true)
                scope.launch {
                    delay((ev.text.length * 40L).coerceIn(200L, 1500L))
                    advance()
                }
            }

            "dialogue" -> {
                val isUser = ev.character == "你" || ev.character == "用户" || ev.character == "我"
                addBubble(text = ev.text, label = if (isUser) "" else ev.character, isUser = isUser)
                if (ev.emotion.isNotBlank()) {
                    addBubble(text = "（${ev.emotion}）", isNarration = true)
                }
            }

            "ai_dialogue" -> {
                val charName = ev.character.ifEmpty { "MAIN" }
                val prompt = ev.prompt.ifEmpty { "请以$charName 的身份自然地回复一句话" }
                waiting = true
                scope.launch {
                    val reply = callAi(prompt)
                    addBubble(text = reply ?: "...", label = charName)
                    waiting = false
                    advance()
                }
            }

            "player" -> {
                addBubble(text = ev.text, isUser = true)
            }

            "input" -> {
                inputHint = ev.hint.ifEmpty { "请输入..." }
                showInput = true
                waiting = true // 等待用户输入
            }

            "choices" -> {
                // 过滤有条件且不满足的选项
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
                freeDlgPromptText = ev.dialogPrompt
                showInput = true
                inputHint = freeDlgHint
            }

            "chapter_end" -> {
                if (ev.nextChapter.isNotBlank() && script.chapters.containsKey(ev.nextChapter)) {
                    val next = ev.nextChapter
                    currentChapter = next
                    events = loadEventsForChapter(next)
                    eventIndex = 0
                    advance()
                } else {
                    finished = true
                }
            }

            "background" -> {
                loadBackground(ev.imagePath, script, ctx, scope) { currentBgBitmap = it }
                advance()
            }
            "background_effect" -> advance()
            "music", "sound", "present_pic", "modify_character", "ambient", "set_variable" -> {
                // UI 效果类事件 - 已通过解析处理，直接跳过
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
                // 自由对话模式
                addBubble(text = input, isUser = true)
                freeRound++
                waiting = true
                scope.launch {
                    val reply = callAi(freeDlgPromptText.ifEmpty { "你是$freeDlgChar，请自然地对话。" })
                    if (reply != null) addBubble(text = reply, label = freeDlgChar)

                    // 检查是否结束
                    val shouldEnd = input.contains(freeEndLine) ||
                            (freeMaxRounds > 0 && freeRound >= freeMaxRounds)
                    if (shouldEnd) {
                        freeDlg = false
                        waiting = false
                        // 用 endPrompt 继续
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
                // 匹配选项
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

    // 启动
    LaunchedEffect(Unit) {
        ScriptEngine.reset()
        advance()
    }

    // 自动滚底
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    val pageBg = AchatTheme.colors.background
    val pageSurface = AchatTheme.colors.surface
    val pageText = AchatTheme.colors.onSurface

    Box(Modifier.fillMaxSize()) {
        if (currentBgBitmap != null) {
            Image(currentBgBitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().background(if (currentBgBitmap != null) Color.Transparent else pageBg)) {
        // 顶栏
        CenterAlignedTopAppBar(
            title = { Text(script.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pageText) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pageText) } },
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
            itemsIndexed(bubbles, key = { i, _ -> i }) { _, b ->
                when {
                    b.isNarration -> Text(b.text, fontSize = 13.sp, color = pageText.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center, lineHeight = 20.sp)
                    b.isUser -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(Modifier.background(AchatTheme.colors.primary.copy(alpha = 0.12f), AchatTheme.shapes.bubbleMe).padding(14.dp).widthIn(max = 270.dp)) {
                            Text(b.text, fontSize = 14.sp, color = pageText)
                        }
                    }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Column(Modifier.widthIn(max = 290.dp)) {
                            if (b.label.isNotEmpty()) Text(b.label, fontSize = 11.sp, color = AchatTheme.colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                            Box(Modifier.background(pageSurface, AchatTheme.shapes.bubbleAi).padding(14.dp)) {
                                Text(b.text, fontSize = 14.sp, color = pageText)
                            }
                        }
                    }
                }
            }
            if (waiting && !showInput && showChoices.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(12.dp)) { Text("● ● ●", fontSize = 11.sp, color = pageText.copy(alpha = 0.3f)) } }
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
            Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f), placeholder = { Text(inputHint.ifEmpty { "输入..." }, fontSize = 14.sp) },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onUserInput(inputText); inputText = "" })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onUserInput(inputText); inputText = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = AchatTheme.colors.primary)
                }
            }
        }

        // 继续按钮（无输入/无选项/不等待时）
        if (!finished && !showInput && showChoices.isEmpty() && !waiting) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { advance() }) { Text("继续 ▼", fontSize = 14.sp) }
            }
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
