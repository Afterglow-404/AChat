package com.aftglw.devapi.feature.chat
import android.util.Log
import android.widget.Toast
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.memory.MemoryItem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日记页 — 查看和管理按日期归档的对话日记。
 * - 点击列表项查看全文
 * - 右侧删除按钮单条删除
 * - 顶部菜单「清空全部」一键清空
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiaryPage(name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var diaries by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(name) {
        try {
            // 改用 listRecentByTopic：按 timestamp 倒序确定性排序，避免 embedding 检索的不确定性
            diaries = withContext(Dispatchers.IO) { MemoryStore.listRecentByTopic("diary:$name", 50) }
        } catch (e: Exception) {
            Log.e("ChatDiaryPage", "load diaries failed", e)
            Toast.makeText(ctx, "日记加载失败", Toast.LENGTH_SHORT).show()
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var viewingDiary by remember { mutableStateOf<MemoryItem?>(null) }

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("📖 日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) } },
            actions = {
                if (diaries.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, "更多", tint = AchatTheme.colors.onSurface)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("清空全部日记") },
                                onClick = {
                                    menuExpanded = false
                                    confirmClearAll = true
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding())
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        if (diaries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日记", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(diaries, key = { i, _ -> i }) { _, d ->
                val dateLabel = d.text.take(10)
                val content = d.text.drop(11)
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { viewingDiary = d },
                        onLongClick = { viewingDiary = d }
                    ),
                    colors = CardDefaults.cardColors(containerColor = AchatTheme.colors.surface),
                    shape = AchatTheme.shapes.card
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dateLabel, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), modifier = Modifier.width(64.dp))
                        Text(content, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp, color = AchatTheme.colors.onSurface, maxLines = 3)
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    MemoryStore.deleteByText(d.text, "diary:$name")
                                    val refreshed = MemoryStore.listRecentByTopic("diary:$name", 50)
                                    withContext(Dispatchers.Main) { diaries = refreshed }
                                } catch (e: Exception) {
                                    Log.e("ChatDiaryPage", "delete diary failed", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, "删除", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // 清空确认
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部日记？") },
            text = { Text("将删除当前角色（$name）的全部 ${diaries.size} 条日记，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch(Dispatchers.IO) {
                        try {
                            MemoryStore.deleteByTopic("diary:$name")
                            val refreshed = MemoryStore.listRecentByTopic("diary:$name", 50)
                            withContext(Dispatchers.Main) {
                                diaries = refreshed
                                Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("ChatDiaryPage", "clear all failed", e)
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

    // 全文查看
    viewingDiary?.let { d ->
        AlertDialog(
            onDismissRequest = { viewingDiary = null },
            title = { Text(d.text.take(10), fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f)) },
            text = {
                Text(
                    d.text.drop(11).ifBlank { d.text },
                    fontSize = 14.sp,
                    color = AchatTheme.colors.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { viewingDiary = null }) { Text("关闭") } }
        )
    }
}
