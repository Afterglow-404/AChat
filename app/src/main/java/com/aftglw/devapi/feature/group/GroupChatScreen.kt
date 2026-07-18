package com.aftglw.devapi.feature.group

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMessage
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群聊聊天页面。
 *
 * 用户发送消息后，群成员按轮次顺序依次回复（一圈一人）。
 * 每个成员使用各自的角色人设作为 system prompt。
 */
@Composable
fun GroupChatScreen(
    group: GroupChat,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 消息历史
    var messages by remember { mutableStateOf(GroupChatManager.loadMessages(ctx, group.id)) }

    // 轮次索引：当前该哪个成员发言（0 = 第一个成员）
    val roundRobinIndex = remember { mutableStateOf(0) }

    // 输入状态
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    /** 非空时表示某个成员正在打字（链式接话过程中显示） */
    var typingMember by remember { mutableStateOf<String?>(null) }

    // 加载成员头像
    val memberAvatars = remember(group.members) {
        group.members.associateWith { name ->
            val uri = GroupChatManager.getMemberAvatarUri(ctx, name)
            if (uri.isNotEmpty()) {
                try { BitmapFactory.decodeFile(uri)?.asImageBitmap() } catch (_: Exception) { null }
            } else null
        }
    }

    // 成员人设缓存
    val memberPersonas = remember(group.members) {
        group.members.associateWith { GroupChatManager.getMemberPersona(ctx, it) }
    }

    // 发送消息
    fun sendMessage(text: String) {
        if (text.isBlank() || sending) return
        sending = true

        val userMsg = GroupChatMessage(text = text, from = "user", time = GroupChatManager.now(), isMe = true)
        messages = messages + userMsg
        GroupChatManager.saveMessages(ctx, group.id, messages)
        inputText = ""

        scope.launch {
            // 首轮：按轮次选成员回复
            val startIdx = roundRobinIndex.value % group.members.size
            val firstSpeaker = group.members[startIdx]
            val replied = mutableSetOf<String>()
            var lastSpeaker = firstSpeaker
            var lastReply: String? = null

            fun history(): List<ChatMessage> = messages.map { m ->
                val displayName = if (m.isMe) "你" else m.from
                ChatMessage(
                    role = if (m.isMe) "user" else "assistant",
                    content = if (m.isMe) m.text else "[$displayName]: ${m.text}"
                )
            }

            fun memberSystemPrompt(name: String): String {
                val p = memberPersonas[name] ?: ""
                return buildString {
                    append(p)
                    if (p.isNotBlank()) appendLine()
                    appendLine()
                    appendLine("你在一个群聊中，群名：${group.name}")
                    appendLine("群成员：${group.members.joinToString("、")}")
                    appendLine("你的角色名是：$name")
                    appendLine("你可以引用和回应其他成员的话，像真人一样在群聊中和大家互动。")
                    appendLine("请以 $name 的身份回复，不要说'作为AI'之类的话。")
                    appendLine("用括号描述你的动作和表情，例如【高兴】【叹气】。")
                }
            }

            suspend fun callMember(name: String, userInput: String): String? {
                typingMember = name
                try {
                    return withContext(Dispatchers.IO) {
                        AiServiceFactory.getService().sendMessage(
                            history = history(),
                            userMessage = userInput,
                            systemPrompt = memberSystemPrompt(name)
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("GroupChat", "Member $name reply failed", e)
                    return null
                } finally {
                    typingMember = null
                }
            }

            // 第一步：首位成员回复
            val r1 = callMember(firstSpeaker, text)
            if (r1 != null) {
                val msg1 = GroupChatMessage(r1, firstSpeaker, GroupChatManager.now())
                messages = messages + msg1
                GroupChatManager.saveMessages(ctx, group.id, messages)
                replied.add(firstSpeaker)
                lastReply = r1

                // 继续判断其他人是否需要接话（最多 5 轮自动推进）
                var autoRounds = 0
                val MAX_AUTO = 5
                while (autoRounds < MAX_AUTO) {
                    typingMember = "判断中…"
                    val next: String? = withContext(Dispatchers.IO) {
                        NextSpeakerJudge.decide(
                            memberNames = group.members,
                            repliedNames = replied,
                            lastSpeaker = lastSpeaker,
                            lastMessage = lastReply ?: "",
                            conversation = messages.takeLast(6).joinToString("\n") {
                                val label = if (it.isMe) "你" else it.from
                                "$label: ${it.text}"
                            }
                        )
                    }
                    if (next == null || next == "none" || next !in group.members) break
                    autoRounds++

                    // 构建适合该成员的 userMessage（基于最近对话）
                    val contextPrompt = "${group.members.joinToString("、")}正在群聊中交谈。话题是最近的消息。轮到 $next 发言了。请自然接话。"

                    val rN = callMember(next, contextPrompt)
                    if (rN != null) {
                        val msgN = GroupChatMessage(rN, next, GroupChatManager.now())
                        messages = messages + msgN
                        GroupChatManager.saveMessages(ctx, group.id, messages)
                        replied.add(next)
                        lastSpeaker = next
                        lastReply = rN
                    } else break
                }
            }

            // 更新群聊摘要
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null) {
                val summary = if (lastMsg.isMe) lastMsg.text.take(30)
                    else "${lastMsg.from}: ${lastMsg.text.take(30)}"
                GroupChatManager.saveGroup(ctx, group.copy(lastMessage = summary, time = lastMsg.time))
            }
            // 轮次指针推进
            roundRobinIndex.value = (roundRobinIndex.value + 1) % group.members.size
            sending = false
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
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${group.members.size} 人",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // 消息列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            state = listState
        ) {
            items(messages, key = { "${it.from}_${it.time}_${it.text.hashCode()}" }) { msg ->
                GroupChatBubble(
                    message = msg,
                    memberAvatar = memberAvatars[msg.from],
                    allMembers = group.members
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AchatTheme.colors.background)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text("说点什么...", color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), fontSize = 14.sp)
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
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { sendMessage(inputText.trim()) },
                    enabled = inputText.isNotBlank() && !sending,
                    modifier = Modifier.size(40.dp).background(
                        if (inputText.isNotBlank() && !sending) AchatTheme.colors.primary
                        else AchatTheme.colors.onSurface.copy(alpha = 0.1f),
                        CircleShape
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "发送",
                        tint = if (inputText.isNotBlank() && !sending) Color.White
                        else AchatTheme.colors.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupChatBubble(
    message: GroupChatMessage,
    memberAvatar: androidx.compose.ui.graphics.ImageBitmap?,
    allMembers: List<String>
) {
    val isMe = message.isMe
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bubbleColor = if (isMe) AchatTheme.colors.primary.copy(alpha = 0.15f)
        else AchatTheme.colors.surface
    val textColor = AchatTheme.colors.onSurface

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
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        message.text,
                        fontSize = 14.sp,
                        color = textColor,
                        lineHeight = 20.sp
                    )
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
}
