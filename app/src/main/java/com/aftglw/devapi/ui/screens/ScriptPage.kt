package com.aftglw.devapi.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val isUser: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPage(script: LingChatScript, initialChapter: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bubbles = remember { mutableStateListOf<DispBubble>() }
    var currentChapter by remember { mutableStateOf(initialChapter.ifEmpty { script.introChapter }) }
    var eventIndex by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf(loadScriptEvents(script, currentChapter)) }
    var waiting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<ScriptChoice>>(emptyList()) }
    var aiMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun isUserChar(char: String) = char == "你" || char == "用户" || char == "我"

    /** 跳过被条件过滤的事件 */
    fun skipToValid(): Int {
        var idx = eventIndex
        while (idx < events.size) {
            val ev = events[idx]
            if (!ScriptEngine.checkCondition(ev.condition)) { idx++; continue }
            if (ev.type == "dialogue" && ev.options.isNotEmpty()) { choices = ev.options; return idx }
            break
        }
        return idx
    }

    fun advanceOne() {
        if (finished || waiting) return
        eventIndex = skipToValid()
        if (eventIndex >= events.size) { finished = true; return }

        val ev = events[eventIndex]
        eventIndex++

        when (ev.type) {
            "narration" -> {
                bubbles.add(DispBubble(text = ev.text, isNarration = true))
                // 旁白自动推进
                scope.launch {
                    val ms = (ev.text.length * 50L).coerceIn(300L, 2000L)
                    delay(ms)
                    advanceOne()
                }
            }
            "dialogue" -> {
                val isUser = isUserChar(ev.character)
                if (aiMode && !isUser) {
                    // AI 扮演角色生成回复
                    waiting = true
                    scope.launch {
                        val aiReply = withContext(Dispatchers.IO) {
                            try {
                                AiServiceFactory.getService().sendMessage(
                                    emptyList(), "",
                                    "你是${ev.character}。请用${ev.character}的口吻说：${ev.text}"
                                )
                            } catch (_: Exception) { null }
                        }
                        bubbles.add(DispBubble(
                            text = aiReply ?: ev.text,
                            label = ev.character
                        ))
                        waiting = false
                        advanceOne()
                    }
                } else {
                    bubbles.add(DispBubble(
                        text = ev.text,
                        label = if (isUser) "" else ev.character,
                        isUser = isUser
                    ))
                    // AI 模式下用户发言后 AI 自动回应
                    if (aiMode && isUser) {
                        waiting = true
                        scope.launch {
                            val aiReply = withContext(Dispatchers.IO) {
                                try {
                                    AiServiceFactory.getService().sendMessage(
                                        emptyList(), ev.text,
                                        "请自然地回应对方。用日常口语短句。"
                                    )
                                } catch (_: Exception) { null }
                            }
                            if (aiReply != null) {
                                bubbles.add(DispBubble(text = aiReply, label = "AI"))
                            }
                            waiting = false
                            advanceOne()
                        }
                    }
                }
            }
            "chapter_end" -> {
                if (ev.nextChapter.isNotBlank() && script.chapters.containsKey(ev.nextChapter)) {
                    val next = ev.nextChapter
                    // 自动跳转下一章
                    currentChapter = next
                    events = loadScriptEvents(script, next)
                    eventIndex = 0
                    advanceOne()
                } else {
                    finished = true
                }
            }
        }
    }

    // 启动
    LaunchedEffect(Unit) {
        ScriptEngine.reset()
        advanceOne()
    }

    // 自动滚底
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    val pageBg = AchatTheme.colors.background
    val pageSurface = AchatTheme.colors.surface
    val pageText = AchatTheme.colors.onSurface

    Column(Modifier.fillMaxSize().background(pageBg)) {
        // 顶栏
        CenterAlignedTopAppBar(
            title = {
                Text(script.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pageText)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pageText)
                }
            },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Text("AI", fontSize = 11.sp, color = if (aiMode) Color(0xFF07C160) else Color.Gray)
                    Switch(
                        checked = aiMode,
                        onCheckedChange = { aiMode = it },
                        modifier = Modifier.padding(horizontal = 4.dp).heightIn(max = 24.dp),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF07C160))
                    )
                    Text("脚本", fontSize = 11.sp, color = if (!aiMode) Color(0xFF07C160) else Color.Gray)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = pageSurface),
            modifier = Modifier.statusBarsPadding()
        )
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        // 气泡列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(bubbles, key = { i, _ -> i }) { _, b ->
                when {
                    b.isNarration -> {
                        Text(
                            b.text, fontSize = 14.sp, color = pageText.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textAlign = TextAlign.Center, lineHeight = 22.sp
                        )
                    }
                    b.isUser -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                Modifier.background(
                                    AchatTheme.colors.primary.copy(alpha = 0.12f),
                                    AchatTheme.shapes.bubbleMe
                                ).padding(14.dp).widthIn(max = 270.dp)
                            ) {
                                Text(b.text, fontSize = 14.sp, color = pageText)
                            }
                        }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Column(Modifier.widthIn(max = 290.dp)) {
                                if (b.label.isNotEmpty()) {
                                    Text(
                                        b.label, fontSize = 11.sp,
                                        color = AchatTheme.colors.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                    )
                                }
                                Box(
                                    Modifier.background(pageSurface, AchatTheme.shapes.bubbleAi).padding(14.dp)
                                ) {
                                    Text(b.text, fontSize = 14.sp, color = pageText)
                                }
                            }
                        }
                    }
                }
            }
            // 加载中指示
            if (waiting) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("● ● ●", fontSize = 11.sp, color = pageText.copy(alpha = 0.3f))
                    }
                }
            }
            // 选项
            if (choices.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        choices.forEach { c ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.7f).clip(AchatTheme.shapes.card),
                                shape = AchatTheme.shapes.card,
                                color = pageSurface,
                                tonalElevation = 2.dp,
                                onClick = {
                                    c.actions.forEach { a -> ScriptEngine.applyAction(a) }
                                    choices = emptyList()
                                    advanceOne()
                                }
                            ) {
                                Text(
                                    c.text, fontSize = 14.sp, color = pageText,
                                    modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            // 剧终
            if (finished) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("— 剧终 —", fontSize = 16.sp, color = pageText.copy(alpha = 0.3f))
                    }
                }
            }
        }

        // 继续按钮
        if (!finished && choices.isEmpty() && !waiting) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { advanceOne() }) {
                    Text("继续 ▼", fontSize = 14.sp)
                }
            }
        }
    }
}

private fun loadScriptEvents(script: LingChatScript, chapter: String): List<ScriptEvent> {
    return ScriptEngine.getChapter(script, chapter)
}
