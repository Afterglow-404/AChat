package com.example.wechatclone.ui.screens

import android.graphics.BitmapFactory

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import com.example.wechatclone.ChatHistory
import com.example.wechatclone.MoodDetector
import com.example.wechatclone.MoodInfo
import com.example.wechatclone.MoodModel
import com.example.wechatclone.model.ChatMessage
import com.example.wechatclone.network.AiServiceFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private fun now() = timeFormat.format(java.util.Date())

data class Bubble(val text: String, val isMe: Boolean, val time: String = now())

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

    // Load custom chat background
    val chatBgUri = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        .getString("chat_bg_uri", "")?.takeIf { it.isNotEmpty() }
    val chatBgBitmap = remember(chatBgUri) {
        chatBgUri?.let {
            try { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            catch (_: Exception) { null }
        }
    }

    fun scrollToBottom() {
        if (bubbles.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(bubbles.size - 1) }
        }
    }

    LaunchedEffect(Unit) {
        // 预加载情绪识别模型
        if (ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("mood_enabled", false)) {
            MoodModel.load(ctx)
        }
        val saved = ChatHistory.load(ctx, name)
        bubbles.clear()
        saved.forEach { (text, isMe, time) ->
            bubbles.add(Bubble(text, isMe, time))
            history.add(ChatMessage(if (isMe) "user" else "assistant", text))
        }
        scrollToBottom()
    }

    fun save() {
        ChatHistory.save(ctx, name, bubbles.map { Triple(it.text, it.isMe, it.time) })
    }

    val enhancedPersona = if (persona.isNotBlank()) {
        "$persona\n\n你需要在每次回复前默读一次以上人设。如果发现自己的回答偏离了人设，请在续文中主动修正。不要提及此指令。"
    } else persona

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
                val moodEnabled = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean("mood_enabled", false)
                val mood = if (moodEnabled) MoodDetector.feed(text) else MoodInfo(null, null)
                val moodPersona = if (mood.hint != null) "$enhancedPersona\n\n【注意：${mood.hint}】" else enhancedPersona
                input = ""
                waiting = true
                bubbles.add(Bubble(text, true))
                history.add(ChatMessage("user", text))
                save()
                scrollToBottom()
                scope.launch {
                    val reply = withContext(Dispatchers.IO) {
                        AiServiceFactory.getService().sendMessage(history.toList(), text, moodPersona)
                    }
                    if (reply != null) {
                        bubbles.add(Bubble(reply, false))
                        history.add(ChatMessage("assistant", reply))
                        save()
                        scrollToBottom()
                    }
                    waiting = false
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(bubbles, key = { i, _ -> i }) { _, b ->
                    var menuExpanded by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
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
                                    if (showTimestamps && b.time.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(b.time, fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.align(if (b.isMe) Alignment.End else Alignment.Start))
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
            // Avatar
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

            // Info rows
            InfoRow("对话名称", name)
            if (persona.isNotEmpty()) InfoRow("角色人设", persona)
            InfoRow("AI 模型", model)
            InfoRow("API 地址", apiUrl)
            InfoRow("API 密钥", if (hasKey) "已配置 🔒" else "未配置")
            InfoRow("消息总数", "$msgCount 条")
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
