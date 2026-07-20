package com.aftglw.devapi.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.network.NetworkMonitor
import com.aftglw.devapi.feature.chat.ChatScreen
import com.aftglw.devapi.feature.chat.ChatsScreen
import com.aftglw.devapi.ui.screens.DiscoverScreen
import com.aftglw.devapi.feature.profile.MeScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.aftglw.devapi.feature.group.GroupChatScreen
import com.aftglw.devapi.feature.group.GroupInfoPage
import com.aftglw.devapi.feature.group.GroupChatManager
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.ui.utils.AnimationUtils
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WeChatTheme = lightColorScheme(
    primary = Color(0xFF07C160),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

private val tabTitles = listOf("对话", "发现", "我的")

private sealed class AppPage {
    data object Tabs : AppPage()
    data class Chat(val name: String, val persona: String, val avatarUri: String, val id: String = "", val characterFolder: String = "", val thinkingMessage: String = "") : AppPage()
    data class GroupChat(val group: com.aftglw.devapi.model.GroupChat) : AppPage()
    data class GroupInfo(val group: com.aftglw.devapi.model.GroupChat) : AppPage()
}

@Composable
fun WeChatApp() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        AiServiceFactory.init(ctx)
        NetworkMonitor.init(ctx)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            (ctx as? androidx.activity.ComponentActivity)?.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
    val prefs = remember { ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE) }
    var customFont by remember { mutableStateOf(prefs.getBoolean("custom_font", false)) }

    var currentPage by remember { mutableStateOf<AppPage>(AppPage.Tabs) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var glassTransparent by remember { mutableStateOf(prefs.getBoolean("glass_transparent", true)) }
    var physicsEnabled by remember { mutableStateOf(prefs.getBoolean("physics_enabled", true)) }
    var showTimestamps by remember { mutableStateOf(prefs.getBoolean("show_timestamps", true)) }
    var hideNavBar by remember { mutableStateOf(false) }
    var currentThemeId by remember { mutableStateOf(prefs.getString("current_theme", "default") ?: "default") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                glassTransparent = prefs.getBoolean("glass_transparent", true)
                physicsEnabled = prefs.getBoolean("physics_enabled", true)
                customFont = prefs.getBoolean("custom_font", false)
                showTimestamps = prefs.getBoolean("show_timestamps", true)
                currentThemeId = prefs.getString("current_theme", "default") ?: "default"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = currentPage !is AppPage.Tabs) {
        currentPage = AppPage.Tabs
    }

    val backdrop = rememberLayerBackdrop(onDraw = {
        drawRect(Color.Transparent)
        drawContent()
    })

    val bgPath = prefs.getString("main_bg_uri", "")?.takeIf { it.isNotEmpty() }
    // Task 2.6: 主背景改异步解码 + 下采样，避免主线程解码原图导致卡顿 / OOM
    var bgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bgPath) {
        val path = bgPath ?: return@LaunchedEffect
        val dm = ctx.resources.displayMetrics
        bgBitmap = withContext(Dispatchers.IO) {
            com.aftglw.devapi.core.storage.decodeSampledBitmap(path, dm.widthPixels, dm.heightPixels)?.asImageBitmap()
        }
    }

    val themeColors = when(currentThemeId) {
        "newspaper" -> NewspaperAchatColors
        "washi" -> WashiAchatColors
        else -> DefaultAchatColors
    }
    val themeShapes = when(currentThemeId) {
        "newspaper" -> NewspaperAchatShapes
        "washi" -> WashiAchatShapes
        else -> DefaultAchatShapes
    }
    val themeTypography = when(currentThemeId) {
        "newspaper" -> NewspaperAchatTypography
        "washi" -> WashiAchatTypography
        else -> DefaultAchatTypography
    }

    CompositionLocalProvider(
        LocalAchatColors provides themeColors,
        LocalAchatShapes provides themeShapes,
        LocalAchatTypography provides themeTypography
    ) {
        WeChatAppContent(
            customFont = customFont,
            glassTransparent = glassTransparent,
            physicsEnabled = physicsEnabled,
            hideNavBar = hideNavBar,
            bgBitmap = bgBitmap,
            currentPage = currentPage,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            backdrop = backdrop,
            chatsScreen = {
                ChatsScreen(
                    onChatClick = { name, persona, avatarUri, id, characterFolder, thinkingMessage ->
                        currentPage = AppPage.Chat(name, persona, avatarUri, id, characterFolder, thinkingMessage)
                    },
                    onGroupClick = { group ->
                        currentPage = AppPage.GroupChat(group)
                    }
                )
            },
            discoverScreen = { DiscoverScreen(emptyList(), onSubPageChange = { hideNavBar = it }) },
            meScreen = { MeScreen() },
            chatScreen = { page ->
                ChatScreen(
                    name = page.name,
                    persona = page.persona,
                    avatarUri = page.avatarUri,
                    id = page.id,
                    characterFolder = page.characterFolder,
                    thinkingMessage = page.thinkingMessage,
                    showTimestamps = showTimestamps,
                    onBack = { currentPage = AppPage.Tabs })
            },
            groupChatScreen = { page ->
                GroupChatScreen(
                    group = page.group,
                    onBack = { currentPage = AppPage.Tabs },
                    onOpenInfo = { currentPage = AppPage.GroupInfo(page.group) }
                )
            },
            groupInfoScreen = { page ->
                GroupInfoPage(
                    group = page.group,
                    onBack = {
                        // 返回群聊界面，携带可能被更新的 group
                        currentPage = AppPage.GroupChat(page.group)
                    },
                    onGroupChanged = { updated ->
                        currentPage = AppPage.GroupInfo(updated)
                    },
                    onGroupDeleted = {
                        currentPage = AppPage.Tabs
                    }
                )
            }
        )
    }
}

