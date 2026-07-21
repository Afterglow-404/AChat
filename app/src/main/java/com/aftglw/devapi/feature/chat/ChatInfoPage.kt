package com.aftglw.devapi.feature.chat
import android.util.Log
import com.aftglw.devapi.core.character.CharacterCardParser
import com.aftglw.devapi.core.ui.InfoRow
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.feature.settings.TextFieldRow

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对话详情页 — 角色信息、人设编辑、主动关怀、偏好设置、情绪可视化、记忆入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoPage(
    name: String, persona: String, avatarUri: String, chatKey: String = "",
    msgCount: Int, model: String, onBack: () -> Unit,
    onNavigateToDiary: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToEditor: () -> Unit = {},
    onNavigateToWorldbook: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    val apiUrl = prefs.getString("ai_api_url", "")?.takeIf { it.isNotEmpty() } ?: "未配置"
    val hasKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key").isNotEmpty()

    // 对话优化 traits 查看对话框 + 对话反思产物查看对话框
    var showTraitsDialog by remember { mutableStateOf(false) }
    var showReflectionDialog by remember { mutableStateOf(false) }
    var reflectionInsights by remember { mutableStateOf<List<com.aftglw.devapi.core.memory.MemoryItem>>(emptyList()) }
    var reflectionEmos by remember { mutableStateOf<List<com.aftglw.devapi.core.memory.MemoryItem>>(emptyList()) }
    LaunchedEffect(showReflectionDialog) {
        if (showReflectionDialog) {
            try {
                reflectionInsights = withContext(Dispatchers.IO) { MemoryStore.listRecentByTopic("insight:$name", 5) }
                reflectionEmos = withContext(Dispatchers.IO) { MemoryStore.listRecentByTopic("ai_emo:$name", 10) }
            } catch (e: Exception) {
                Log.e("ChatInfoPage", "load reflection failed", e)
                Toast.makeText(ctx, "加载反思产物失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("对话详情", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding())
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 头像区域
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(AchatTheme.colors.divider), contentAlignment = Alignment.Center) {
                    if (avatarUri.isNotEmpty()) {
                        var bmp by remember(avatarUri) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(avatarUri) {
                            bmp = withContext(Dispatchers.IO) {
                                try { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() } catch (e: Exception) { Log.w("ChatInfoPage", "avatar decode failed", e); null }
                            }
                        }
                        val bmpVal = bmp
                        if (bmpVal != null) Image(bmpVal, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        else Text(name.take(1), fontSize = 28.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    } else Text(name.take(1), fontSize = 28.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                }
            }
            InfoRow("对话名称", name)
            // 人设编辑 — 跳转到字段化编辑器
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).clickable { onNavigateToEditor() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("角色人设", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(0.3f))
                Spacer(Modifier.width(8.dp))
                Text(if (persona.isEmpty()) "点击编辑" else persona, fontSize = 14.sp, color = if (persona.isEmpty()) AchatTheme.colors.onSurface.copy(alpha = 0.3f) else AchatTheme.colors.onSurface, maxLines = 2, modifier = Modifier.weight(0.6f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "编辑", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
            }
            // 角色卡导入
            val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { pickedUri ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val input = ctx.contentResolver.openInputStream(pickedUri)
                                val card = com.aftglw.devapi.core.character.CharacterCardParser.parsePng(input!!); input.close()
                                if (card != null && card.name.isNotBlank()) {
                                    val avatarDir = java.io.File(ctx.filesDir, "avatars"); avatarDir.mkdirs()
                                    val avatarFile = java.io.File(avatarDir, "${name}_card.png")
                                    ctx.contentResolver.openInputStream(pickedUri)?.use { src -> avatarFile.outputStream().use { dst -> src.copyTo(dst) } }
                                    com.aftglw.devapi.core.character.CharacterCardParser.importToChat(ctx, name, card, avatarFile.absolutePath)
                                    card.name
                                } else null
                            } catch (e: Exception) {
                                android.util.Log.e("ChatInfoPage", "import card failed", e)
                                null
                            }
                        }
                        Toast.makeText(ctx, ok?.let { "已导入角色：$it" } ?: "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            TextButton(onClick = { cardPicker.launch("image/png") }, modifier = Modifier.fillMaxWidth()) { Text("导入角色卡 (SillyTavern)", fontSize = 13.sp, color = Color(0xFF888888)) }
            InfoRow("AI 模型", model); InfoRow("API 地址", apiUrl)
            InfoRow("API 密钥", if (hasKey) "已配置 🔒" else "未配置")
            InfoRow("通信协议", AiServiceFactory.getProtocolName()); InfoRow("消息总数", "$msgCount 条")
            // 当前角色的 GPT-SoVITS 语言覆盖；空值表示继承全局默认语言。
            if (prefs.getString("tts_engine", "local") == "gpt_sovits") {
                Spacer(Modifier.height(8.dp))
                Text("TTS 语言", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                val languageOptions = listOf("" to "继承全局默认", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "yue" to "粤语")
                val languageKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.languagePrefsKey("gpt_sovits", name)
                var selectedLanguage by remember(name) { mutableStateOf(prefs.getString(languageKey, "") ?: "") }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("角色语言", Modifier.weight(1f), fontSize = 14.sp, color = AchatTheme.colors.onSurface)
                        val languageExpanded = remember { mutableStateOf(false) }
                        Box {
                            Text(languageOptions.find { it.first == selectedLanguage }?.second ?: "继承全局默认", fontSize = 14.sp, color = AchatTheme.colors.primary, modifier = Modifier.clickable { languageExpanded.value = true })
                            DropdownMenu(expanded = languageExpanded.value, onDismissRequest = { languageExpanded.value = false }) {
                                languageOptions.forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = {
                                        selectedLanguage = code
                                        prefs.edit().apply {
                                            if (code.isEmpty()) remove(languageKey) else putString(languageKey, code)
                                        }.apply()
                                        languageExpanded.value = false
                                    })
                                }
                            }
                        }
                    }
                    Text("用于 GPT-SoVITS 的 text_lang 参数；未覆盖时使用全局默认值。", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (prefs.getString("tts_engine", "local") == "qwen3_tts") {
                Spacer(Modifier.height(8.dp))
                Text("Qwen3-TTS 角色设置", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                val voiceKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.prefsKey("qwen3_tts", name)
                val languageKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.languagePrefsKey("qwen3_tts", name)
                val instructionKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.instructionPrefsKey("qwen3_tts", name)
                var qwenVoice by remember(name) { mutableStateOf(prefs.getString(voiceKey, "") ?: "") }
                var qwenLanguage by remember(name) { mutableStateOf(prefs.getString(languageKey, "") ?: "") }
                var qwenInstruction by remember(name) { mutableStateOf(prefs.getString(instructionKey, "") ?: "") }
                val languageOptions = listOf("" to "继承全局默认", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "de" to "德语", "fr" to "法语", "ru" to "俄语", "pt" to "葡萄牙语", "es" to "西班牙语", "it" to "意大利语", "auto" to "自动")
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                    TextFieldRow("角色音色", "留空继承全局，例如 Vivian", qwenVoice) { qwenVoice = it; prefs.edit().putString(voiceKey, it.trim()).apply() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("角色语言", Modifier.weight(1f), fontSize = 14.sp, color = AchatTheme.colors.onSurface)
                        val languageExpanded = remember { mutableStateOf(false) }
                        Box {
                            Text(languageOptions.find { it.first == qwenLanguage }?.second ?: "继承全局默认", fontSize = 14.sp, color = AchatTheme.colors.primary, modifier = Modifier.clickable { languageExpanded.value = true })
                            DropdownMenu(expanded = languageExpanded.value, onDismissRequest = { languageExpanded.value = false }) {
                                languageOptions.forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = {
                                        qwenLanguage = code
                                        prefs.edit().apply { if (code.isEmpty()) remove(languageKey) else putString(languageKey, code) }.apply()
                                        languageExpanded.value = false
                                    })
                                }
                            }
                        }
                    }
                    TextFieldRow("角色语气", "留空继承全局，例如 温柔、亲切地说", qwenInstruction) { qwenInstruction = it; prefs.edit().putString(instructionKey, it.trim()).apply() }
                    Text("Qwen3-TTS 支持通过自然语言控制情绪、语速和表达方式。", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 6.dp))
                }
            }
            // 主动关怀
            Spacer(Modifier.height(8.dp))
            Text("主动关怀", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                var proactiveEnabled by remember { mutableStateOf(prefs.getBoolean("proactive_enabled_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主动关怀", Modifier.weight(1f), fontSize = 14.sp, color = AchatTheme.colors.onSurface)
                    Switch(checked = proactiveEnabled, onCheckedChange = { v -> proactiveEnabled = v; prefs.edit().putBoolean("proactive_enabled_$name", v).apply() })
                }
                if (proactiveEnabled) {
                    var dailyLimit by remember { mutableIntStateOf(prefs.getInt("proactive_daily_limit_$name", 3)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每日上限", Modifier.weight(1f), fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                        Slider(value = dailyLimit.toFloat(), onValueChange = { v -> dailyLimit = v.toInt(); prefs.edit().putInt("proactive_daily_limit_$name", v.toInt()).apply() }, valueRange = 1f..20f, steps = 3, modifier = Modifier.width(120.dp), colors = SliderDefaults.colors(thumbColor = AchatTheme.colors.primary, activeTrackColor = AchatTheme.colors.primary))
                        Text("${dailyLimit}条", fontSize = 13.sp, color = AchatTheme.colors.onSurface)
                    }
                }
            }
            // 对话功能
            Spacer(Modifier.height(8.dp))
            Text("对话功能", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                var dialogueOpt by remember { mutableStateOf(prefs.getBoolean("dialogue_optimization_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对话优化", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = dialogueOpt, onCheckedChange = { v -> dialogueOpt = v; prefs.edit().putBoolean("dialogue_optimization_$name", v).apply() })
                }
                if (dialogueOpt) {
                    val traitsText = prefs.getString("persona_dialogue_traits_$name", "") ?: ""
                    val traitsTs = prefs.getLong("persona_dialogue_traits_ts_$name", 0L)
                    Row(Modifier.fillMaxWidth().clickable { showTraitsDialog = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "查看用户特点${if (traitsText.isNotBlank()) " ✓" else "（暂无）"}",
                            Modifier.weight(1f), fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                        if (traitsTs > 0L) {
                            Text(
                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(traitsTs)),
                                fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                    }
                }
                var reflection by remember { mutableStateOf(prefs.getBoolean("reflection_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对话反思", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = reflection, onCheckedChange = { v -> reflection = v; prefs.edit().putBoolean("reflection_$name", v).apply() })
                }
                if (reflection) {
                    Row(Modifier.fillMaxWidth().clickable { showReflectionDialog = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("查看反思产物", Modifier.weight(1f), fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                    }
                }
            }
            // 情绪可视化
            if (prefs.getBoolean("mood_enabled", false)) {
                Spacer(Modifier.height(8.dp))
                Text("情绪", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                    var moodVis by remember { mutableStateOf(prefs.getBoolean("mood_visualization", false)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("情绪可视化", Modifier.weight(1f), fontSize = 14.sp, color = AchatTheme.colors.onSurface)
                        Switch(checked = moodVis, onCheckedChange = { v -> moodVis = v; prefs.edit().putBoolean("mood_visualization", v).apply() })
                    }
                }
            }
            // 记忆入口
            Spacer(Modifier.height(8.dp))
            Text("记忆", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                var diaryCount by remember { mutableIntStateOf(0) }
                LaunchedEffect(name) {
                    diaryCount = withContext(Dispatchers.IO) { MemoryStore.count("diary:$name") }
                }
                Row(Modifier.fillMaxWidth().clickable { onNavigateToDiary() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("📖 日记 ($diaryCount)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                }
                Row(Modifier.fillMaxWidth().clickable { onNavigateToMemory() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠 全部记忆", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                }
            }
            // 世界书入口
            Spacer(Modifier.height(8.dp))
            Text("世界书", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
            var worldbookCount by remember { mutableIntStateOf(0) }
            LaunchedEffect(name) {
                worldbookCount = withContext(Dispatchers.IO) { com.aftglw.devapi.core.worldbook.WorldbookStore.load(ctx, name).size }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).clickable { onNavigateToWorldbook() }.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🌍 世界书 ($worldbookCount)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                }
            }
            // 好感度
            if (prefs.getBoolean("affinity_enabled", false)) {
                Spacer(Modifier.height(8.dp))
                Text("好感度", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)) {
                    var mode by remember { mutableStateOf(prefs.getString("affinity_mode_$name", "auto") ?: "auto") }
                    var lockLv by remember { mutableIntStateOf(prefs.getInt("affinity_lock_$name", 0)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "auto", onClick = { prefs.edit().putString("affinity_mode_$name", "auto").apply(); mode = "auto" })
                        Text("自动成长", Modifier.weight(1f), fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "lock", onClick = { prefs.edit().putString("affinity_mode_$name", "lock").apply(); mode = "lock" })
                        Text("手动锁定", Modifier.weight(1f), fontSize = 14.sp)
                    }
                    if (mode == "lock") {
                        Slider(value = lockLv.toFloat(), onValueChange = { v -> lockLv = v.toInt(); prefs.edit().putInt("affinity_lock_$name", v.toInt()).apply() }, valueRange = 0f..4f, steps = 3, colors = SliderDefaults.colors(thumbColor = AchatTheme.colors.primary, activeTrackColor = AchatTheme.colors.primary))
                    }
                }
            }
        }
    }

    // 用户特点 traits 查看对话框
    if (showTraitsDialog) {
        val traitsText = prefs.getString("persona_dialogue_traits_$name", "") ?: ""
        val traitsTs = prefs.getLong("persona_dialogue_traits_ts_$name", 0L)
        AlertDialog(
            onDismissRequest = { showTraitsDialog = false },
            title = { Text("用户特点（对话优化产物）") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (traitsTs > 0L) {
                        Text(
                            "最近更新：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(traitsTs))}",
                            fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (traitsText.isBlank()) {
                        Text(
                            "暂无特点记录。开启对话优化后，每 20 轮或 1 小时会自动分析。",
                            fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        traitsText.lines().filter { it.isNotBlank() }.forEach { line ->
                            Text("• $line", fontSize = 13.sp, color = AchatTheme.colors.onSurface, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = {
                            showTraitsDialog = false
                            scope.launch {
                                prefs.edit().remove("persona_dialogue_traits_$name").remove("persona_dialogue_traits_ts_$name").apply()
                                Toast.makeText(ctx, "已重置用户特点", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("重置", color = Color(0xFFE53935)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showTraitsDialog = false }) { Text("关闭") }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // 对话反思产物查看对话框
    if (showReflectionDialog) {
        AlertDialog(
            onDismissRequest = { showReflectionDialog = false },
            title = { Text("对话反思产物") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("对话本质 (insight)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    if (reflectionInsights.isEmpty()) {
                        Text("暂无。开启反思后每轮会自动分析。", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                    } else {
                        reflectionInsights.forEachIndexed { i, it ->
                            Text("${i + 1}. ${it.text}", fontSize = 12.sp, color = AchatTheme.colors.onSurface, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("AI 情绪 (ai_emo)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    if (reflectionEmos.isEmpty()) {
                        Text("暂无。", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                    } else {
                        reflectionEmos.forEachIndexed { i, it ->
                            Text("${i + 1}. ${it.text}", fontSize = 12.sp, color = AchatTheme.colors.onSurface, modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = {
                            showReflectionDialog = false
                            scope.launch {
                                try {
                                    MemoryStore.deleteByTopic("insight:$name")
                                    MemoryStore.deleteByTopic("ai_emo:$name")
                                    reflectionInsights = emptyList()
                                    reflectionEmos = emptyList()
                                    Toast.makeText(ctx, "已清空反思产物", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Log.e("ChatInfoPage", "clear reflection failed", e)
                                    Toast.makeText(ctx, "清空失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("清空全部", color = Color(0xFFE53935)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showReflectionDialog = false }) { Text("关闭") }
                    }
                }
            },
            confirmButton = {}
        )
    }
}
