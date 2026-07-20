package com.aftglw.devapi.feature.settings
import com.aftglw.devapi.DebugOverlayService
import com.aftglw.devapi.feature.tools.ToolMarketPage

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aftglw.devapi.ui.buildCustomTypography
import com.aftglw.devapi.ui.utils.AnimationUtils
import com.aftglw.devapi.ui.utils.StaggeredEntrance
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private sealed class SettingsPage {
    data object Main : SettingsPage()
    data object Profile : SettingsPage()
    data object Notifications : SettingsPage()
    data object AiApi : SettingsPage()
    data object ManageRoles : SettingsPage()
    data object Backgrounds : SettingsPage()
    data object Appearance : SettingsPage()
    data object Debug : SettingsPage()
    data object About : SettingsPage()
    data object McpServers : SettingsPage()
    data object ToolMarket : SettingsPage()
    data object ToolSecurity : SettingsPage()
    data object DataManagement : SettingsPage()
    data object MoodModel : SettingsPage()
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsRoot(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun SettingsRoot(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE) }
    val initialCustom = prefs.getBoolean("custom_font", false)
    val typography = remember { mutableStateOf(if (initialCustom) buildCustomTypography() else Typography()) }
    var currentPage by remember { mutableStateOf<SettingsPage>(SettingsPage.Main) }
    // 拦截系统返回键：在子页面时调 goBack，主页时正常退出
    if (currentPage !is SettingsPage.Main) {
        androidx.activity.compose.BackHandler { currentPage = SettingsPage.Main }
    }

    var sound by remember { mutableStateOf(prefs.getBoolean("notification_sound", true)) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("notification_vibrate", true)) }
    var debug by remember { mutableStateOf(prefs.getBoolean("debug_overlay", false)) }

    var apiUrl by remember { mutableStateOf(prefs.getString("ai_api_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key")) }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "deepsleep-cat") ?: "deepsleep-cat") }
    var mockReplies by remember { mutableStateOf(prefs.getString("mock_replies", "") ?: "") }
    var mockDelay by remember { mutableStateOf(prefs.getString("mock_delay_ms", "800") ?: "800") }

    var aiTemperature by remember { mutableStateOf(prefs.getString("ai_temperature", "") ?: "") }
    var aiTopP by remember { mutableStateOf(prefs.getString("ai_top_p", "") ?: "") }
    var aiMaxTokens by remember { mutableStateOf(prefs.getString("ai_max_tokens", "") ?: "") }
    var aiSeed by remember { mutableStateOf(prefs.getInt("ai_seed", -1).let { if (it < 0) "" else it.toString() }) }
    var aiFrequencyPenalty by remember { mutableStateOf(prefs.getString("ai_frequency_penalty", "") ?: "") }
    var aiPresencePenalty by remember { mutableStateOf(prefs.getString("ai_presence_penalty", "") ?: "") }
    var aiStopSequences by remember { mutableStateOf(prefs.getString("ai_stop_sequences", "") ?: "") }
    var aiResponseFormat by remember { mutableStateOf(prefs.getString("ai_response_format", "") ?: "") }
    var aiClaudeThinking by remember { mutableStateOf(prefs.getBoolean("ai_claude_thinking", false)) }
    var aiDeepSeekThinking by remember { mutableStateOf(prefs.getBoolean("ai_deepseek_thinking", false)) }
    var aiProtocol by remember { mutableStateOf(prefs.getString("ai_protocol", "auto") ?: "auto") }
    var newChatName by remember { mutableStateOf("") }
    var newChatPersona by remember { mutableStateOf("") }
    var newChatAvatarUri by remember { mutableStateOf("") }

    var glassTransparent by remember { mutableStateOf(prefs.getBoolean("glass_transparent", true)) }
    var physicsEnabled by remember { mutableStateOf(prefs.getBoolean("physics_enabled", true)) }
    var customFont by remember { mutableStateOf(initialCustom) }
    var longContextMode by remember { mutableStateOf(prefs.getBoolean("long_context_mode", true)) }
    var currentThemeId by remember { mutableStateOf(prefs.getString("current_theme", "default") ?: "default") }
    
    var showTimestamps by remember { mutableStateOf(prefs.getBoolean("show_timestamps", true)) }
    var hitokotoType by remember { mutableStateOf(prefs.getString("hitokoto_type", "") ?: "") }
    var moodEnabled by remember { mutableStateOf(prefs.getBoolean("mood_enabled", false)) }
    var affinityEnabled by remember { mutableStateOf(prefs.getBoolean("affinity_enabled", false)) }
    var openBookMode by remember { mutableStateOf(prefs.getBoolean("open_book_mode", false)) }
    LaunchedEffect(customFont) { typography.value = if (customFont) buildCustomTypography() else Typography() }

    var profileName by remember { mutableStateOf(prefs.getString("profile_name", "User") ?: "User") }
    var profileWechatId by remember { mutableStateOf(prefs.getString("profile_wechat_id", "个人签名: Hello Wisp") ?: "个人签名: Hello Wisp") }
    var profileAvatarUri by remember { mutableStateOf(prefs.getString("profile_avatar_uri", "") ?: "") }

    var mainBgUri by remember { mutableStateOf(prefs.getString("main_bg_uri", "") ?: "") }
    var chatBgUri by remember { mutableStateOf(prefs.getString("chat_bg_uri", "") ?: "") }

    val profileAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePickedImage(ctx, uri, "profile_avatar.jpg") { file ->
            profileAvatarUri = file.absolutePath
            prefs.edit().putString("profile_avatar_uri", file.absolutePath).apply()
        }
    }
    val roleAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePickedImage(ctx, uri, "avatar_${System.currentTimeMillis()}.jpg") { file ->
            newChatAvatarUri = file.absolutePath
        }
    }
    val mainBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePickedImage(ctx, uri, "bg_main.jpg") { file ->
            mainBgUri = file.absolutePath
            prefs.edit().putString("main_bg_uri", file.absolutePath).apply()
        }
    }
    val chatBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePickedImage(ctx, uri, "bg_chat.jpg") { file ->
            chatBgUri = file.absolutePath
            prefs.edit().putString("chat_bg_uri", file.absolutePath).apply()
        }
    }

    val goBack: () -> Unit = { currentPage = SettingsPage.Main }
    val nav: (SettingsPage) -> Unit = { currentPage = it }

    val themeColors = if (currentThemeId == "newspaper") NewspaperAchatColors else DefaultAchatColors
    val themeShapes = if (currentThemeId == "newspaper") NewspaperAchatShapes else DefaultAchatShapes

    CompositionLocalProvider(
        LocalAchatColors provides themeColors,
        LocalAchatShapes provides themeShapes
    ) {
        MaterialTheme(typography = typography.value, colorScheme = if (themeColors.isDark) darkColorScheme(primary = themeColors.primary, background = themeColors.background, surface = themeColors.surface) else lightColorScheme(primary = themeColors.primary, background = themeColors.background, surface = themeColors.surface)) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    AnimationUtils.slideHorizontal(forward = targetState !is SettingsPage.Main)
                },
                label = "settings"
            ) { page ->
                when (page) {
                    is SettingsPage.Main -> SettingsMainPage(onBack = onBack, onNav = nav)
                    is SettingsPage.Profile -> ProfilePage(
                        onBack = goBack,
                        profileName, { profileName = it; prefs.edit().putString("profile_name", it).apply() },
                        profileWechatId, { profileWechatId = it; prefs.edit().putString("profile_wechat_id", it).apply() },
                        profileAvatarUri,
                        { profileAvatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { profileAvatarUri = ""; prefs.edit().remove("profile_avatar_uri").apply() }
                    )
                    is SettingsPage.Notifications -> NotificationsPage(
                        onBack = goBack,
                        sound, { sound = it; prefs.edit().putBoolean("notification_sound", it).apply() },
                        vibrate, { vibrate = it; prefs.edit().putBoolean("notification_vibrate", it).apply() }
                    )
                    is SettingsPage.AiApi -> AiApiPage(
                        onBack = goBack,
                        apiUrl, { apiUrl = it; prefs.edit().putString("ai_api_url", it).apply() },
                        apiKey, { apiKey = it; com.aftglw.devapi.core.security.SecureKeyStore.putString(ctx, "ai_api_key", it) },
                        model, { model = it; prefs.edit().putString("ai_model", it).apply() },
                        mockReplies, { mockReplies = it; prefs.edit().putString("mock_replies", it).apply() },
                        mockDelay, { mockDelay = it; prefs.edit().putString("mock_delay_ms", it).apply() },
                        longContextMode, { longContextMode = it; prefs.edit().putBoolean("long_context_mode", it).apply() },
                        aiTemperature, { aiTemperature = it; prefs.edit().putString("ai_temperature", it).apply() },
                        aiTopP, { aiTopP = it; prefs.edit().putString("ai_top_p", it).apply() },
                        aiMaxTokens, { aiMaxTokens = it; prefs.edit().putString("ai_max_tokens", it).apply() },
                        aiSeed, { aiSeed = it; prefs.edit().putInt("ai_seed", it.toIntOrNull() ?: -1).apply() },
                        aiFrequencyPenalty, { aiFrequencyPenalty = it; prefs.edit().putString("ai_frequency_penalty", it).apply() },
                        aiPresencePenalty, { aiPresencePenalty = it; prefs.edit().putString("ai_presence_penalty", it).apply() },
                        aiStopSequences, { aiStopSequences = it; prefs.edit().putString("ai_stop_sequences", it).apply() },
                        aiResponseFormat, { aiResponseFormat = it; prefs.edit().putString("ai_response_format", it).apply() },
                        aiClaudeThinking, { aiClaudeThinking = it; prefs.edit().putBoolean("ai_claude_thinking", it).apply() },
                        aiDeepSeekThinking, { aiDeepSeekThinking = it; prefs.edit().putBoolean("ai_deepseek_thinking", it).apply() },
                        aiProtocol, { aiProtocol = it; prefs.edit().putString("ai_protocol", it).apply() }
                    )
                    is SettingsPage.ManageRoles -> ManageRolesPage(
                        onBack = goBack,
                        newChatName, { newChatName = it },
                        newChatPersona, { newChatPersona = it },
                        newChatAvatarUri,
                        onPickAvatar = { roleAvatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onClearAvatar = { newChatAvatarUri = "" },
                        onCreateChat = {
                            val name = newChatName.trim()
                            if (name.isNotBlank()) {
                                val persona = newChatPersona.trim()
                                val avatar = newChatAvatarUri
                                newChatName = ""; newChatPersona = ""; newChatAvatarUri = ""
                                Toast.makeText(ctx, "已添加喵~", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    try { addChat(ctx, name, persona, avatar) }
                                    catch (e: Exception) {
                                        Log.e("SettingsActivity", "addChat failed", e)
                                        Toast.makeText(ctx, "创建失败: ${e.message?.take(60)}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    )
                    is SettingsPage.Backgrounds -> BackgroundsPage(
                        onBack = goBack,
                        mainBgUri, { mainBgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { mainBgUri = ""; prefs.edit().remove("main_bg_uri").apply(); Toast.makeText(ctx, "已重置", Toast.LENGTH_SHORT).show() },
                        chatBgUri, { chatBgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { chatBgUri = ""; prefs.edit().remove("chat_bg_uri").apply(); Toast.makeText(ctx, "已重置", Toast.LENGTH_SHORT).show() }
                    )
                    is SettingsPage.Appearance -> AppearancePage(
                        onBack = goBack,
                        glassTransparent, { glassTransparent = it; prefs.edit().putBoolean("glass_transparent", it).apply() },
                        physicsEnabled, { physicsEnabled = it; prefs.edit().putBoolean("physics_enabled", it).apply() },
                        customFont, { customFont = it; prefs.edit().putBoolean("custom_font", it).apply() },
                        showTimestamps, { showTimestamps = it; prefs.edit().putBoolean("show_timestamps", it).apply() },
                        hitokotoType, { hitokotoType = it; prefs.edit().putString("hitokoto_type", it).apply() },
                        currentThemeId, { currentThemeId = it; prefs.edit().putString("current_theme", it).apply() }
                    )
                    is SettingsPage.Debug -> DebugPage(
                        onBack = goBack,
                        debug, { v ->
                            debug = v; prefs.edit().putBoolean("debug_overlay", v).apply()
                            if (v) {
                                if (Settings.canDrawOverlays(ctx)) ctx.startService(Intent(ctx, DebugOverlayService::class.java))
                                else {
                                    Toast.makeText(ctx, "请先授予悬浮窗权限喵~", Toast.LENGTH_LONG).show()
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                                }
                            } else ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                        },
                        moodEnabled, { moodEnabled = it; prefs.edit().putBoolean("mood_enabled", it).apply() },
                        affinityEnabled, { affinityEnabled = it; prefs.edit().putBoolean("affinity_enabled", it).apply() },
                        openBookMode, { openBookMode = it; prefs.edit().putBoolean("open_book_mode", it).apply() }
                    )
                    is SettingsPage.About -> AboutPage(onBack = goBack)
                    is SettingsPage.McpServers -> McpServersPage(onBack = goBack)
                    is SettingsPage.ToolMarket -> ToolMarketPage(onBack = goBack)
                    is SettingsPage.ToolSecurity -> ToolSecurityPage(onBack = goBack)
                    is SettingsPage.DataManagement -> DataManagementPage(onBack = goBack)
                    is SettingsPage.MoodModel -> MoodModelPage(onBack = goBack)
                }
            }
        }
    }
}

private fun savePickedImage(ctx: android.content.Context, uri: Uri, fileName: String, onSaved: (java.io.File) -> Unit) {
    try {
        val input = ctx.contentResolver.openInputStream(uri)
        val file = java.io.File(ctx.filesDir, fileName)
        input?.use { it.copyTo(file.outputStream()) }
        onSaved(file)
    } catch (_: Exception) {}
}

private suspend fun addChat(ctx: android.content.Context, name: String, persona: String, avatarUri: String) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.aftglw.devapi.core.storage.room.AppDatabase.get(ctx).chatDao().upsert(
            com.aftglw.devapi.core.storage.room.ChatEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                persona = persona,
                avatarUri = avatarUri
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMainPage(onBack: () -> Unit, onNav: (SettingsPage) -> Unit) {
    val entries = listOf(
        Triple("个人信息", "昵称、头像、个人签名", SettingsPage.Profile),
        Triple("通知设置", "消息提示音 with 振动", SettingsPage.Notifications),
        Triple("AI 接口", "API 地址、密钥、模型与离线状态随机回复", SettingsPage.AiApi),
        Triple("工具安全", "工具白名单与高风险确认", SettingsPage.ToolSecurity),
        Triple("数据管理", "导出/清空聊天数据", SettingsPage.DataManagement),
        Triple("情绪模型", "导入/管理 ONNX 情绪识别模型", SettingsPage.MoodModel),
        Triple("管理角色", "添加对话角色与设定人设", SettingsPage.ManageRoles),
        Triple("背景设置", "主界面与聊天背景图片", SettingsPage.Backgrounds),
        Triple("界面设置", "通透效果、液态动效、字体、主题", SettingsPage.Appearance),
        Triple("调试", "开发用功能", SettingsPage.Debug),
        Triple("关于", "Wisp 信息", SettingsPage.About),
        Triple("MCP 服务", "管理外部 MCP Server 连接", SettingsPage.McpServers),
        Triple("工具管理", "安装/卸载动态工具包", SettingsPage.ToolMarket)
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", color = AchatTheme.colors.onSurface) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AchatTheme.colors.surface)
            )
        },
        containerColor = AchatTheme.colors.background
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(AchatTheme.colors.background).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            entries.forEachIndexed { index, (title, subtitle, page) ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                StaggeredEntrance(index = index, visible = visible) {
                    SettingsEntry(title, subtitle) { onNav(page) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
