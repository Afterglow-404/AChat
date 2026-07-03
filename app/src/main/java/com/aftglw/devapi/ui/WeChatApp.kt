package com.aftglw.devapi.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import android.graphics.BitmapFactory
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
import com.aftglw.devapi.ui.screens.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.aftglw.devapi.ui.utils.AnimationUtils

private val WeChatTheme = lightColorScheme(
    primary = Color(0xFF07C160),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

private val tabTitles = listOf("对话", "发现(TBD)", "我的")

private sealed class AppPage {
    data object Tabs : AppPage()
    data class Chat(val name: String, val persona: String, val avatarUri: String, val id: String = "") : AppPage()
}

@Composable
fun WeChatApp() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        AiServiceFactory.init(ctx)
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                glassTransparent = prefs.getBoolean("glass_transparent", true)
                physicsEnabled = prefs.getBoolean("physics_enabled", true)
                customFont = prefs.getBoolean("custom_font", false)
                showTimestamps = prefs.getBoolean("show_timestamps", true)
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
    val bgBitmap = remember(bgPath) {
        bgPath?.let {
            try { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            catch (_: Exception) { null }
        }
    }

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
            ChatsScreen(onChatClick = { name, persona, avatarUri, id ->
                currentPage = AppPage.Chat(name, persona, avatarUri, id)
            })
        },
        discoverScreen = { DiscoverScreen(onSubPageChange = { hideNavBar = it }) },
        meScreen = { MeScreen() },
        chatScreen = { page ->
            ChatScreen(
                name = page.name,
                persona = page.persona,
                avatarUri = page.avatarUri,
                id = page.id,
                showTimestamps = showTimestamps,
                onBack = { currentPage = AppPage.Tabs })
        }
    )
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
    chatScreen: @Composable (AppPage.Chat) -> Unit
) {
    MaterialTheme(colorScheme = WeChatTheme, typography = if (customFont) buildCustomTypography() else Typography()) {
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
                            Column(
                                Modifier.fillMaxSize().then(
                                    if (bgBitmap != null) Modifier.background(Color.White.copy(alpha = 0.75f))
                                    else Modifier.background(Brush.verticalGradient(listOf(Color(0xFFE8F5E9), Color(0xFFF5F5F5))))
                                )
                            ) {
                                Column {
                                    Box(
                                        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp)
                                            .background(if (bgBitmap != null) Color.Transparent else Color.White.copy(alpha = 0.95f))
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
                                                color = Color(0xFF1A1A1A),
                                                style = MaterialTheme.typography.headlineMedium
                                                    .copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE0E0E0))
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
                        is AppPage.Chat -> {
                            chatScreen(page)
                        }
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
                    physicsEnabled = physicsEnabled
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
        chatScreen = { Text("Chat with ${it.name}", Modifier.padding(16.dp)) }
    )
}
