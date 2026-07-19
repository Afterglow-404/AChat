package com.aftglw.devapi.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.storage.ChatDataManager
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据管理页：导出全部聊天数据 / 清空全部数据。
 *
 * - 导出：JSON 写入用户选择的位置（SAF CreateDocument）
 * - 清空：弹出二次确认对话框，确认后清除 Room + 关联 SharedPreferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatCount by remember { mutableStateOf(0) }
    var groupCount by remember { mutableStateOf(0) }
    var msgCount by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    // 统计当前数据量
    fun refresh() {
        scope.launch {
            val (c, g, m) = withContext(Dispatchers.IO) {
                val db = AppDatabase.get(ctx)
                Triple(
                    db.chatDao().getAll().size,
                    db.groupDao().getAll().size,
                    db.chatDao().getAll().sumOf { db.messageDao().getMessages(it.id, false).size } +
                    db.groupDao().getAll().sumOf { db.messageDao().getMessages(it.id, true).size }
                )
            }
            chatCount = c; groupCount = g; msgCount = m
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // SAF 创建文档启动器
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val json = ChatDataManager.exportAll(ctx)
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    false
                }
            }
            exporting = false
            Toast.makeText(ctx, if (ok) "导出成功" else "导出失败：${"未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    SubPageScaffold(title = "数据管理", onBack = onBack) {
        // 当前数据统计
        SettingsMainHeader("当前数据")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                StatRow("单聊会话", "$chatCount 个")
                StatRow("群聊会话", "$groupCount 个")
                StatRow("消息总数", "$msgCount 条")
            }
        }

        // 导出
        SettingsMainHeader("导出数据")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = AchatTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (exporting) "正在导出…" else "导出全部聊天数据",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AchatTheme.colors.onSurface
                    )
                    Text(
                        "JSON 格式，包含所有单聊/群聊/消息",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                Button(
                    onClick = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("wisp_export_$ts.json")
                    },
                    enabled = !exporting
                ) { Text("导出") }
            }
        }

        // 清空（危险操作）
        SettingsMainHeader("危险操作")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (clearing) "正在清空…" else "清空全部聊天数据",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFD32F2F)
                    )
                    Text(
                        "删除所有单聊、群聊、消息和世界书，不可恢复",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                Button(
                    onClick = { showClearConfirm = true },
                    enabled = !clearing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("清空") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // 清空确认对话框
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空全部数据？", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("此操作将永久删除：", fontSize = 13.sp)
                    Text("• $chatCount 个单聊及其所有消息", fontSize = 12.sp, color = Color.Gray)
                    Text("• $groupCount 个群聊及其所有消息", fontSize = 12.sp, color = Color.Gray)
                    Text("• 所有世界书条目", fontSize = 12.sp, color = Color.Gray)
                    Text("• 主动消息/亲密度等聊天状态", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text("建议先导出数据备份。", fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        clearing = true
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { ChatDataManager.clearAll(ctx) }
                            clearing = false
                            Toast.makeText(ctx, "已清空 $n 个聊天", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = AchatTheme.colors.onSurface)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f))
    }
}
