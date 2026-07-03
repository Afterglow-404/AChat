package com.aftglw.devapi

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.aftglw.devapi.MoodModel
import com.aftglw.devapi.ui.buildCustomTypography
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.screens.WeChatScreenWithData
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
    data object WCPreview : SettingsPage()
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
    var apiKey by remember { mutableStateOf(prefs.getString("ai_api_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "deepsleep-cat") ?: "deepsleep-cat") }
    var mockReplies by remember { mutableStateOf(prefs.getString("mock_replies", "") ?: "") }
    var mockDelay by remember { mutableStateOf(prefs.getString("mock_delay_ms", "800") ?: "800") }

    var newChatName by remember { mutableStateOf("") }
    var newChatPersona by remember { mutableStateOf("") }
    var newChatAvatarUri by remember { mutableStateOf("") }

    var glassTransparent by remember { mutableStateOf(prefs.getBoolean("glass_transparent", true)) }
    var physicsEnabled by remember { mutableStateOf(prefs.getBoolean("physics_enabled", true)) }
    var customFont by remember { mutableStateOf(initialCustom) }
    var longContextMode by remember { mutableStateOf(prefs.getBoolean("long_context_mode", true)) }
    
    var showTimestamps by remember { mutableStateOf(prefs.getBoolean("show_timestamps", true)) }
    var hitokotoType by remember { mutableStateOf(prefs.getString("hitokoto_type", "") ?: "") }
    var moodEnabled by remember { mutableStateOf(prefs.getBoolean("mood_enabled", false)) }
    var affinityEnabled by remember { mutableStateOf(prefs.getBoolean("affinity_enabled", false)) }
    var localMode by remember { mutableStateOf(prefs.getBoolean("local_mode", false)) }
    LaunchedEffect(customFont) { typography.value = if (customFont) buildCustomTypography() else Typography() }

    var profileName by remember { mutableStateOf(prefs.getString("profile_name", "User") ?: "User") }
    var profileWechatId by remember { mutableStateOf(prefs.getString("profile_wechat_id", "微信号: achat_miao") ?: "微信号: achat_miao") }
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

    MaterialTheme(typography = typography.value) {
    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            if (targetState is SettingsPage.Main) {
                (slideInHorizontally { -it }) togetherWith (slideOutHorizontally { it })
            } else {
                (slideInHorizontally { it }) togetherWith (slideOutHorizontally { -it })
            }
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
            apiKey, { apiKey = it; prefs.edit().putString("ai_api_key", it).apply() },
            model, { model = it; prefs.edit().putString("ai_model", it).apply() },
            mockReplies, { mockReplies = it; prefs.edit().putString("mock_replies", it).apply() },
            mockDelay, { mockDelay = it; prefs.edit().putString("mock_delay_ms", it).apply() },
            longContextMode, { longContextMode = it; prefs.edit().putBoolean("long_context_mode", it).apply() }
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
                    addChat(ctx, name, newChatPersona.trim(), newChatAvatarUri)
                    newChatName = ""; newChatPersona = ""; newChatAvatarUri = ""
                    Toast.makeText(ctx, "已添加喵~", Toast.LENGTH_SHORT).show()
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
            onNavToWC = { nav(SettingsPage.WCPreview) }
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
            localMode, { localMode = it; prefs.edit().putBoolean("local_mode", it).apply(); if (!it) com.aftglw.devapi.network.AiServiceFactory.unloadLocal() }
        )
        is SettingsPage.About -> AboutPage(onBack = goBack)
        is SettingsPage.WCPreview -> WeChatScreenWithData(onBack = goBack)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMainPage(onBack: () -> Unit, onNav: (SettingsPage) -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", color = Color(0xFF1A1A1A)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFF1A1A1A)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            SettingsEntry("个人信息", "昵称、头像、微信号") { onNav(SettingsPage.Profile) }
            SettingsEntry("通知设置", "消息提示音与振动") { onNav(SettingsPage.Notifications) }
            SettingsEntry("AI 接口", "API 地址、密钥、模型与离线状态随机回复") { onNav(SettingsPage.AiApi) }
            SettingsEntry("管理角色", "添加对话角色与设定人设") { onNav(SettingsPage.ManageRoles) }
            SettingsEntry("背景设置", "主界面与聊天背景图片") { onNav(SettingsPage.Backgrounds) }
            SettingsEntry("界面设置", "通透效果、液态动效、字体") { onNav(SettingsPage.Appearance) }
            SettingsEntry("调试", "开发用功能") { onNav(SettingsPage.Debug) }
            SettingsEntry("关于", "AChat 信息") { onNav(SettingsPage.About) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFFCCCCCC))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFFF0F0F0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = Color(0xFF1A1A1A)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFF1A1A1A)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun ProfilePage(
    onBack: () -> Unit,
    profileName: String, onProfileNameChange: (String) -> Unit,
    profileWechatId: String, onProfileWechatIdChange: (String) -> Unit,
    profileAvatarUri: String, onPickProfileAvatar: () -> Unit, onClearProfileAvatar: () -> Unit
) {
    SubPageScaffold("个人信息", onBack) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) {
                if (profileAvatarUri.isNotEmpty()) {
                    val bmp = remember(profileAvatarUri) { try { BitmapFactory.decodeFile(profileAvatarUri)?.asImageBitmap() } catch (_: Exception) { null } }
                    if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Text(profileName.take(1), fontSize = 20.sp, color = Color.Gray)
                } else Text(profileName.take(1), fontSize = 20.sp, color = Color.Gray)
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onPickProfileAvatar) { Text(if (profileAvatarUri.isNotEmpty()) "更换头像" else "选择头像", color = Color(0xFF07C160)) }
            if (profileAvatarUri.isNotEmpty()) TextButton(onClick = onClearProfileAvatar) { Text("清除", color = Color.Gray) }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFFF0F0F0))
        TextFieldRow("昵称", "User", profileName, onProfileNameChange)
        TextFieldRow("微信号", "achat_miao", profileWechatId, onProfileWechatIdChange)
    }
}

