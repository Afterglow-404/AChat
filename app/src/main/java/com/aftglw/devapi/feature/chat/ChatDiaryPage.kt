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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日记页 — 查看和管理按日期归档的对话日记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryPage(name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var diaries by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(name) {
        try {
            diaries = withContext(Dispatchers.IO) { MemoryStore.search(ctx, "日记", 50, "diary:$name") }
        } catch (e: Exception) {
            Log.e("ChatDiaryPage", "load diaries failed", e)
            Toast.makeText(ctx, "日记加载失败", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("📖 日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) } },
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
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AchatTheme.colors.surface), shape = AchatTheme.shapes.card) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dateLabel, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), modifier = Modifier.width(64.dp))
                        Text(content, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp, color = AchatTheme.colors.onSurface, maxLines = 3)
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    MemoryStore.deleteByText(d.text, "diary:$name")
                                    val refreshed = MemoryStore.search(ctx, "日记", 50, "diary:$name")
                                    withContext(Dispatchers.Main) { diaries = refreshed }
                                } catch (e: Exception) {
                                    Log.e("ChatDiaryPage", "delete diary failed", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "删除", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
