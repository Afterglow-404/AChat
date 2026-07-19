package com.aftglw.devapi.feature.chat
import com.aftglw.devapi.core.character.CharacterCardParser
import com.aftglw.devapi.core.ui.InfoRow
import com.aftglw.devapi.core.memory.MemoryStore

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.network.AiServiceFactory
import com.aftglw.devapi.ui.theme.*

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
    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    val apiUrl = prefs.getString("ai_api_url", "")?.takeIf { it.isNotEmpty() } ?: "未配置"
    val hasKey = com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "ai_api_key").isNotEmpty()

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
                        val bmp = remember(avatarUri) { try { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() } catch (_: Exception) { null } }
                        if (bmp != null) Image(bmp, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
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
                uri?.let {
                    try {
                        val input = ctx.contentResolver.openInputStream(it)
                        val card = com.aftglw.devapi.core.character.CharacterCardParser.parsePng(input!!); input.close()
                        if (card != null && card.name.isNotBlank()) {
                            val avatarDir = java.io.File(ctx.filesDir, "avatars"); avatarDir.mkdirs()
                            val avatarFile = java.io.File(avatarDir, "${name}_card.png")
                            ctx.contentResolver.openInputStream(it)?.use { src -> avatarFile.outputStream().use { dst -> src.copyTo(dst) } }
                            com.aftglw.devapi.core.character.CharacterCardParser.importToChat(ctx, name, card, avatarFile.absolutePath)
                            Toast.makeText(ctx, "已导入角色：${card.name}", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(ctx, "未找到有效角色卡", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(ctx, "导入失败", Toast.LENGTH_SHORT).show() }
                }
            }
            TextButton(onClick = { cardPicker.launch("image/png") }, modifier = Modifier.fillMaxWidth()) { Text("导入角色卡 (SillyTavern)", fontSize = 13.sp, color = Color(0xFF888888)) }
            InfoRow("AI 模型", model); InfoRow("API 地址", apiUrl)
            InfoRow("API 密钥", if (hasKey) "已配置 🔒" else "未配置")
            InfoRow("通信协议", AiServiceFactory.getProtocolName()); InfoRow("消息总数", "$msgCount 条")
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
                var dialogueOpt by remember { mutableStateOf(prefs.getBoolean("dialogue_optimization_$name", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对话优化", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = dialogueOpt, onCheckedChange = { v -> dialogueOpt = v; prefs.edit().putBoolean("dialogue_optimization_$name", v).apply() })
                }
                var reflection by remember { mutableStateOf(prefs.getBoolean("reflection_$name", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对话反思", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = reflection, onCheckedChange = { v -> reflection = v; prefs.edit().putBoolean("reflection_$name", v).apply() })
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
                val diaryCount = MemoryStore.search(ctx, "日记", 1, "diary:$name").size
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
            val worldbookCount = com.aftglw.devapi.core.worldbook.WorldbookStore.load(ctx, name).size
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
}
