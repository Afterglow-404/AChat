package com.aftglw.devapi.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.ai.LlamaEngine
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地 GGUF 模型管理页。
 *
 * 功能：
 * - 列出设备上的 .gguf 文件（内部 + 外部存储）
 * - 设置/切换活动模型
 * - 删除模型文件
 * - 展示推荐模型与下载链接（用户自行下载后放入 filesDir/models/）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedName by remember { mutableStateOf<String?>(LlamaEngine.getSelectedModelName(ctx)) }
    var importing by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 加载模型列表
    fun refresh() {
        scope.launch {
            models = withContext(Dispatchers.IO) { LlamaEngine.listAvailableModels(ctx) }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // SAF 文件选择器：从系统文件应用选择 .gguf 文件导入
    val pickGguf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val imported = withContext(Dispatchers.IO) { LlamaEngine.importModelFromUri(ctx, uri) }
            importing = false
            if (imported != null) {
                Toast.makeText(ctx, "已导入：${imported.name}", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(ctx, "导入失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SubPageScaffold(title = "本地模型", onBack = onBack) {
        // 当前活动模型
        SettingsMainHeader("当前活动模型")
        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = AchatTheme.colors.surface) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = AchatTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selectedName ?: "未选择（自动取第一个）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AchatTheme.colors.onSurface
                    )
                    Text(
                        text = if (selectedName != null) "已显式选择" else "未设置时按目录顺序自动选择",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                if (selectedName != null) {
                    TextButton(onClick = {
                        LlamaEngine.setSelectedModelName(ctx, null)
                        selectedName = null
                        Toast.makeText(ctx, "已清除选择", Toast.LENGTH_SHORT).show()
                    }) { Text("清除", color = Color.Gray) }
                }
            }
        }

        // 导入入口
        SettingsMainHeader("导入模型")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, tint = AchatTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (importing) "正在导入…" else "从文件选择 .gguf 导入",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AchatTheme.colors.onSurface
                    )
                    Text(
                        text = "文件会被复制到内部存储 models/ 目录",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                TextButton(
                    onClick = { pickGguf.launch(arrayOf("*/*")) },
                    enabled = !importing
                ) { Text("选择文件", color = AchatTheme.colors.primary) }
            }
        }

        // 设备上的模型文件
        SettingsMainHeader("设备上的模型（${models.size}）")
        if (models.isEmpty()) {
            Text(
                "未在 filesDir/models/ 或外部存储 models/ 下找到 .gguf 文件",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                fontSize = 13.sp,
                color = Color.Gray
            )
        } else {
            models.forEach { file ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = AchatTheme.colors.surface
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
                            Text("${formatSize(file.length())} · ${dateFmt.format(Date(file.lastModified()))}",
                                fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                            Text(file.absolutePath, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), maxLines = 1)
                        }
                        TextButton(onClick = {
                            LlamaEngine.setSelectedModelName(ctx, file.name)
                            selectedName = file.name
                            com.aftglw.devapi.network.AiServiceFactory.unloadLocal()
                            Toast.makeText(ctx, "已设为活动模型：${file.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Text(if (file.name == selectedName) "当前" else "选用",
                                color = if (file.name == selectedName) AchatTheme.colors.primary else AchatTheme.colors.onSurface)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { file.delete() }
                                if (ok) {
                                    if (selectedName == file.name) {
                                        LlamaEngine.setSelectedModelName(ctx, null)
                                        selectedName = null
                                    }
                                    Toast.makeText(ctx, "已删除 ${file.name}", Toast.LENGTH_SHORT).show()
                                    refresh()
                                } else {
                                    Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFD32F2F)) }
                    }
                }
            }
        }

        // 推荐模型
        SettingsMainHeader("推荐模型（自行下载后放入 filesDir/models/）")
        LlamaEngine.RECOMMENDED_MODELS.forEach { m ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = AchatTheme.colors.surface
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
                        Text("${m.size} · ${m.note}", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                        Text("文件名：${m.fileName}", fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), maxLines = 1)
                    }
                    IconButton(onClick = {
                        // 打开浏览器到下载页
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(m.downloadUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(intent) }
                        catch (_: Exception) {
                            // 浏览器不可用时复制到剪贴板
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("model_url", m.downloadUrl))
                            Toast.makeText(ctx, "已复制下载链接", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Filled.Download, contentDescription = "下载", tint = AchatTheme.colors.primary) }
                }
            }
        }

        // 使用说明
        SettingsMainHeader("使用说明")
        Text(
            buildString {
                appendLine("方式一：点击上方“导入模型”→“选择文件”，从系统文件应用选择 .gguf 文件（推荐）")
                appendLine("方式二：通过 adb push 传输到内部存储：")
                appendLine("  adb push <model.gguf> /data/data/com.aftglw.devapi/files/models/")
                appendLine("方式三：在外部存储建立 models/ 目录后放入 .gguf（应用会自动扫描）")
                appendLine()
                appendLine("导入后点击“选用”设为活动模型，再到 AI 接口页选择协议为“本地模型”")
            },
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = Color.Gray,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 把字节数格式化为人类可读字符串 */
private fun formatSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIdx = 0
    while (size >= 1024 && unitIdx < units.size - 1) {
        size /= 1024
        unitIdx++
    }
    return if (unitIdx == 0) "${bytes}B"
    else String.format("%.1f%s", size, units[unitIdx])
}
