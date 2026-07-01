package com.aftglw.devapi.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.aftglw.devapi.AffinityManager
import com.aftglw.devapi.ChatHistory
import com.aftglw.devapi.MoodDetector
import com.aftglw.devapi.MoodInfo
import com.aftglw.devapi.MoodModel
import com.aftglw.devapi.MemoryStore
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiServiceFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private fun now() = timeFormat.format(java.util.Date())

data class Bubble(val text: String, val isMe: Boolean, val time: String = now(), val mood: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(name: String, persona: String = "", avatarUri: String = "", showTimestamps: Boolean = true, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bubbles = remember { mutableStateListOf<Bubble>() }
    val history = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(false) }
    val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val model = remember { prefs.getString("ai_model", "deepsleep-cat") ?: "deepsleep-cat" }

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
        // 自动归档：未归档时用最近 20 条生成日记
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val saved = ChatHistory.load(ctx, name)
            if (saved.size >= 5) {
                val total = saved.size
                val chunkKey = "chunk:$name:$total"
                val existing = MemoryStore.search(ctx, chunkKey, 1, "diary:$name")
                if (existing.isEmpty()) {
                    val msgs = saved.takeLast(20)
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    val text = msgs.joinToString("\n") { "${if (it.second) "我" else name}：${it.first}" }
                    try {
                        val summary = com.aftglw.devapi.network.AiServiceFactory.getService()
                            .sendMessage(emptyList(), text, "概括这段对话的核心内容和情绪，像日记一样写两句话。")
                        if (!summary.isNullOrBlank()) MemoryStore.save(ctx, "$dateStr $summary", "diary:$name")
                    } catch (_: Exception) {} /* 非关键，API 失败不影响聊天 */
                }
            }
        }
        ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE).edit()
            .putString("last_active_chat", name).apply()
        // 模型丢 IO 线程，不阻塞 UI
        launch(Dispatchers.IO) {
            if (ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean("mood_enabled", false)) {
                MoodModel.load(ctx)
            }
        }
        val saved = ChatHistory.load(ctx, name)
        bubbles.clear()
        bubbles.addAll(saved.map { Bubble(it.first, it.second, it.third) })
        history.addAll(saved.map { ChatMessage(if (it.second) "user" else "assistant", it.first) })
    }
    // 每次气泡变化时滚动到底部（替代 scrollToBottom）
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.size - 1)
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
        ChatHistory.save(ctx, name, bubbles.map { Triple(it.text, it.isMe, it.time) })
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
                        facts.lines().filter { it.isNotBlank() }.forEach { MemoryStore.save(ctx, it.trim(), "chat:$name") }
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

    
    var memoryContext by remember { mutableStateOf("") }
    LaunchedEffect(bubbles.size / 5) {
        withContext(Dispatchers.IO) {
            val last = if (bubbles.isNotEmpty()) bubbles.last().text else ""
            // 情绪驱动检索
            val moodQuery = when (com.aftglw.devapi.MoodDetector.lastMood) {
                "悲伤" -> "$last 需要安慰关心"
                "害怕" -> "$last 安全感安抚"
                "愤怒" -> "$last 冷静积极解决问题"
                "开心" -> "$last 有趣好笑共同回忆"
                "厌恶" -> null // 厌恶时不检索记忆
                else -> last
            }
            if (moodQuery != null) MemoryStore.search(ctx, moodQuery, 1).joinToString("\n") { "- ${it.text}" }
            else ""
        }.also { memoryContext = it }
    }

    
    val optimized = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("persona_optimized_$name", "") ?: ""
    val optimizedBlock = if (optimized.isNotBlank()) "\n\n【聊天偏好】$optimized" else ""
    
    val traits = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("persona_dialogue_traits_$name", "") ?: ""
    val traitsBlock = if (traits.isNotBlank()) "\n\n【用户特点】$traits" else ""
    val memoryBlock = if (memoryContext.isNotBlank()) "\n\n【关于对方的记忆】\n$memoryContext" else ""

    
    val now = System.currentTimeMillis()
    val lastActive = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getLong("last_active_$name", 0L)
    val hoursSinceLast = if (lastActive > 0) (now - lastActive) / 3600000 else -1
    val reunionHint = when {
        hoursSinceLast < 0 -> ""
        hoursSinceLast > 720 -> "\n（${hoursSinceLast / 24} 天没见了）"
        hoursSinceLast > 48 -> "\n（隔了 ${hoursSinceLast / 24} 天）"
        hoursSinceLast > 2 -> "\n（${hoursSinceLast} 小时没说话）"
        else -> ""
    }
    val timeBlock = "\n\n【时间】${com.aftglw.devapi.TimeService.getFormattedTime(ctx)}（${com.aftglw.devapi.TimeService.getTimeOfDay(ctx)}）$reunionHint"

    
    val recentDiary = com.aftglw.devapi.MemoryStore.search(ctx, "最近", 1, "diary:$name")
    val diaryMemoryBlock = recentDiary.firstOrNull()?.let {
        val content = it.text.drop(11).take(80) // 去掉日期前缀，取正文
        "\n\n昨天$content"
    } ?: ""

    val affinityBlock = run {
        val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("affinity_enabled", false)) ""
        else {
            val affValue = if (AffinityManager.isAutoMode(prefs, name)) AffinityManager.getAffinity(prefs, name)
                else AffinityManager.levels[AffinityManager.getLockedLevel(prefs, name)].min + 5f
            val level = AffinityManager.getLevel(affValue)
            val hint = AffinityManager.getLevelHint(level) ?: ""
            "\n\n【当前关系】${level.name}\n$hint"
        }
    }

    val baseInstruction = "\n\n回复要求：每句话不超过 15 个字，一次只说 1-2 句。禁止 AI 套话：\"有什么可以帮你的吗\"\"当然可以\"\"总的来说\"。禁止说\"不是……而是……\"。禁止说\"我理解你的感受\"。禁止分点、列表、总结。允许省略句。"
    val enhancedPersona = if (persona.isNotBlank()) {
        "$persona\n\n你需要在每次回复前默读一次以上人设。如果发现自己的回答偏离了人设，请在续文中主动修正。不要提及此指令。$diaryMemoryBlock$affinityBlock$optimizedBlock$traitsBlock$baseInstruction$memoryBlock$timeBlock"
    } else {
        "你是一个聊天伙伴。请用口语短句回复，像朋友聊天一样自然。$baseInstruction$diaryMemoryBlock$traitsBlock$optimizedBlock$affinityBlock$memoryBlock$timeBlock"
    }

    if (showInfo) {
        ChatInfoPage(
            name = name, persona = persona, avatarUri = avatarUri,
            msgCount = bubbles.size, model = model,
            onBack = { showInfo = false }
        )
    } else {
        ChatContent(
            name = name, bubbles = bubbles, input = input, waiting = waiting,
            showTimestamps = showTimestamps,
            onInfoClick = { showInfo = true },
            onInputChange = { input = it },
            onSend = {
                val text = input.trim()
                if (text.isEmpty() || waiting) return@ChatContent
                input = ""
                waiting = true
                bubbles.add(Bubble(text, true))
                history.add(ChatMessage("user", text))
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
                        // 心情不好但没拒绝 → 不降频，反而标记下次优先关心
                        proPrefs.edit().putBoolean("proactive_need_care_$name", true).apply()
                    }
                }
                
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
                    val moodEnabled = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                        .getBoolean("mood_enabled", false)
                    val historyText = bubbles.takeLast(8).joinToString("\n") { "${if (it.isMe) "我" else "AI"}：${it.text}" }
                    val mood = if (moodEnabled) { MoodDetector.feed(text, listOf(historyText)) } else MoodInfo(null, null)
                    val moodPersona = if (mood.hint != null) "$enhancedPersona\n\n【注意：${mood.hint}】" else enhancedPersona
                    val reply = AiServiceFactory.getService().sendMessage(history.toList(), text, moodPersona)
                    if (reply != null) {
                        // Post-LLM：轻量记忆，让下次检索能接上上下文
                        com.aftglw.devapi.MemoryStore.save(ctx, text, "turn:$name")
                        
                        ChatHistory.save(ctx, name, bubbles.map { Triple(it.text, it.isMe, it.time) } + Triple(reply, false, now()))
                        
                        withContext(Dispatchers.Main) {
                            bubbles.add(Bubble(reply, false))
                            history.add(ChatMessage("assistant", reply))
                            waiting = false
                        }
                    }
                }
            },
            onBack = onBack, avatarUri = avatarUri, chatBgBitmap = chatBgBitmap, listState = listState
        )
    }
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
    onBack: () -> Unit,
    avatarUri: String = "",
    chatBgBitmap: ImageBitmap? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
        if (chatBgBitmap != null) {
            Image(chatBgBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(
            Modifier.fillMaxSize().imePadding().background(
                if (chatBgBitmap != null) Color.White.copy(alpha = 0.75f)
                else Color(0xFFF5F5F5)
            )
        ) {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (avatarUri.isNotEmpty()) {
                            val topBmp = remember(avatarUri) {
                                try { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() }
                                catch (_: Exception) { null }
                            }
                            if (topBmp != null) {
                                Image(topBmp, null, Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Text(
                            if (waiting && bubbles.isNotEmpty()) "对方正在输入..." else name,
                            color = Color(0xFF1A1A1A),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onBack, Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Color(0xFF1A1A1A))
                    }
                },
                actions = {
                    IconButton(onClick = onInfoClick) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "详情", tint = Color(0xFF888888))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding()
            )
            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE0E0E0))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(bubbles, key = { i, _ -> i }) { _, b ->
                    var menuExpanded by remember { mutableStateOf(false) }
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }
                    val animProgress by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                        label = "bubble_entry"
                    )

                    Row(
                        Modifier.fillMaxWidth().graphicsLayer {
                            alpha = animProgress
                            translationY = (1f - animProgress) * 30f
                            scaleX = 0.95f + (animProgress * 0.05f)
                            scaleY = 0.95f + (animProgress * 0.05f)
                        },
                        horizontalArrangement = if (b.isMe) Arrangement.End else Arrangement.Start
                    ) {
                        if (!b.isMe) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF07C160)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarUri.isNotEmpty()) {
                                    val bmp = remember(avatarUri) {
                                        try { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() }
                                        catch (_: Exception) { null }
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
                        Box {
                            Box(
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { menuExpanded = true }
                                ).background(
                                    if (b.isMe) Color(0xFF8CE09C) else Color.White,
                                    RoundedCornerShape(if (b.isMe) 20.dp else 8.dp, 8.dp, 20.dp, 20.dp)
                                ).padding(12.dp).widthIn(max = 240.dp)
                            ) {
                                Column {
                                    Text(b.text, fontSize = 15.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 情绪可视化
                                        val moodVis = prefs.getBoolean("mood_visualization", false) && prefs.getBoolean("mood_enabled", false)
                                        if (b.isMe && b == bubbles.lastOrNull() && moodVis) {
                                            val moodEmoji = when (com.aftglw.devapi.MoodDetector.lastMood) {
                                                "开心" -> "😊"; "悲伤" -> "😢"; "愤怒" -> "😠"
                                                "害怕" -> "😨"; "惊讶" -> "😮"; "厌恶" -> "🤢"
                                                "中性" -> "😐"; else -> null
                                            }
                                            if (moodEmoji != null) {
                                                Text(moodEmoji, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                                            }
                                        }
                                        if (showTimestamps && b.time.isNotEmpty()) {
                                            Text(b.time, fontSize = 10.sp, color = Color(0xFF888888))
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
                                        clipboard.setText(AnnotatedString(b.text))
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("收藏") },
                                    onClick = {
                                        com.aftglw.devapi.MemoryStore.save(ctx, b.text, "starred:$name")
                                        android.widget.Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show()
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                        if (b.isMe) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF07C160)),
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

            Row(
                Modifier.fillMaxWidth().background(Color.White)
                    .navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    input, onInputChange, Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                    placeholder = { Text("说些什么吧...") },
                    shape = RoundedCornerShape(22.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color(0xFFE8E8E8).copy(alpha = 0.8f),
                        unfocusedContainerColor = Color(0xFFE8E8E8).copy(alpha = 0.5f)
                    ),
                    enabled = !waiting
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSend,
                    Modifier.background(Color(0xFF07C160), RoundedCornerShape(18.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Text(if (waiting) "..." else "发送")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInfoPage(
    name: String, persona: String, avatarUri: String,
    msgCount: Int, model: String, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
    val apiUrl = prefs.getString("ai_api_url", "")?.takeIf { it.isNotEmpty() } ?: "未配置"
    val hasKey = prefs.getString("ai_api_key", "")?.isNotEmpty() == true

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        CenterAlignedTopAppBar(
            title = { Text("对话详情", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Color(0xFF1A1A1A)) } },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding())
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE0E0E0))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                    if (avatarUri.isNotEmpty()) {
                        val bmp = remember(avatarUri) {
                            try { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() }
                            catch (_: Exception) { null }
                        }
                        if (bmp != null) Image(bmp, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        else Text(name.take(1), fontSize = 28.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    } else Text(name.take(1), fontSize = 28.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }

            InfoRow("对话名称", name)
            if (persona.isNotEmpty()) InfoRow("角色人设", persona)
            InfoRow("AI 模型", model)
            InfoRow("API 地址", apiUrl)
            InfoRow("API 密钥", if (hasKey) "已配置 🔒" else "未配置")
            InfoRow("通信协议", com.aftglw.devapi.network.AiServiceFactory.getProtocolName())
            InfoRow("消息总数", "$msgCount 条")



            // 主动关怀设置
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text("主动关怀", Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                var proactiveEnabled by remember { mutableStateOf(prefs.getBoolean("proactive_enabled_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主动关怀", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = proactiveEnabled, onCheckedChange = { v ->
                        proactiveEnabled = v; prefs.edit().putBoolean("proactive_enabled_$name", v).apply()
                    })
                }
                if (proactiveEnabled) {
                    Spacer(Modifier.height(4.dp))
                    var dailyLimit by remember { mutableIntStateOf(prefs.getInt("proactive_daily_limit_$name", 3)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每日上限", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
                        androidx.compose.material3.Slider(value = dailyLimit.toFloat(), onValueChange = { v ->
                            dailyLimit = v.toInt(); prefs.edit().putInt("proactive_daily_limit_$name", v.toInt()).apply()
                        }, valueRange = 1f..20f, steps = 3, modifier = Modifier.width(120.dp))
                        Text("${dailyLimit}条", fontSize = 13.sp)
                    }
                    var idleHours by remember { mutableIntStateOf(prefs.getInt("proactive_idle_hours_$name", 8)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("空闲触发", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
                        androidx.compose.material3.Slider(value = idleHours.toFloat(), onValueChange = { v ->
                            idleHours = v.toInt(); prefs.edit().putInt("proactive_idle_hours_$name", v.toInt()).apply()
                        }, valueRange = 0f..1f, steps = 0, modifier = Modifier.width(120.dp))
                        Text(if (idleHours == 0) "立即" else "${idleHours}h", fontSize = 13.sp)
                    }
                    var checkMode by remember { mutableStateOf(prefs.getString("proactive_check_mode_$name", "random") ?: "random") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("检查模式", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
                        androidx.compose.material3.RadioButton(selected = checkMode == "fixed", onClick = { checkMode = "fixed"; prefs.edit().putString("proactive_check_mode_$name", "fixed").apply() })
                        Text("固定", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.RadioButton(selected = checkMode == "random", onClick = { checkMode = "random"; prefs.edit().putString("proactive_check_mode_$name", "random").apply() })
                        Text("随机", fontSize = 13.sp)
                    }
                    if (checkMode == "fixed") {
                        var interval by remember { mutableIntStateOf(prefs.getInt("proactive_interval_min_$name", 30)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("间隔", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
                            androidx.compose.material3.Slider(value = interval.toFloat(), onValueChange = { v ->
                                interval = v.toInt(); prefs.edit().putInt("proactive_interval_min_$name", v.toInt()).apply()
                            }, valueRange = 1f..120f, steps = 7, modifier = Modifier.width(120.dp))
                            Text("${interval}min", fontSize = 13.sp)
                        }
                    }
                    // 触发条件
                    val triggerLabels = listOf("空闲太久","深夜在线","情绪回访","记忆触发","早安问候","连续冷落")
                    val triggerKeys = listOf("1","2","3","4","5","6")
                    val currentTriggers = (prefs.getString("proactive_triggers_$name", "1,2,3,4,5,6") ?: "1,2,3,4,5,6")
                        .split(",").toMutableSet()
                    Spacer(Modifier.height(4.dp))
                    Text("触发条件", fontSize = 13.sp, color = Color.Gray)
                    triggerKeys.forEachIndexed { i, key ->
                        val checked = key in currentTriggers
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { v ->
                                if (v) currentTriggers.add(key) else currentTriggers.remove(key)
                                prefs.edit().putString("proactive_triggers_$name", currentTriggers.joinToString(",")).apply()
                            }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF07C160)))
                            Text(triggerLabels[i], fontSize = 13.sp)
                        }
                    }
                }
            }

            // 导出聊天记录
            Spacer(Modifier.height(8.dp))
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                uri?.let {
                    try {
                        val history = ChatHistory.load(ctx, name)
                        val arr = org.json.JSONArray()
                        for ((text, isMe, time) in history) {
                            arr.put(org.json.JSONObject().apply { put("text", text); put("isMe", isMe); put("time", time) })
                        }
                        ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(arr.toString(2).toByteArray()) }
                        Toast.makeText(ctx, "导出成功", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show() }
                }
            }
            Button(
                onClick = { exportLauncher.launch("${name}_chat.json") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("导出聊天记录", fontSize = 14.sp) }

            // 记忆管理 + 对话优化
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text("优化", Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                var autoArchive by remember { mutableStateOf(prefs.getBoolean("auto_archive_$name", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动归档", Modifier.weight(1f), fontSize = 14.sp)
                    Text("超过 60 条时压缩旧对话", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = autoArchive, onCheckedChange = { v -> autoArchive = v; prefs.edit().putBoolean("auto_archive_$name", v).apply() })
                }
                var dialogueOpt by remember { mutableStateOf(prefs.getBoolean("dialogue_optimization_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对话优化", Modifier.weight(1f), fontSize = 14.sp)
                    Text("分析用户特点优化回复", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = dialogueOpt, onCheckedChange = { v -> dialogueOpt = v; prefs.edit().putBoolean("dialogue_optimization_$name", v).apply() })
                }
            }

            // 情绪可视化
            if (prefs.getBoolean("mood_enabled", false)) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text("情绪", Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    var moodVis by remember { mutableStateOf(prefs.getBoolean("mood_visualization", false)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("情绪可视化", Modifier.weight(1f), fontSize = 14.sp)
                        Text("在消息旁显示情绪表情", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(end = 8.dp))
                        Switch(checked = moodVis, onCheckedChange = { v -> moodVis = v; prefs.edit().putBoolean("mood_visualization", v).apply() })
                    }
                }
            }

            // 日记与收藏
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text("记忆", Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                val diaries = com.aftglw.devapi.MemoryStore.search(ctx, "日记", 10, "diary:$name")
                val starred = com.aftglw.devapi.MemoryStore.search(ctx, "收藏", 10, "starred:$name")
                Text("📖 日记 (${diaries.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF888888))
                if (diaries.isEmpty()) {
                    Text("暂无日记", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            val msgs = ChatHistory.load(ctx, name).takeLast(30)
                            if (msgs.isEmpty()) return@launch
                            val text = msgs.joinToString("\n") { "${if (it.second) "我" else name}：${it.first}" }
                            try {
                                val summary = com.aftglw.devapi.network.AiServiceFactory.getService()
                                    .sendMessage(emptyList(), text, "用两句话概括最近的聊天内容，像日记一样自然。")
                                if (!summary.isNullOrBlank()) {
                                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                                    MemoryStore.save(ctx, "$today $summary", "diary:$name")
                                }
                            } catch (_: Exception) {} /* 非关键 */
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(36.dp)) {
                        Text("生成今日日记", fontSize = 13.sp)
                    }
                } else diaries.forEach { d ->
                    var expanded by remember { mutableStateOf(false) }
                    val dateLabel = d.text.take(10)
                    val content = d.text.drop(11)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
                        Text(dateLabel, fontSize = 11.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(72.dp))
                        Text(if (expanded) content else content.take(40) + if (content.length > 40) "..." else "", fontSize = 12.sp, color = Color(0xFF555555), maxLines = if (expanded) 10 else 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (diaries.isNotEmpty()) {
                    TextButton(onClick = { /* 未来做日历视图 */ }, modifier = Modifier.fillMaxWidth()) {
                        Text("查看更多", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("⭐ 收藏 (${starred.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF888888))
                if (starred.isEmpty()) Text("暂无收藏", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                else starred.forEach { Text("• ${it.text.take(60)}", fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.padding(vertical = 2.dp)) }
            }

            // 好感度设置
            if (prefs.getBoolean("affinity_enabled", false)) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text("好感度", Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    var mode by remember { mutableStateOf(prefs.getString("affinity_mode_$name", "auto") ?: "auto") }
                    var lockLv by remember { mutableIntStateOf(prefs.getInt("affinity_lock_$name", 0)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = mode == "auto", onClick = { prefs.edit().putString("affinity_mode_$name", "auto").apply(); mode = "auto" })
                        Text("自动成长", Modifier.weight(1f), fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = mode == "lock", onClick = { prefs.edit().putString("affinity_mode_$name", "lock").apply(); mode = "lock" })
                        Text("手动锁定", Modifier.weight(1f), fontSize = 14.sp)
                    }
                    if (mode == "lock") {
                        Spacer(Modifier.height(4.dp))
                        Slider(value = lockLv.toFloat(), onValueChange = { v ->
                            lockLv = v.toInt(); prefs.edit().putInt("affinity_lock_$name", v.toInt()).apply()
                        }, valueRange = 0f..4f, steps = 3, colors = SliderDefaults.colors(thumbColor = Color(0xFF07C160), activeTrackColor = Color(0xFF07C160)))
                        Row(Modifier.fillMaxWidth()) {
                            AffinityManager.levels.forEachIndexed { i, l ->
                                Text(l.name, fontSize = 11.sp, color = if (i == lockLv) Color(0xFF07C160) else Color.Gray, modifier = Modifier.weight(1f), textAlign = if (i == 0) TextAlign.Start else if (i == AffinityManager.levels.size - 1) TextAlign.End else TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(0.3f))
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 15.sp, color = Color(0xFF1A1A1A), modifier = Modifier.weight(0.7f))
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
