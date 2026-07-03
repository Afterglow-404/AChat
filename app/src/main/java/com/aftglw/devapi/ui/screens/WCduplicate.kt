package com.aftglw.devapi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aftglw.devapi.model.ChatItem
import com.aftglw.devapi.ui.utils.AnimationUtils
import com.aftglw.devapi.ui.utils.StaggeredEntrance
import com.aftglw.devapi.viewmodel.ChatsViewModel
import androidx.compose.animation.AnimatedContent

val WeChatBgColor = Color(0xFFF7F7F7)
val WeChatBottomBarColor = Color(0xFFF7F7F7)
val WeChatGreen = Color(0xFF07C160)
val TextPrimary = Color(0xFF191919)
val TextSecondary = Color(0xFF999999)
val DividerColor = Color(0xFFEEEEEE)
val BadgeRed = Color(0xFFFA5151)
val HeaderGrey = Color(0xFFEDEDED)

enum class WeChatTab(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    Chats("微信", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    Contacts("通讯录", Icons.Filled.Contacts, Icons.Outlined.Contacts),
    Discover("发现", Icons.Filled.Explore, Icons.Outlined.Explore),
    Me("我", Icons.Filled.Person, Icons.Outlined.Person)
}

data class ChatItemData(
    val id: Int,
    val title: String,
    val message: String,
    val time: String,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val avatarColor: Color = Color(0xFFE0E0E0)
)

@Composable
fun WeChatScreenWithData(
    vm: ChatsViewModel = viewModel(),
    onItemClick: (ChatItem) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val chats by vm.chats.observeAsState(emptyList())
    WeChatScreenContent(chats, onItemClick, onBack)
}

@Composable
fun WeChatScreenContent(
    chats: List<ChatItem>,
    onItemClick: (ChatItem) -> Unit = {},
    onBack: () -> Unit = {}
) {
    var currentTab by remember { mutableStateOf(WeChatTab.Chats) }

    val chatListData = chats.map { chat ->
        ChatItemData(
            id = chat.id.hashCode(),
            title = chat.name,
            message = chat.lastMessage,
            time = chat.time,
            unreadCount = chat.unreadCount,
            isMuted = false,
            avatarColor = Color(chat.avatarColor)
        )
    }

    Scaffold(
        topBar = {
            WeChatTopBar(
                title = when(currentTab) {
                    WeChatTab.Chats -> if (chatListData.sumOf { it.unreadCount } > 0) "微信(${chatListData.sumOf { it.unreadCount }})" else "微信"
                    else -> currentTab.label
                },
                onBack = onBack
            )
        },
        bottomBar = {
            WeChatBottomBar(
                selectedTab = currentTab,
                onTabSelect = { currentTab = it }
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(WeChatBgColor)) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    AnimationUtils.slideHorizontal(forward = targetState.ordinal > initialState.ordinal)
                },
                label = "wechat_tabs"
            ) { targetTab ->
                when (targetTab) {
                    WeChatTab.Chats -> ChatListScreen(chatListData) { data ->
                        val original = chats.find { it.id.hashCode() == data.id }
                        original?.let { onItemClick(it) }
                    }
                    WeChatTab.Contacts -> ContactsScreen()
                    WeChatTab.Discover -> DiscoverScreen()
                    WeChatTab.Me -> MeScreenMock()
                }
            }
        }
    }
}


