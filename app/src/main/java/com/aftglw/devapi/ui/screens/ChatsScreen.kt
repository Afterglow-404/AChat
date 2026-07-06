package com.aftglw.devapi.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aftglw.devapi.model.ChatItem
import com.aftglw.devapi.viewmodel.ChatsViewModel
import androidx.compose.ui.tooling.preview.Preview
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.utils.StaggeredEntrance
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(onChatClick: (name: String, persona: String, avatarUri: String, id: String) -> Unit = { _, _, _, _ -> }, vm: ChatsViewModel = viewModel<ChatsViewModel>()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refresh() }

    val chats: List<ChatItem> by vm.chats.observeAsState(emptyList())
    ChatsScreenContent(
        chats = chats,
        onSearchQueryChange = { vm.setSearchQuery(it) },
        onChatClick = onChatClick,
        onTogglePin = { vm.togglePin(it) },
        onDeleteChat = { vm.deleteChat(it) }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreenContent(
    chats: List<ChatItem>,
    onSearchQueryChange: (String) -> Unit,
    onChatClick: (name: String, persona: String, avatarUri: String, id: String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val bgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    Column(Modifier.fillMaxSize().then(bgModifier)) {
        OutlinedTextField(q, { q = it; onSearchQueryChange(it) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).defaultMinSize(minHeight = 48.dp),
            placeholder = { Text("搜索", fontSize = 14.sp, fontFamily = AchatTheme.typography.title) }, 
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = AchatTheme.colors.surface, unfocusedContainerColor = AchatTheme.colors.surface))
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 90.dp)) {
            itemsIndexed(chats, key = { _, c -> c.id }) { index, c ->
                var menuExpanded by remember { mutableStateOf(false) }

                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }

                StaggeredEntrance(index = index, visible = visible) {
                    val itemBgColor = if (c.pinned && AchatTheme.colors.themeId == "newspaper") {
                        AchatTheme.colors.divider.copy(alpha = 0.2f)
                    } else if (c.pinned && AchatTheme.colors.themeId == "washi") {
                        AchatTheme.colors.divider.copy(alpha = 0.3f)
                    } else AchatTheme.colors.surface

                    Box(
                        Modifier.padding(vertical = if (AchatTheme.colors.themeId == "washi") 2.dp else 0.5.dp)
                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(bottom = true) else Modifier)
                            .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, index) else Modifier)
                            .then(if (AchatTheme.colors.themeId == "washi" && c.unreadCount > 0) Modifier.hankoStamp("信", seed = index) else Modifier)
                            .background(itemBgColor, AchatTheme.shapes.card)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(72.dp)
                                .combinedClickable(
                                    onClick = { onChatClick(c.name, c.persona, c.avatarUri, c.id) },
                                    onLongClick = { menuExpanded = true }
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChatAvatar(avatarUri = c.avatarUri, avatarColor = c.avatarColor, name = c.name)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (c.pinned) {
                                        Icon(Icons.Filled.PushPin, "置顶", Modifier.size(14.dp), tint = AchatTheme.colors.primary)
                                        Spacer(Modifier.width(2.dp))
                                    }
                                    Text(c.name, fontWeight = FontWeight.Medium, fontSize = 16.sp, modifier = Modifier.weight(1f), color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
                                    Text(c.time, fontSize = 11.sp, color = Color.Gray, fontFamily = AchatTheme.typography.mono)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.lastMessage, fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontFamily = AchatTheme.typography.body)
                                    if (c.unreadCount > 0) Box(Modifier.size(20.dp, 18.dp).clip(CircleShape).background(Color(0xFFFA5151)), contentAlignment = Alignment.Center) { Text(if (c.unreadCount > 99) "99+" else c.unreadCount.toString(), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center) }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            offset = DpOffset(64.dp, 0.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (c.pinned) "取消置顶" else "置顶") },
                                onClick = { onTogglePin(c.id); menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("删除对话", color = Color(0xFFFA5151)) },
                                onClick = { onDeleteChat(c.id); menuExpanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAvatar(avatarUri: String, avatarColor: Int, name: String) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(avatarColor)), contentAlignment = Alignment.Center) {
        if (avatarUri.isNotEmpty()) {
            val bmp = remember(avatarUri) {
                try {
                    BitmapFactory.decodeFile(avatarUri)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
            if (bmp != null) {
                Image(bmp, null, Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        } else {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatsScreenPreview() {
    val sampleChats = listOf(
        ChatItem("1", "张三", "下午去打球吗？", "14:20", 0, 0xFF07C160.toInt()),
        ChatItem("2", "李四", "代码写完没？", "13:45", 2, 0xFF1976D2.toInt(), pinned = true),
        ChatItem("3", "微信团队", "欢迎使用微信", "昨天", 0, 0xFF757575.toInt())
    )
    MaterialTheme {
        ChatsScreenContent(
            chats = sampleChats,
            onSearchQueryChange = {},
            onChatClick = { _, _, _, _ -> },
            onTogglePin = {},
            onDeleteChat = {}
        )
    }
}
