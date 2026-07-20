package com.aftglw.devapi.feature.tools

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.tools.ToolScanner
import com.aftglw.devapi.feature.settings.SubPageScaffold
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工具管理页 — 查看已安装的动态工具包、安装/卸载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolMarketPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var packages by remember {
        mutableStateOf(
            try { ToolScanner.getInstalledPackages(ctx) } catch (e: Exception) { Log.e("ToolMarketPage", "getInstalledPackages failed", e); emptyList() }
        )
    }
    var showInstallDialog by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }

    SubPageScaffold("工具管理", onBack) {
        Spacer(Modifier.height(8.dp))

        // 已安装工具包列表
        Text("已安装的工具包 (${packages.size})",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

        if (packages.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("暂无工具包\n点击下方按钮安装", fontSize = 13.sp, color = Color.Gray)
            }
        } else {
            packages.forEach { pkg ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = AchatTheme.colors.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pkg, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
                            Text("点击删除可卸载", fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
                        }
                        IconButton(onClick = {
                            try {
                                ToolScanner.uninstall(ctx, pkg)
                                packages = ToolScanner.getInstalledPackages(ctx)
                                Toast.makeText(ctx, "已卸载: $pkg", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("ToolMarketPage", "uninstall failed for $pkg", e)
                                Toast.makeText(ctx, "卸载失败", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Delete, "卸载", tint = Color(0xFFE53935))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { showInstallDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.primary)
        ) { Text("安装工具包", fontSize = 14.sp) }

        // 内置工具说明
        Spacer(Modifier.height(16.dp))
        Text("📦 内置工具包",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Text("network_tools — check_ip, check_http\nutility_tools — exchange_rate, generate_uuid, encode_base64\n\n工具包文件放在 assets/tools/ 或 filesDir/tools/ 目录下",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            fontSize = 11.sp, color = Color.Gray)
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false; installUrl = "" },
            title = { Text("安装工具包", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("输入 .wsptool 文件的 JSON 内容或粘贴 URL（开发中）", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = installUrl, onValueChange = { installUrl = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        placeholder = { Text("{\n  \"name\": \"my_tools\",\n  \"tools\": [...]\n}", fontSize = 12.sp) },
                        maxLines = 10, singleLine = false
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val content = installUrl.trim()
                    if (content.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val result = ToolScanner.install(ctx, content)
                            withContext(Dispatchers.Main) {
                                if (result.isSuccess) {
                                    packages = ToolScanner.getInstalledPackages(ctx)
                                    Toast.makeText(ctx, result.getOrThrow(), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, result.exceptionOrNull()?.message ?: "安装失败", Toast.LENGTH_SHORT).show()
                                }
                                showInstallDialog = false
                                installUrl = ""
                            }
                        }
                    }
                }) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false; installUrl = "" }) { Text("取消") }
            }
        )
    }
}