@Composable
fun ChatListScreen(chatList: List<ChatItemData>, onItemClick: (ChatItemData) -> Unit) {
    if (chatList.isEmpty()) {
        EmptyStateView()
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items = chatList, key = { _, it -> it.id }) { index, chatItem ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                StaggeredEntrance(index = index, visible = visible) {
                    Column {
                        ChatListItem(item = chatItem, onClick = { onItemClick(chatItem) })
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsScreen() {
    val menuItems = listOf(
        Triple("新的朋友", Icons.Default.PersonAdd, Color(0xFFFA9D3B)),
        Triple("仅聊天的朋友", Icons.Default.PersonSearch, Color(0xFFFA9D3B)),
        Triple("群聊", Icons.Default.Group, Color(0xFF07C160)),
        Triple("标签", Icons.Default.Label, Color(0xFF2782D7)),
        Triple("公众号", Icons.Default.ChatBubble, Color(0xFF2782D7))
    )
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(menuItems) { index, (text, icon, color) ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            StaggeredEntrance(index = index, visible = visible) {
                Column {
                    ContactMenuItem(text, icon, color)
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
        item { SectionHeader("A") }
        item {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            StaggeredEntrance(index = 5, visible = visible) {
                ContactItem("ab123", Color(0xFFE8512B))
            }
        }
        item { SectionHeader("C") }
        item {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            StaggeredEntrance(index = 6, visible = visible) {
                ContactItem("程序员", Color(0xFF1A7DC0))
            }
        }
        item { SectionHeader("F") }
        item {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            StaggeredEntrance(index = 7, visible = visible) {
                ContactItem("文件传输助手", Color(0xFF07C160))
            }
        }
    }
}

@Composable
fun DiscoverScreen() {
    val items = listOf(
        Triple("朋友圈", Icons.Default.Camera, Color(0xFFFA5151)),
        Triple("视频号", Icons.Default.VideoCameraFront, Color(0xFFFA9D3B)),
        Triple("直播", Icons.Default.LiveTv, Color(0xFFFA5151)),
        Triple("扫一扫", Icons.Default.QrCodeScanner, Color(0xFF2782D7)),
        Triple("听一听", Icons.Default.MusicNote, Color(0xFF2782D7)),
        Triple("看一看", Icons.Default.RemoveRedEye, Color(0xFFFA9D3B)),
        Triple("搜一搜", Icons.Default.Search, Color(0xFFFA5151))
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(8.dp)) }
        itemsIndexed(items) { index, (text, icon, color) ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            StaggeredEntrance(index = index, visible = visible) {
                Column {
                    if (index == 1 || index == 3 || index == 5) {
                        Spacer(Modifier.height(8.dp))
                    }
                    DiscoverMenuItem(text, icon, color)
                }
            }
        }
    }
}

@Composable
fun MeScreenMock() {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(24.dp, 40.dp, 24.dp, 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("User Name", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("个人签名: Hello AChat", fontSize = 14.sp, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row {
                    Surface(shape = RoundedCornerShape(12.dp), color = WeChatBgColor) {
                        Text("+ 状态", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp)
                    }
                }
            }
            Icon(Icons.Default.QrCode, null, Modifier.size(18.dp), tint = TextSecondary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFFCCCCCC))
        }
        Spacer(Modifier.height(8.dp))
        MeMenuItem("服务", Icons.Default.Payment, Color(0xFF07C160))
        Spacer(Modifier.height(8.dp))
        MeMenuItem("收藏", Icons.Default.FavoriteBorder, Color(0xFFFA9D3B))
        MeMenuItem("朋友圈", Icons.Default.PhotoLibrary, Color(0xFF2782D7))
        MeMenuItem("卡包", Icons.Default.Wallet, Color(0xFF2782D7))
        MeMenuItem("表情", Icons.Default.EmojiEmotions, Color(0xFFFA9D3B))
        Spacer(Modifier.height(8.dp))
        MeMenuItem("设置", Icons.Default.Settings, Color(0xFF2782D7))
    }
}


@Composable
fun WeChatTopBar(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(HeaderGrey).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
        }
        Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Default.Search, null, Modifier.size(24.dp), tint = TextPrimary)
            Spacer(Modifier.width(16.dp))
            Icon(Icons.Default.Add, null, Modifier.size(26.dp), tint = TextPrimary)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListItem(item: ChatItemData, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BadgedBox(
            badge = {
                if (item.unreadCount > 0) {
                    Badge(containerColor = BadgeRed, modifier = Modifier.offset(x = (-4).dp, y = 4.dp)) {
                        Text(item.unreadCount.toString(), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(item.avatarColor))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 16.sp, color = TextPrimary, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(item.message, fontSize = 14.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(item.time, fontSize = 11.sp, color = Color(0xFFB0B0B0), modifier = Modifier.align(Alignment.Top))
    }
}

@Composable
fun ContactMenuItem(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 16.sp, color = TextPrimary)
    }
}

@Composable
fun ContactItem(name: String, avatarColor: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(avatarColor))
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 16.sp, color = TextPrimary)
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().background(WeChatBgColor).padding(horizontal = 16.dp, vertical = 4.dp),
        fontSize = 12.sp, color = TextSecondary
    )
}

@Composable
fun DiscoverMenuItem(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(16.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), fontSize = 16.sp, color = TextPrimary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFFCCCCCC))
    }
}

@Composable
fun MeMenuItem(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(16.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), fontSize = 16.sp, color = TextPrimary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFFCCCCCC))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeChatBottomBar(selectedTab: WeChatTab, onTabSelect: (WeChatTab) -> Unit) {
    NavigationBar(containerColor = WeChatBottomBarColor, tonalElevation = 0.dp, modifier = Modifier.height(56.dp)) {
        WeChatTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelect(tab) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (tab == WeChatTab.Chats) {
                                Badge(containerColor = BadgeRed) { Text("1", color = Color.White) }
                            } else if (tab == WeChatTab.Discover) {
                                Badge(containerColor = BadgeRed, modifier = Modifier.size(8.dp))
                            }
                        }
                    ) {
                        Icon(if (isSelected) tab.selectedIcon else tab.unselectedIcon, null, Modifier.size(24.dp))
                    }
                },
                label = { Text(tab.label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WeChatGreen,
                    selectedTextColor = WeChatGreen,
                    unselectedIconColor = TextPrimary,
                    unselectedTextColor = TextPrimary,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun EmptyStateView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无消息", color = TextSecondary)
    }
}

@Preview(showBackground = true)
@Composable
fun WeChatFullPreview() {
    MaterialTheme {
        val mockChats = listOf(
            ChatItem("1", "微信团队", "欢迎来到微信", "10:00", 1, 0xFF07C160.toInt()),
            ChatItem("2", "文件传输助手", "[文件] 资料.pdf", "昨天", 0, 0xFF9E9E9E.toInt())
        )
        WeChatScreenContent(chats = mockChats)
    }
}

@Preview(showBackground = true)
@Composable
fun WCMeScreenPreview() {
    MaterialTheme {
        MeScreenMock()
    }
}