@Composable
private fun NotificationsPage(
    onBack: () -> Unit,
    sound: Boolean, onSoundChange: (Boolean) -> Unit,
    vibrate: Boolean, onVibrateChange: (Boolean) -> Unit
) {
    SubPageScaffold("通知设置", onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("通知")
        ToggleRow("新消息通知", "接收新消息时播放提示音", sound, onSoundChange)
        ToggleRow("振动", "新消息时振动", vibrate, onVibrateChange)
    }
}

@Composable
private fun AiApiPage(
    onBack: () -> Unit,
    apiUrl: String, onApiUrlChange: (String) -> Unit,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    model: String, onModelChange: (String) -> Unit,
    mockReplies: String, onMockRepliesChange: (String) -> Unit,
    mockDelay: String, onMockDelayChange: (String) -> Unit,
    longContext: Boolean, onLongContextChange: (Boolean) -> Unit
) {
    SubPageScaffold("AI 接口", onBack) {
        Spacer(Modifier.height(8.dp))
        TextFieldRow("API 地址", "https://api.114514.com/v1", apiUrl, onApiUrlChange)
        PasswordRow("API Key", "sk-1145141919810", apiKey, onApiKeyChange)
        TextFieldRow("模型名", "gpt-114514", model, onModelChange)
        ToggleRow("长上下文模式", "DeepSeek/GPT 等长上下文模型无需重注入提示，关闭可节省小模型 tokens", longContext, onLongContextChange)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingsMainHeader("离线回复（未接入 API 时生效）")
        TextFieldRow("回复词库", "用 | 分隔，如：好的|行|不行|...", mockReplies, onMockRepliesChange)
        TextFieldRow("延迟(ms)", "默认800", mockDelay, onMockDelayChange)
    }
}

