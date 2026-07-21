package com.aftglw.devapi.feature.chat

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.worldbook.WorldbookEntry
import com.aftglw.devapi.core.worldbook.WorldbookStore
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 世界书页面 — 列表 + 内联编辑对话框 + 导入/导出/批量操作。
 * 每个条目可设：关键词、内容、优先级、常驻、启用。
 * 匹配上限 8 条（在 WorldbookStore.matchEntries 中限制），优先级数字越大越先注入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldbookPage(
    chatName: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<WorldbookEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(chatName) {
        try {
            entries = withContext(Dispatchers.IO) { WorldbookStore.load(ctx, chatName) }
        } catch (e: Exception) {
            Log.e("WorldbookPage", "load failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "加载世界书失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var editing by remember { mutableStateOf<WorldbookEntry?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    // 导出用：SAF CreateDocument，启动时设为待导出 JSON；null 表示无待处理
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "已导出", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WorldbookPage", "export write failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "导出失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // 导入用：SAF OpenDocument，读取 JSON 后调用 importJson
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw java.io.IOException("无法读取文件")
                val refreshed = WorldbookStore.importJson(ctx, chatName, json, merge = false)
                withContext(Dispatchers.Main) {
                    entries = refreshed
                    Toast.makeText(ctx, "已导入 ${refreshed.size} 条", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WorldbookPage", "import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "导入失败：${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CenterAlignedTopAppBar(
                title = { Text("世界书", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, "更多", tint = AchatTheme.colors.onSurface)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("导出 JSON") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val json = WorldbookStore.exportJson(ctx, chatName)
                                            withContext(Dispatchers.Main) {
                                                pendingExportJson = json
                                                exportLauncher.launch("worldbook_$chatName.json")
                                            }
                                        } catch (e: Exception) {
                                            Log.e("WorldbookPage", "export build failed", e)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "导出失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入 JSON（覆盖）") },
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("全部启用") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val refreshed = WorldbookStore.setAllEnabled(ctx, chatName, true)
                                            withContext(Dispatchers.Main) {
                                                entries = refreshed
                                                Toast.makeText(ctx, "已全部启用", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("WorldbookPage", "enable all failed", e)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "操作失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("全部禁用") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val refreshed = WorldbookStore.setAllEnabled(ctx, chatName, false)
                                            withContext(Dispatchers.Main) {
                                                entries = refreshed
                                                Toast.makeText(ctx, "已全部禁用", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("WorldbookPage", "disable all failed", e)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "操作失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清空全部") },
                                onClick = {
                                    menuExpanded = false
                                    confirmClearAll = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding()
            )
            HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无世界书条目\n点击右下角 + 添加",
                            fontSize = 14.sp,
                            color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // 数量提示：匹配上限 8 条
                    if (entries.size > 8) {
                        Text(
                            "⚠ 共 ${entries.size} 条，匹配上限为 8 条；高优先级条目将优先注入",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        entries.forEach { entry ->
                            WorldbookEntryCard(
                                entry = entry,
                                onClick = { editing = entry },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val refreshed = WorldbookStore.delete(ctx, chatName, entry.id)
                                            withContext(Dispatchers.Main) {
                                                entries = refreshed
                                                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("WorldbookPage", "delete failed", e)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "删除失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // FAB 添加新条目
        FloatingActionButton(
            onClick = { creatingNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp),
            containerColor = AchatTheme.colors.primary
        ) {
            Icon(Icons.Filled.Add, "添加", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }

    // 清空确认
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部世界书？") },
            text = { Text("将删除当前角色（$chatName）的全部 ${entries.size} 条世界书条目，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch(Dispatchers.IO) {
                        try {
                            val refreshed = WorldbookStore.deleteAll(ctx, chatName)
                            withContext(Dispatchers.Main) {
                                entries = refreshed
                                Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("WorldbookPage", "clear all failed", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "清空失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("清空", color = Color(0xFFE53935)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } }
        )
    }

    // 编辑现有条目
    editing?.let { e ->
        WorldbookEditDialog(
            entry = e,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val refreshed = WorldbookStore.update(ctx, chatName, updated)
                        withContext(Dispatchers.Main) {
                            entries = refreshed
                            editing = null
                            Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("WorldbookPage", "update failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "保存失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    // 新建条目
    if (creatingNew) {
        WorldbookEditDialog(
            entry = WorldbookEntry(id = 0L, keywords = emptyList(), content = ""),
            onDismiss = { creatingNew = false },
            onSave = { newEntry ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val refreshed = WorldbookStore.add(ctx, chatName, newEntry)
                        withContext(Dispatchers.Main) {
                            entries = refreshed
                            creatingNew = false
                            Toast.makeText(ctx, "已添加", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("WorldbookPage", "add failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "添加失败：${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun WorldbookEntryCard(
    entry: WorldbookEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = AchatTheme.colors.surface,
        shape = AchatTheme.shapes.card,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 关键词 chips
                if (entry.keywords.isEmpty()) {
                    Text(
                        "(无关键词)",
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        entry.keywords.take(3).forEach { kw ->
                            Surface(
                                color = AchatTheme.colors.primary.copy(alpha = 0.15f),
                                shape = CircleShape
                            ) {
                                Text(
                                    kw,
                                    fontSize = 11.sp,
                                    color = AchatTheme.colors.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (entry.keywords.size > 3) {
                            Text(
                                "+${entry.keywords.size - 3}",
                                fontSize = 11.sp,
                                color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                // 状态标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.constant) {
                        Text(
                            "常驻",
                            fontSize = 10.sp,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    if (!entry.enabled) {
                        Text(
                            "已禁用",
                            fontSize = 10.sp,
                            color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    if (entry.priority != 0) {
                        Text(
                            "P${entry.priority}",
                            fontSize = 10.sp,
                            color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            "删除",
                            tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.content.ifEmpty { "(无内容)" },
                fontSize = 13.sp,
                color = AchatTheme.colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WorldbookEditDialog(
    entry: WorldbookEntry,
    onDismiss: () -> Unit,
    onSave: (WorldbookEntry) -> Unit
) {
    var keywordsText by remember { mutableStateOf(entry.keywords.joinToString(",")) }
    var content by remember { mutableStateOf(entry.content) }
    var priority by remember { mutableIntStateOf(entry.priority) }
    var constant by remember { mutableStateOf(entry.constant) }
    var enabled by remember { mutableStateOf(entry.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.id == 0L) "新建世界书条目" else "编辑世界书条目") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = keywordsText,
                    onValueChange = { keywordsText = it },
                    label = { Text("关键词（逗号分隔）", fontSize = 12.sp) },
                    placeholder = { Text("魔法, 咒语, 魔兽", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("任一关键词命中即注入", fontSize = 10.sp) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容", fontSize = 12.sp) },
                    placeholder = { Text("该世界的设定说明...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("优先级: $priority", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt() },
                    valueRange = -10f..10f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = constant, onCheckedChange = { constant = it })
                    Text("常驻条目（无需关键词触发）", fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用", fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val kws = keywordsText.split(",", "，", "、")
                    .map { it.trim() }.filter { it.isNotEmpty() }
                onSave(entry.copy(
                    keywords = kws,
                    content = content,
                    priority = priority,
                    constant = constant,
                    enabled = enabled
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
