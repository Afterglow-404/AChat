package com.aftglw.devapi

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.tools.MCPBridge
import com.aftglw.devapi.tools.MCPClient
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE) }

    var urls by remember { mutableStateOf(MCPClient.fromConfig(ctx).map { it.baseUrl }) }
    var toolInfos by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }

    fun refreshAll() {
        scope.launch(Dispatchers.IO) {
            val freshUrls = MCPClient.fromConfig(ctx)
            val freshTools = mutableMapOf<String, List<String>>()
            val freshStatuses = mutableMapOf<String, String>()

            for (client in freshUrls) {
                val url = client.toString()
                freshStatuses[url] = "连接中..."
                val result = client.listTools()
                if (result.isSuccess) {
                    val tools = result.getOrThrow()
                    freshTools[url] = tools.map { "${it.name}: ${it.description.take(40)}" }
                    freshStatuses[url] = "✅ ${tools.size} 个工具"
                } else {
                    freshStatuses[url] = "❌ 连接失败"
                }
            }

            withContext(Dispatchers.Main) {
                urls = freshUrls.map { it.baseUrl }
                toolInfos = freshTools
                statuses = freshStatuses
                // 刷新 MCPBridge
                MCPBridge.refresh(ctx)
            }
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("MCP 服务管理", color = AchatTheme.colors.onSurface) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AchatTheme.colors.surface),
                actions = {
                    IconButton(onClick = { refreshAll() }) {
                        Icon(Icons.Default.Refresh, "刷新所有", tint = AchatTheme.colors.primary)
                    }
                }
            )
        },
        containerColor = AchatTheme.colors.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = AchatTheme.colors.primary
            ) { Icon(Icons.Default.Add, "添加 MCP 服务", tint = Color.White) }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).background(AchatTheme.colors.background)
                .verticalScroll(rememberScrollState())
        ) {
            if (urls.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无 MCP 服务\n点击右下角 + 添加", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
                }
            } else {
                urls.forEach { url ->
                    val status = statuses[url] ?: "未知"
                    val tools = toolInfos[url] ?: emptyList()

                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = AchatTheme.colors.surface),
                        shape = AchatTheme.shapes.card
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(url, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface, maxLines = 1)
                                    Spacer(Modifier.height(2.dp))
                                    Text(status, fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
                                }
                                if (tools.isNotEmpty()) {
                                    IconButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val client = MCPClient(url)
                                            val result = client.listTools()
                                            withContext(Dispatchers.Main) {
                                                if (result.isSuccess) {
                                                    val t = result.getOrThrow()
                                                    toolInfos = toolInfos + (url to t.map { "${it.name}: ${it.description.take(40)}" })
                                                    statuses = statuses + (url to "✅ ${t.size} 个工具")
                                                } else {
                                                    statuses = statuses + (url to "❌ 连接失败")
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Refresh, "刷新", tint = AchatTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                                    }
                                }
                                IconButton(onClick = {
                                    val newUrls = urls.toMutableList().apply { remove(url) }
                                    MCPClient.saveConfig(ctx, newUrls)
                                    urls = newUrls
                                    scope.launch(Dispatchers.IO) { MCPBridge.refresh(ctx) }
                                }) {
                                    Icon(Icons.Default.Delete, "删除", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                                }
                            }

                            if (tools.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = AchatTheme.colors.divider, thickness = 0.5.dp)
                                Spacer(Modifier.height(6.dp))
                                Text("可用工具", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.primary)
                                tools.forEach { tool ->
                                    Text(tool, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newUrl = "" },
            title = { Text("添加 MCP 服务", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newUrl, onValueChange = { newUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/mcp", fontSize = 14.sp) },
                    label = { Text("MCP Server URL") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newUrl.trim()
                    if (trimmed.isNotBlank()) {
                        val newList = urls.toMutableList().apply { add(trimmed) }
                        MCPClient.saveConfig(ctx, newList)
                        urls = newList
                        newUrl = ""
                        showAddDialog = false
                        scope.launch(Dispatchers.IO) { MCPBridge.refresh(ctx) }
                        refreshAll()
                    } else Toast.makeText(ctx, "URL 不能为空", Toast.LENGTH_SHORT).show()
                }) { Text("添加", color = AchatTheme.colors.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newUrl = "" }) { Text("取消") }
            }
        )
    }
}
