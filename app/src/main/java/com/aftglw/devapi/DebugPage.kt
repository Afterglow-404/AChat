package com.aftglw.devapi

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.theme.*

@Composable
fun DebugPage(
    onBack: () -> Unit,
    debug: Boolean, onDebugChange: (Boolean) -> Unit,
    moodEnabled: Boolean, onMoodEnabledChange: (Boolean) -> Unit,
    affinityEnabled: Boolean, onAffinityEnabledChange: (Boolean) -> Unit,
    localMode: Boolean, onLocalModeChange: (Boolean) -> Unit,
    openBookMode: Boolean, onOpenBookModeChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    SubPageScaffold("调试", onBack) {
        Spacer(Modifier.height(8.dp))
        ToggleRow("Debug 窗", "显示调试信息(待完善)", debug, onDebugChange)
        val sysPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        var sysEnter by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_enter", true)) }
        var sysTimer by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_timer", true)) }
        var sysHotline by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_hotline", true)) }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("系统消息", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Switch(checked = sysEnter || sysTimer || sysHotline, onCheckedChange = { v -> sysEnter = v; sysTimer = v; sysHotline = v; sysPrefs.edit().putBoolean("sysmsg_enter", v).putBoolean("sysmsg_timer", v).putBoolean("sysmsg_hotline", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("进入提示", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysEnter, onCheckedChange = { v -> sysEnter = v; sysPrefs.edit().putBoolean("sysmsg_enter", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("2小时提醒", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysTimer, onCheckedChange = { v -> sysTimer = v; sysPrefs.edit().putBoolean("sysmsg_timer", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("求助热线", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysHotline, onCheckedChange = { v -> sysHotline = v; sysPrefs.edit().putBoolean("sysmsg_hotline", v).apply() })
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("情绪感知", "使用模型本地分析对话情绪(待完善)", moodEnabled, onMoodEnabledChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模型已集成", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("🔓 开诚布公", "允许 AI 访问位置、通知、应用使用统计", openBookMode, onOpenBookModeChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 调用前会先询问你的许可，数据仅当前对话内存中有效", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("好感度系统", "AI 语气随相处变化(待完善)", affinityEnabled, onAffinityEnabledChange)
        ToggleRow("本地模式", "用本地 Qwen 模型处理对话（需提前放置模型文件）", localMode, onLocalModeChange)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val dbPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            Text("历史消息数", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.width(80.dp))
            val ctxw = dbPrefs.getInt("context_window", 0)
            val cwOptions = listOf(0 to "自动", 10 to "10条", 20 to "20条", 30 to "30条", 50 to "50条", 80 to "80条", 100 to "100条")
            val cwExpanded = remember { mutableStateOf(false) }
            Box { val cwLabel = cwOptions.find { it.first == ctxw }?.second ?: "自动"
                Text(cwLabel, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { cwExpanded.value = true })
                DropdownMenu(expanded = cwExpanded.value, onDismissRequest = { cwExpanded.value = false }) {
                    cwOptions.forEach { (v, label) -> DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { dbPrefs.edit().putInt("context_window", v).apply(); cwExpanded.value = false }) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val shareLog: () -> Unit = {
            try {
                val sb = StringBuilder()
                sb.appendLine("=== Wisp Debug Log ===")
                sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                sb.appendLine("Device: ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                sb.appendLine(); val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                sb.appendLine("API URL: ${prefs.getString("ai_api_url", "")?.take(60)}")
                sb.appendLine("Mood: ${prefs.getBoolean("mood_enabled", false)}")
                sb.appendLine("Affinity: ${prefs.getBoolean("affinity_enabled", false)}")
                sb.appendLine("Protocol: ${com.aftglw.devapi.network.AiServiceFactory.getProtocolName()}")
                val logDir = java.io.File(ctx.cacheDir, "log"); logDir.mkdirs()
                val logFile = java.io.File(logDir, "Wisp_debug_log.txt"); logFile.writeText(sb.toString())
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", logFile))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(shareIntent, "分享调试日志"))
            } catch (_: Exception) { Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show() }
        }
        OutlinedButton(onClick = { shareLog() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("导出调试日志", fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("时区", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(0.3f))
            Spacer(Modifier.width(8.dp))
            val tzPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val tzId = tzPrefs.getString("timezone_id", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id
            val tzExpanded = remember { mutableStateOf(false) }
            Box { Text(tzId, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { tzExpanded.value = true })
                DropdownMenu(expanded = tzExpanded.value, onDismissRequest = { tzExpanded.value = false }) {
                    com.aftglw.devapi.TimeService.getAvailableTimezones().forEach { (id, label) -> DropdownMenuItem(text = { Text("$id  $label", fontSize = 13.sp) }, onClick = { com.aftglw.devapi.TimeService.setTimezone(ctx, id); tzExpanded.value = false }) }
                }
            }
        }
    }
}
