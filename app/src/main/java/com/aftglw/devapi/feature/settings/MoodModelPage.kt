package com.aftglw.devapi.feature.settings

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.mood.MoodModel
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ONNX 情绪识别模型管理页。
 *
 * 由于 release APK 不再打包 model_quant.onnx（约 28MB），
 * 用户需要通过本页导入模型文件后才能启用本地情绪识别。
 *
 * 功能：
 * - 显示当前模型加载状态
 * - SAF 选择 .onnx 文件导入到 filesDir/models/
 * - 列出设备上的所有 .onnx 文件，可选用/删除
 * - 重新加载按钮（导入新模型后立即生效）
 *
 * 未导入模型时，MoodDetector 会自动走 LLM 兜底，不影响聊天功能。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodModelPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedName by remember { mutableStateOf<String?>(MoodModel.getSelectedModelName(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(MoodModel.isLoaded) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun refresh() {
        scope.launch {
            try {
                models = withContext(Dispatchers.IO) { MoodModel.listAvailableModels(ctx) }
                loaded = MoodModel.isLoaded
            } catch (e: Exception) {
                Log.e("MoodModelPage", "refresh failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "刷新模型列表失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val pickOnnx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { MoodModel.importModelFromUri(ctx, uri) }
                importing = false
                if (imported != null) {
                    Toast.makeText(ctx, "已导入：${imported.name}", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(ctx, "导入失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MoodModelPage", "import failed", e)
                importing = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "导入异常：${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    SubPageScaffold(title = "情绪模型", onBack = onBack) {
        // 当前状态
        SettingsMainHeader("模型状态")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (loaded) AchatTheme.colors.primary else Color.Gray
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (loaded) "已加载" else "未加载（走 LLM 兜底）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AchatTheme.colors.onSurface
                    )
                    Text(
                        text = "活动模型：${selectedName ?: "自动取第一个 .onnx"}",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                MoodModel.close()
                                MoodModel.load(ctx)
                            }
                            loaded = MoodModel.isLoaded
                            Toast.makeText(
                                ctx,
                                if (loaded) "已加载模型" else "加载失败（未找到模型文件）",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Log.e("MoodModelPage", "reload failed", e)
                            loaded = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "重载异常：${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新加载", tint = AchatTheme.colors.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("重载", color = AchatTheme.colors.primary)
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
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, tint = AchatTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (importing) "正在导入…" else "从文件选择 .onnx 导入",
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
                    onClick = { pickOnnx.launch(arrayOf("*/*")) },
                    enabled = !importing
                ) { Text("选择文件", color = AchatTheme.colors.primary) }
            }
        }

        // 设备上的模型文件
        SettingsMainHeader("设备上的模型（${models.size}）")
        if (models.isEmpty()) {
            Text(
                "未在 filesDir/models/ 或外部存储 models/ 下找到 .onnx 文件",
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
                            Text(
                                "${formatSize(file.length())} · ${dateFmt.format(Date(file.lastModified()))}",
                                fontSize = 12.sp,
                                color = AchatTheme.colors.onSurface.copy(alpha = 0.5f)
                            )
                            Text(
                                file.absolutePath,
                                fontSize = 11.sp,
                                color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = {
                            MoodModel.setSelectedModelName(ctx, file.name)
                            selectedName = file.name
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        MoodModel.close()
                                        MoodModel.load(ctx)
                                    }
                                    loaded = MoodModel.isLoaded
                                    Toast.makeText(
                                        ctx,
                                        if (loaded) "已切换并加载：${file.name}" else "切换失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Log.e("MoodModelPage", "switch load failed", e)
                                    loaded = false
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "切换异常：${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) {
                            Text(
                                if (file.name == selectedName) "当前" else "选用",
                                color = if (file.name == selectedName) AchatTheme.colors.primary else AchatTheme.colors.onSurface
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val ok = withContext(Dispatchers.IO) { file.delete() }
                                    if (ok) {
                                        if (selectedName == file.name) {
                                            MoodModel.setSelectedModelName(ctx, null)
                                            selectedName = null
                                        }
                                        Toast.makeText(ctx, "已删除 ${file.name}", Toast.LENGTH_SHORT).show()
                                        refresh()
                                    } else {
                                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e("MoodModelPage", "delete failed", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "删除异常：${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFD32F2F)) }
                    }
                }
            }
        }

        // 说明
        SettingsMainHeader("说明")
        Text(
            buildString {
                appendLine("• 情绪模型用于本地识别消息情绪，未加载时自动走 LLM 兜底，不影响聊天功能")
                appendLine("• 模型已从 release APK 中剥离以减小安装体积（约 28MB）")
                appendLine("• 点击上方「选择文件」从系统文件应用导入 .onnx 模型")
                appendLine("• 也可通过 adb push 传到：")
                appendLine("  /data/data/com.aftglw.devapi/files/models/")
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