@Composable
private fun ManageRolesPage(
    onBack: () -> Unit,
    newChatName: String, onNewChatNameChange: (String) -> Unit,
    newChatPersona: String, onNewChatPersonaChange: (String) -> Unit,
    newChatAvatarUri: String,
    onPickAvatar: () -> Unit, onClearAvatar: () -> Unit,
    onCreateChat: () -> Unit
) {
    val ctx = LocalContext.current
    SubPageScaffold("管理角色", onBack) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Color(0xFFF0F0F0), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    if (newChatAvatarUri.isNotEmpty()) {
                        val bmp = remember(newChatAvatarUri) { try { BitmapFactory.decodeFile(newChatAvatarUri)?.asImageBitmap() } catch (_: Exception) { null } }
                        if (bmp != null) Image(bmp, null, Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
                    } else Text("+", fontSize = 20.sp, color = Color.Gray)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onPickAvatar) { Text(if (newChatAvatarUri.isNotEmpty()) "更换头像" else "选择头像", color = Color(0xFF07C160)) }
                if (newChatAvatarUri.isNotEmpty()) TextButton(onClick = onClearAvatar) { Text("清除", color = Color.Gray) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newChatName, onNewChatNameChange, Modifier.fillMaxWidth(), placeholder = { Text("对话名称") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newChatPersona, onNewChatPersonaChange, Modifier.fillMaxWidth().height(100.dp),
                placeholder = { Text("角色人设，例如：你是一个傲娇的猫娘喵，说话带喵...") }, maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
            Spacer(Modifier.height(4.dp))
            val importSkillLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    try {
                        val raw = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
                        val chatName = newChatName.trim().ifEmpty { "imported" }
                        // 1. 去掉 YAML frontmatter（--- ... ---）
                        val cleaned = raw.replace(Regex("""^---[\s\S]*?---\n*"""), "").trim()
                        // 2. 分离记忆（PART A）和人设（PART B）
                        val parts = cleaned.split(Regex("""## PART [AB]"""))
                        val personaText = parts.getOrElse(2) { parts.getOrElse(1) { cleaned } }.trim()
                        val memoryText = parts.getOrElse(1) { "" }
                        // 3. 填人设（只填 PART B，不含运行规则）
                        val personaClean = personaText.split("## 运行规则").firstOrNull()?.trim() ?: personaText
                        onNewChatPersonaChange(personaClean)
                        // 4. 从记忆段提取具体条目
                        val memories = memoryText.lines().filter { it.trim().startsWith("- ") || it.trim().startsWith("• ") }
                            .map { it.trim().removePrefix("- ").removePrefix("• ") }.filter { it.length > 5 }
                        com.aftglw.devapi.MemoryStore.init(ctx)
                        memories.take(15).forEach { com.aftglw.devapi.MemoryStore.save(ctx, it, "skill:$chatName") }
                        android.widget.Toast.makeText(ctx, "Skill 导入成功（${memories.size} 条记忆）", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { android.widget.Toast.makeText(ctx, "导入失败", Toast.LENGTH_SHORT).show() }
                }
            }
            TextButton(onClick = { importSkillLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("导入 Skill（从 ex-skill 文件）", fontSize = 13.sp, color = Color(0xFF888888))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCreateChat, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)), shape = CircleShape) { Text("创建角色") }
        }
    }
}

@Composable
private fun BackgroundsPage(
    onBack: () -> Unit,
    mainBgUri: String, onPickMainBg: () -> Unit, onResetMainBg: () -> Unit,
    chatBgUri: String, onPickChatBg: () -> Unit, onResetChatBg: () -> Unit
) {
    SubPageScaffold("背景设置", onBack) {
        Spacer(Modifier.height(8.dp))
        BgRow("主界面背景", mainBgUri, onPickMainBg, onResetMainBg)
        HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFFF0F0F0))
        BgRow("聊天背景", chatBgUri, onPickChatBg, onResetChatBg)
    }
}

@Composable
private fun BgRow(label: String, uri: String, onPick: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        TextButton(onClick = onPick) { Text(if (uri.isNotEmpty()) "更换" else "选择图片", color = Color(0xFF07C160)) }
        if (uri.isNotEmpty()) TextButton(onClick = onReset) { Text("重置", color = Color.Gray) }
    }
}

