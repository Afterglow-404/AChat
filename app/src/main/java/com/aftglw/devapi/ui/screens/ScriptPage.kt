package com.aftglw.devapi.ui.screens

import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ScriptChoice
import com.aftglw.devapi.ScriptEngine
import com.aftglw.devapi.ScriptEvent
import com.aftglw.devapi.ScriptPlayer
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DisplayBubble(
    val text: String,
    val label: String = "",  // 角色名
    val isNarration: Boolean = false,
    val isUser: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPage(scriptEvents: List<ScriptEvent>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentIndex by remember { mutableIntStateOf(0) }
    val bubbles = remember { mutableStateListOf<DisplayBubble>() }
    var waiting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<ScriptChoice>>(emptyList()) }
    var aiMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun isUserChar(char: String) = char == "你" || char == "用户" || char == "我"

    fun nextEvent() {
        if (currentIndex >= scriptEvents.size) { finished = true; return }
        val event = scriptEvents[currentIndex]
        currentIndex++

        when (event.type) {
            "narration" -> {
                bubbles.add(DisplayBubble(text = event.text, isNarration = true))
            }
            "dialogue" -> {
                val isUser = isUserChar(event.character)
                if (aiMode && !isUser) {
                    // AI 扮演角色回复
                    waiting = true
                    scope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            try {
                                AiServiceFactory.getService().sendMessage(
                                    emptyList(), "",
                                    "你是${event.character}。${event.text}"
                                )
                            } catch (_: Exception) { null }
                        }
                        bubbles.add(DisplayBubble(
                            text = event.text,
                            label = event.character,
                            isUser = false
                        ))
                        waiting = false
                        nextEvent()
                    }
                } else {
                    bubbles.add(DisplayBubble(
                        text = event.text,
                        label = if (isUser) "" else event.character,
                        isUser = isUser
                    ))
                    // 纯脚本模式不自动推进，等用户点"继续"
                    if (aiMode && isUser) {
                        // 用户发言 → AI 回复
                        waiting = true
                        scope.launch {
                            val reply = withContext(Dispatchers.IO) {
                                try {
                                    AiServiceFactory.getService().sendMessage(
                                        emptyList(), event.text,
                                        "你是角色的朋友，自然地回应。"
                                    )
                                } catch (_: Exception) { null }
                            }
                            if (reply != null) {
                                bubbles.add(DisplayBubble(text = reply, label = "AI", isUser = false))
                            }
                            waiting = false
                            nextEvent()
                        }
                    } else {
                        nextEvent()
                    }
                }
            }
            "choices" -> {
                choices = event.options
            }
            "chapter_end" -> {
                finished = true
            }
            else -> nextEvent()
        }
    }

    LaunchedEffect(Unit) { nextEvent() }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        // 顶栏
        CenterAlignedTopAppBar(
            title = { Text("剧本播放", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Text("AI", fontSize = 11.sp, color = if (aiMode) Color(0xFF07C160) else Color.Gray)
                    Switch(
                        checked = aiMode, onCheckedChange = { aiMode = it },
                        modifier = Modifier.padding(horizontal = 4.dp).heightIn(max = 24.dp)
                    )
                    Text("脚本", fontSize = 11.sp, color = if (!aiMode) Color(0xFF07C160) else Color.Gray)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding()
        )
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE0E0E0))

        // 气泡列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(bubbles, key = { i, _ -> i }) { _, b ->
                if (b.isNarration) {
                    // 旁白：居中斜体
                    Text(b.text, fontSize = 14.sp, color = Color(0xFF888888),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        lineHeight = 22.sp)
                } else if (b.isUser) {
                    // 用户消息：右对齐
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(Modifier.background(Color(0xFF07C160).copy(alpha = 0.15f), RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp)).padding(12.dp).widthIn(max = 240.dp)) {
                            Text(b.text, fontSize = 14.sp, color = Color(0xFF1A1A1A))
                        }
                    }
                } else {
                    // AI/角色消息：左对齐
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Column(Modifier.widthIn(max = 260.dp)) {
                            if (b.label.isNotEmpty()) Text(b.label, fontSize = 11.sp, color = Color(0xFF07C160), fontWeight = FontWeight.Bold)
                            Box(Modifier.background(Color.White, RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)).padding(12.dp)) {
                                Text(b.text, fontSize = 14.sp, color = Color(0xFF1A1A1A))
                            }
                        }
                    }
                }
            }
            if (waiting) {
                item { Text("● ● ●", fontSize = 12.sp, color = Color(0xFFBBBBBB), modifier = Modifier.padding(8.dp)) }
            }
            if (choices.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { c ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable {
                                c.actions.forEach { a -> ScriptEngine.applyAction(a) }
                                choices = emptyList()
                                nextEvent()
                            }.padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(c.text, fontSize = 14.sp, color = Color(0xFF1A1A1A))
                            }
                        }
                    }
                }
            }
            if (finished) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("— 剧终 —", fontSize = 16.sp, color = Color(0xFFBBBBBB))
                    }
                }
            }
        }

        // 底部控制
        if (!finished && choices.isEmpty() && !waiting) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { nextEvent() }) {
                    Text("继续", fontSize = 14.sp)
                }
            }
        }
    }
}
