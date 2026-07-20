package com.aftglw.devapi.feature.chat
import android.util.Log
import android.widget.Toast
import com.aftglw.devapi.core.memory.MemoryStore
import com.aftglw.devapi.core.memory.MemoryItem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆页 — 搜索和管理角色的全部记忆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPage(name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // 用于在搜索输入变化时取消上一次未完成的搜索，避免乱序覆盖
    var searchJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(name) {
        try {
            items = withContext(Dispatchers.IO) { MemoryStore.search(ctx, "", 100, "$name") }
        } catch (e: Exception) {
            Log.e("ChatMemoryPage", "initial search failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "加载记忆失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("🧠 全部记忆", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding())
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        OutlinedTextField(
            value = query, onValueChange = { q ->
                query = q
                searchJob?.cancel()
                searchJob = scope.launch(Dispatchers.IO) {
                    try {
                        val results = MemoryStore.search(ctx, q.ifBlank { "" }, 100, "$name")
                        withContext(Dispatchers.Main) { items = results }
                    } catch (e: Exception) {
                        Log.e("ChatMemoryPage", "search failed", e)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text("搜索记忆...", fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider, focusedContainerColor = AchatTheme.colors.surface, unfocusedContainerColor = AchatTheme.colors.surface)
        )
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无记忆", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(items, key = { i, _ -> i }) { _, m ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AchatTheme.colors.surface), shape = AchatTheme.shapes.card) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.topic.take(12), fontSize = 10.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
                            Text(m.text, fontSize = 13.sp, color = AchatTheme.colors.onSurface, maxLines = 3)
                        }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    MemoryStore.deleteByText(m.text, "$name")
                                    val results = MemoryStore.search(ctx, query.ifBlank { "" }, 100, "$name")
                                    withContext(Dispatchers.Main) { items = results }
                                } catch (e: Exception) {
                                    Log.e("ChatMemoryPage", "delete failed", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "删除", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