@Composable
private fun AppearancePage(
    onBack: () -> Unit,
    glassTransparent: Boolean, onGlassTransparentChange: (Boolean) -> Unit,
    physicsEnabled: Boolean, onPhysicsEnabledChange: (Boolean) -> Unit,
    customFont: Boolean, onCustomFontChange: (Boolean) -> Unit,
    showTimestamps: Boolean, onShowTimestampsChange: (Boolean) -> Unit,
    hitokotoType: String, onHitokotoTypeChange: (String) -> Unit,
    onNavToWC: () -> Unit
) {
    SubPageScaffold("界面设置", onBack) {
        Spacer(Modifier.height(8.dp))
        ToggleRow("底栏通透效果", "更通透的底栏效果，性能有略微影响", glassTransparent, onGlassTransparentChange)
        ToggleRow("液态动效", "拖拽弹性、按压缩放、高光反馈，性能影响较大", physicsEnabled, onPhysicsEnabledChange)
        ToggleRow("一套新字体", "Noto Sans SC（中）+ Space Mono（英）", customFont, onCustomFontChange)
        ToggleRow("消息时间戳", "聊天气泡底部显示发送时间", showTimestamps, onShowTimestampsChange)

        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("实验室")
        SettingsEntry("微信首页复刻", "查看 WCduplicate 复刻效果") { onNavToWC() }

        Spacer(Modifier.height(8.dp))
        Text("一言类型（可多选）", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

        val types = listOf(
            "" to "随机", "a" to "动画", "b" to "漫画", "c" to "游戏", "d" to "文学",
            "e" to "原创", "f" to "网络", "h" to "影视", "i" to "诗词", "j" to "网易云", "k" to "哲学", "l" to "抖机灵"
        )
        val checked = remember(hitokotoType) { hitokotoType.split(",").filter { it.isNotEmpty() }.toMutableSet() }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 4.dp)) {
            types.forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = key in checked || (key == "" && checked.isEmpty()),
                        onCheckedChange = { isChecked ->
                            val newSet = checked.toMutableSet()
                            if (key == "") {
                                onHitokotoTypeChange("")
                            } else {
                                if (isChecked) newSet.add(key) else newSet.remove(key)
                                onHitokotoTypeChange(if (newSet.isEmpty()) "" else newSet.joinToString(","))
                            }
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF07C160))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DebugPage(
    onBack: () -> Unit,
    debug: Boolean, onDebugChange: (Boolean) -> Unit,
    moodEnabled: Boolean, onMoodEnabledChange: (Boolean) -> Unit,
    affinityEnabled: Boolean, onAffinityEnabledChange: (Boolean) -> Unit,
    localMode: Boolean, onLocalModeChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    SubPageScaffold("调试", onBack) {
        Spacer(Modifier.height(8.dp))
        ToggleRow("Debug 窗", "显示调试信息(待完善)", debug, onDebugChange)

        Spacer(Modifier.height(8.dp))
        ToggleRow("情绪感知", "使用模型本地分析对话情绪(待完善)", moodEnabled, onMoodEnabledChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模型已集成", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        ToggleRow("好感度系统", "AI 语气随相处变化(待完善)", affinityEnabled, onAffinityEnabledChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("开关控制各对话详情页中的好感度调节", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        ToggleRow("本地模式", "用本地 Qwen 模型处理对话（需提前放置模型文件）", localMode, onLocalModeChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("关闭后恢复使用云端 API", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        val logExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                try {
                    val sb = StringBuilder()
                    sb.appendLine("=== AChat Debug Log ===")
                    sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                    sb.appendLine("Device: ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                    sb.appendLine()
                    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                    sb.appendLine("=== Settings ===")
                    sb.appendLine("API URL: ${prefs.getString("ai_api_url", "")?.take(60)}")
                    sb.appendLine("Model: ${prefs.getString("ai_model", "")}")
                    sb.appendLine("Embedding Model: detected from URL")
                    sb.appendLine("Long Context: ${prefs.getBoolean("long_context_mode", false)}")
                    sb.appendLine("Mood Detection: ${prefs.getBoolean("mood_enabled", false)}")
                    sb.appendLine("Affinity: ${prefs.getBoolean("affinity_enabled", false)}")
                    sb.appendLine("Local Mode: ${prefs.getBoolean("local_mode", false)}")
                    sb.appendLine("Timezone: ${prefs.getString("timezone_id", java.util.TimeZone.getDefault().id)}")
                    sb.appendLine("Glass Effect: ${prefs.getBoolean("glass_transparent", false)}")
                    sb.appendLine("Custom Font: ${prefs.getBoolean("custom_font", false)}")
                    sb.appendLine("Notification Sound: ${prefs.getBoolean("notification_sound", true)}")
                    sb.appendLine("Notification Vibrate: ${prefs.getBoolean("notification_vibrate", true)}")
                    sb.appendLine("Show Timestamps: ${prefs.getBoolean("show_timestamps", true)}")
                    sb.appendLine("Debug Overlay: ${prefs.getBoolean("debug_overlay", false)}")
                    sb.appendLine("Physics Enabled: ${prefs.getBoolean("physics_enabled", true)}")
                    sb.appendLine("Protocol: ${com.aftglw.devapi.network.AiServiceFactory.getProtocolName()}")
                    sb.appendLine()
                    sb.appendLine("=== All Chats ===")
                    val chats = org.json.JSONArray(ctx.getSharedPreferences("wechat_chats", Context.MODE_PRIVATE).getString("chats", "[]") ?: "[]")
                    for (i in 0 until chats.length()) {
                        val o = chats.getJSONObject(i)
                        val chatName = o.getString("name")
                        val msgCount = com.aftglw.devapi.ChatHistory.load(ctx, chatName).size
                        val affVal = com.aftglw.devapi.AffinityManager.getAffinity(prefs, chatName).toInt()
                        val affMode = if (com.aftglw.devapi.AffinityManager.isAutoMode(prefs, chatName)) "auto" else "locked"
                        val lastMood = prefs.getString("last_mood_$chatName", "") ?: ""
                        val hasPersona = o.optString("persona", "").isNotBlank()
                        val pEnable = prefs.getBoolean("proactive_enabled_$chatName", false)
                        val pMode = prefs.getString("proactive_trigger_mode_$chatName", "custom") ?: "custom"
                        val pLong = prefs.getBoolean("proactive_long_history_$chatName", false)
                        val pLimit = prefs.getInt("proactive_daily_limit_$chatName", 3)
                        val pTriggers = prefs.getString("proactive_triggers_$chatName", "1,2,3,4,5,6") ?: "?"
                        val pIdle = prefs.getInt("proactive_idle_hours_$chatName", 0)
                        val pCheck = prefs.getString("proactive_check_mode_$chatName", "random") ?: "?"
                        val archive = prefs.getBoolean("auto_archive_$chatName", true)
                        val diagOpt = prefs.getBoolean("dialogue_optimization_$chatName", false)
                        val refl = prefs.getBoolean("reflection_$chatName", false)
                        val moodVis = prefs.getBoolean("mood_visualization", false)
                        sb.appendLine("  $chatName | msg=$msgCount | aff=$affVal($affMode) | mood=$lastMood | persona=${if (hasPersona) "yes" else "no"} | arch=$archive | opt=$diagOpt | refl=$refl | pro=$pEnable($pMode) | longH=$pLong | vis=$moodVis | limit=$pLimit | idle=${pIdle}h | check=$pCheck | trigs=$pTriggers")
                    }
                    sb.appendLine()
                    sb.appendLine("=== Emotion Detection ===")
                    sb.appendLine("Last Mood: ${com.aftglw.devapi.MoodDetector.lastMood ?: "N/A"}")
                    sb.appendLine("Last Hint: ${com.aftglw.devapi.MoodDetector.lastHint ?: "N/A"}")
                    sb.appendLine("Source: ${com.aftglw.devapi.MoodDetector.lastSource}")
                    sb.appendLine("Feed Count: ${com.aftglw.devapi.MoodDetector.feedCount}")
                    sb.appendLine("Model Error: ${com.aftglw.devapi.MoodDetector.lastModelError}")
                    sb.appendLine("Last ONNX Confidence: ${(com.aftglw.devapi.MoodModel.lastConfidence * 100).toInt()}%")
                    sb.appendLine()
                    sb.appendLine("=== Usage ===")
                    sb.appendLine("Last Tokens In: ${prefs.getInt("last_tokens_in", 0)}")
                    sb.appendLine("Last Tokens Out: ${prefs.getInt("last_tokens_out", 0)}")
                    sb.appendLine("Proactive Daily Count: ${prefs.getInt("proactive_count_${com.aftglw.devapi.MoodDetector.lastMood ?: "unknown"}_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date()), 0)}")
                    sb.appendLine()
                    sb.appendLine("=== NTP Sync ===")
                    sb.appendLine("TimeService Active: true")
                    sb.appendLine("Formatted: ${com.aftglw.devapi.TimeService.getFormattedTime(ctx)}")
                    ctx.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(sb.toString().toByteArray())
                    }
                    android.widget.Toast.makeText(ctx, "日志已导出", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { android.widget.Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show() }
            }
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { logExporter.launch("AChat_debug_log.txt") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888888))
        ) { Text("导出调试日志", fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("时区", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(0.3f))
            Spacer(Modifier.width(8.dp))
            val tzPrefs = ctx.getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
            val tzId = tzPrefs.getString("timezone_id", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id
            val tzExpanded = remember { mutableStateOf(false) }
            Box {
                Text(tzId, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { tzExpanded.value = true })
                DropdownMenu(expanded = tzExpanded.value, onDismissRequest = { tzExpanded.value = false }) {
                    com.aftglw.devapi.TimeService.getAvailableTimezones().forEach { (id, label) ->
                        DropdownMenuItem(text = { Text("$id  $label", fontSize = 13.sp) }, onClick = {
                            com.aftglw.devapi.TimeService.setTimezone(ctx, id)
                            tzExpanded.value = false
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    SubPageScaffold("关于", onBack) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AChat", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(4.dp))
            Text("Preview Version", fontSize = 13.sp, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal)
            Text("Pre-Alpha (Dev)", fontSize = 13.sp, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Spacer(Modifier.height(12.dp))
            Text("WeChat 的拙劣模仿品，你能在「AChat」中与 AI 模型聊天。", fontSize = 12.sp, color = Color(0xFF888888))
            Text("支持 OpenAI 兼容 API 对话，支持自定义对话人设。", fontSize = 12.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(8.dp))
            Text("💗 爱来自 AFTGLW 与 Deepseek-Reasonix 💗", fontSize = 11.sp, color = Color(0xFFBBBBBB))
            Text("感谢 Kyant0 的 AndroidLiquidGlass 库", fontSize = 11.sp, color = Color(0xFFBBBBBB))
            Text("感谢 一言API 素颜API TheCatAPI BoredAPI LeetCodeAPI", fontSize = 11.sp, color = Color(0xFFBBBBBB))
            Text("感谢 chinese-roberta-wwm-ext ", fontSize = 11.sp, color = Color(0xFFBBBBBB))
            Text("感谢 sepidmnorozy 的 Chinese_sentiment 数据，感谢 t1annnnn 的 Chinese_sentimentAnalyze 数据", fontSize = 11.sp, color = Color(0xFFBBBBBB))
        }
    }
}

@Composable
private fun SettingsMainHeader(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF07C160)))
    }
}

@Composable
private fun TextFieldRow(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
    }
}

@Composable
private fun PasswordRow(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
    }
}

private fun addChat(ctx: android.content.Context, name: String, persona: String = "", avatarUri: String = "") {
    val prefs = ctx.getSharedPreferences("wechat_chats", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("chats", "[]") ?: "[]"
    val arr = JSONArray(json)
    val colors = listOf("#1A7DC0", "#E8512B", "#07C160", "#A020F0", "#FF9500", "#E91E63", "#607D8B", "#795548", "#009688", "#FF5722", "#3F51B5", "#00BCD4")
    val obj = JSONObject().apply {
        put("id", System.currentTimeMillis().toString())
        put("name", name)
        put("lastMessage", "")
        put("time", "")
        put("unreadCount", 0)
        put("avatarColor", colors[(name.hashCode() and Int.MAX_VALUE) % colors.size])
        put("pinned", false)
        put("persona", persona)
        put("avatarUri", avatarUri)
    }
    arr.put(obj)
    prefs.edit().putString("chats", arr.toString()).apply()
}

@Preview(showBackground = true)
@Composable
fun SettingsMainPreview() {
    MaterialTheme { SettingsMainPage(onBack = {}, onNav = {}) }
}