@Composable
private fun WeChatAppContent(
    customFont: Boolean,
    glassTransparent: Boolean,
    physicsEnabled: Boolean,
    hideNavBar: Boolean,
    bgBitmap: ImageBitmap?,
    currentPage: AppPage,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: LayerBackdrop,
    chatsScreen: @Composable () -> Unit,
    discoverScreen: @Composable () -> Unit,
    meScreen: @Composable () -> Unit,
    chatScreen: @Composable (AppPage.Chat) -> Unit,
    groupChatScreen: @Composable (AppPage.GroupChat) -> Unit,
    groupInfoScreen: @Composable (AppPage.GroupInfo) -> Unit
) {
    val themeTypography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = AchatTheme.typography.title),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = AchatTheme.typography.title),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = AchatTheme.typography.body),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = AchatTheme.typography.body),
        labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = AchatTheme.typography.mono),
    )
    MaterialTheme(colorScheme = if (AchatTheme.colors.isDark) darkColorScheme(primary = AchatTheme.colors.primary, background = AchatTheme.colors.background, surface = AchatTheme.colors.surface) else lightColorScheme(primary = AchatTheme.colors.primary, background = AchatTheme.colors.background, surface = AchatTheme.colors.surface), typography = if (customFont) buildCustomTypography() else themeTypography) {
        Box(Modifier.fillMaxSize()) {
            if (bgBitmap != null) {
                Image(bgBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().then(
                if (!glassTransparent) Modifier.layerBackdrop(backdrop) else Modifier
            )) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        AnimationUtils.slideHorizontal(forward = targetState !is AppPage.Tabs)
                    },
                    label = "page"
                ) { page ->
                    when (page) {
                        is AppPage.Tabs -> {
                            val tabsBgModifier = if (bgBitmap != null) {
                                Modifier.background(AchatTheme.colors.surface.copy(alpha = 0.75f))
                            } else {
                                if (AchatTheme.colors.themeId == "newspaper") {
                                    Modifier.newspaperBackground(AchatTheme.colors.background)
                                } else {
                                    Modifier.background(Brush.verticalGradient(listOf(Color(0xFFE8F5E9), AchatTheme.colors.background)))
                                }
                            }
                            Column(Modifier.fillMaxSize().then(tabsBgModifier)) {
                                Column {
                                    val topBarBgModifier = if (bgBitmap != null) {
                                        Modifier.background(Color.Transparent)
                                    } else {
                                        if (AchatTheme.colors.themeId == "newspaper") {
                                            Modifier.newspaperBackground(AchatTheme.colors.surface.copy(alpha = 0.95f))
                                        } else {
                                            Modifier.background(AchatTheme.colors.surface.copy(alpha = 0.95f))
                                        }
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp)
                                            .then(topBarBgModifier)
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AnimatedContent(
                                            targetState = tabTitles[selectedTab],
                                            transitionSpec = {
                                                AnimationUtils.titleTransition()
                                            },
                                            label = "title"
                                        ) { title ->
                                            Text(
                                                title,
                                                color = AchatTheme.colors.onSurface,
                                                style = MaterialTheme.typography.headlineMedium
                                                    .copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    AnimatedContent(
                                        targetState = selectedTab,
                                        transitionSpec = {
                                            AnimationUtils.slideHorizontal(forward = targetState > initialState)
                                        },
                                        label = "tabs"
                                    ) { targetTab ->
                                        when (targetTab) {
                                            0 -> chatsScreen()
                                            1 -> discoverScreen()
                                            2 -> meScreen()
                                        }
                                    }
                                }
                            }
                        }
                        is AppPage.Chat -> chatScreen(page)
                        is AppPage.GroupChat -> groupChatScreen(page)
                        is AppPage.GroupInfo -> groupInfoScreen(page)
                    }
                }
            }

            AnimatedVisibility(
                visible = currentPage is AppPage.Tabs && !hideNavBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                GlassNavBar(
                    backdrop = backdrop,
                    selectedIndex = selectedTab,
                    onTabSelected = onTabSelected,
                    physicsEnabled = physicsEnabled,
                    selectedColor = AchatTheme.colors.primary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WeChatAppPreview() {
    val backdrop = rememberLayerBackdrop(onDraw = {
        drawRect(Color.Transparent)
        drawContent()
    })

    WeChatAppContent(
        customFont = false,
        glassTransparent = true,
        physicsEnabled = true,
        hideNavBar = false,
        bgBitmap = null,
        currentPage = AppPage.Tabs,
        selectedTab = 0,
        onTabSelected = {},
        backdrop = backdrop,
        chatsScreen = { Text("Chats List Placeholder", Modifier.padding(16.dp)) },
        discoverScreen = { Text("Discover Placeholder", Modifier.padding(16.dp)) },
        meScreen = { Text("Me Placeholder", Modifier.padding(16.dp)) },
        chatScreen = { Text("Chat with ${it.name}", Modifier.padding(16.dp)) },
        groupChatScreen = { Text("Group: ${it.group.name}", Modifier.padding(16.dp)) },
        groupInfoScreen = { Text("Group Info: ${it.group.name}", Modifier.padding(16.dp)) }
    )
}
