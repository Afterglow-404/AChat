package com.aftglw.devapi.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.graphics.ImageBitmap
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
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.feature.group.GroupChatManager
import com.aftglw.devapi.viewmodel.ChatsViewModel
import androidx.compose.ui.tooling.preview.Preview
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.utils.StaggeredEntrance
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onChatClick: (name: String, persona: String, avatarUri: String, id: String, characterFolder: String, thinkingMessage: String) -> Unit = { _, _, _, _, _, _ -> },
    onGroupClick: (GroupChat) -> Unit = {},
    vm: ChatsViewModel = viewModel<ChatsViewModel>()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { vm.refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refresh() }

    val ctx = LocalContext.current
    val chats: List<ChatItem> by vm.chats.observeAsState(emptyList())
    var groups by remember { mutableStateOf<List<GroupChat>>(emptyList()) }
    LaunchedEffect(Unit) { groups = GroupChatManager.loadGroups(ctx) }
    // 每当界面恢复时重新加载群聊列表
    LaunchedEffect(chats) { groups = GroupChatManager.loadGroups(ctx) }

    ChatsScreenContent(
        chats = chats,
        groups = groups,
        onSearchQueryChange = { scope.launch { vm.setSearchQuery(it) } },
        onChatClick = onChatClick,
        onGroupClick = onGroupClick,
        onTogglePin = { scope.launch { vm.togglePin(it) } },
        onDeleteChat = { scope.launch { vm.deleteChat(it) } },
        onGroupCreated = { scope.launch { groups = GroupChatManager.loadGroups(ctx) } },
        onDeleteGroup = { id -> scope.launch {
            GroupChatManager.deleteGroup(ctx, id)
            groups = GroupChatManager.loadGroups(ctx)
        } },
        ctx = ctx
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreenContent(
    chats: List<ChatItem>,
    onSearchQueryChange: (String) -> Unit,
    onChatClick: (name: String, persona: String, avatarUri: String, id: String, characterFolder: String, thinkingMessage: String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    groups: List<GroupChat> = emptyList(),
    onGroupClick: (GroupChat) -> Unit = {},
    onGroupCreated: () -> Unit = {},
    onDeleteGroup: (String) -> Unit = {},
    ctx: android.content.Context? = null
) {
    var q by remember { mutableStateOf("") }
    var showCreateGroup by remember { mutableStateOf(false) }
    val bgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    Column(Modifier.fillMaxSize().then(bgModifier)) {
        // 顶部搜索栏 + 右上角"创建群聊"按钮（避免与底部 glassnavbar 重合）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(q, { q = it; onSearchQueryChange(it) }, Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                placeholder = { Text("搜索", fontSize = 14.sp, fontFamily = AchatTheme.typography.title) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = AchatTheme.colors.surface, unfocusedContainerColor = AchatTheme.colors.surface))
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showCreateGroup = true },
                modifier = Modifier.size(48.dp).clip(CircleShape).background(AchatTheme.colors.primary)
            ) {
                Icon(Icons.Filled.Add, "创建群聊", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp)) {
            // 群聊条目
            itemsIndexed(groups, key = { _, g -> "group_${g.id}" }) { index, g ->
                var menuExpanded by remember { mutableStateOf(false) }

                StaggeredEntrance(index = index, visible = true) {
                    Box(
                        Modifier.padding(vertical = 0.5.dp)
                            .background(AchatTheme.colors.surface, AchatTheme.shapes.card)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(72.dp)
                                .combinedClickable(
                                    onClick = { onGroupClick(g) },
                                    onLongClick = { menuExpanded = true }
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 群聊头像（首字 + group icon）
                            Box(Modifier.size(48.dp).clip(CircleShape).background(AchatTheme.colors.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.People, null, Modifier.size(24.dp), tint = AchatTheme.colors.primary)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.name, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                                        modifier = Modifier.weight(1f), color = AchatTheme.colors.onSurface,
                                        fontFamily = AchatTheme.typography.title)
                                    Text(g.time, fontSize = 11.sp, color = Color.Gray, fontFamily = AchatTheme.typography.mono)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.lastMessage, fontSize = 13.sp, color = Color.Gray,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f), fontFamily = AchatTheme.typography.body)
                                    Text("${g.members.size}人", fontSize = 11.sp,
                                        color = AchatTheme.colors.primary.copy(alpha = 0.6f))
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            offset = DpOffset(64.dp, 0.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除群聊", color = Color(0xFFFA5151)) },
                                onClick = { onDeleteGroup(g.id); menuExpanded = false }
                            )
                        }
                    }
                }
            }

            // 单聊条目
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
                                    onClick = { onChatClick(c.name, c.persona, c.avatarUri, c.id, c.characterFolder, c.thinkingMessage) },
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

    // 创建群聊弹窗
    if (showCreateGroup && ctx != null) {
        CreateGroupDialog(
            ctx = ctx,
            onDismiss = { showCreateGroup = false },
            onCreated = { showCreateGroup = false; onGroupCreated() }
        )
    }
}

@Composable
private fun ChatAvatar(avatarUri: String, avatarColor: Int, name: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(avatarColor)), contentAlignment = Alignment.Center) {
        // Task 2.5: 头像异步加载（BuiltInCharacterLoader.loadAvatarBitmap 已改 suspend，
        // 内置 LruCache 命中时直接返回）
        var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(avatarUri) {
            bmp = if (avatarUri.isNotEmpty()) {
                com.aftglw.devapi.core.character.BuiltInCharacterLoader.loadAvatarBitmap(ctx, avatarUri)?.asImageBitmap()
            } else null
        }
        if (bmp != null) {
            Image(bmp!!, null, Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

/**
 * 创建群聊弹窗 — 选择成员 + 命名群聊。
 */
@Composable
private fun CreateGroupDialog(
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { members = GroupChatManager.getAvailableMembers(ctx) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var groupName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建群聊", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("群聊名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("选择成员（至少 2 人）", fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                if (members.isEmpty()) {
                    Text("暂无可用角色，请先在设置中添加对话。", fontSize = 13.sp, color = Color.Gray)
                } else {
                    members.forEach { (name, persona) ->
                        val isSel = name in selected
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (isSel) selected - name else selected + name
                            }.padding(vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isSel, onCheckedChange = {
                                    selected = if (it) selected + name else selected - name
                                })
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (persona.isNotBlank()) {
                                Text(
                                    persona.take(40) + if (persona.length > 40) "…" else "",
                                    fontSize = 11.sp,
                                    color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 44.dp)
                                )
                            }
                        }
                    }
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 12.sp, color = Color(0xFFFA5151))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    groupName.isBlank() -> error = "请输入群聊名称"
                    selected.size < 2 -> error = "请至少选择 2 个成员"
                    else -> {
                        scope.launch {
                            GroupChatManager.saveGroup(ctx, GroupChat(
                                id = "group_${System.currentTimeMillis()}",
                                name = groupName.trim(),
                                members = selected.toList(),
                                time = "",
                                lastMessage = ""
                            ))
                            onCreated()
                        }
                    }
                }
            }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
            onChatClick = { _, _, _, _, _, _ -> },
            onTogglePin = {},
            onDeleteChat = {}
        )
    }
}
