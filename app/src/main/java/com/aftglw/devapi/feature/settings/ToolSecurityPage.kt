package com.aftglw.devapi.feature.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.tools.RiskLevel
import com.aftglw.devapi.tools.ToolRegistry
import com.aftglw.devapi.tools.ToolWhitelist
import com.aftglw.devapi.ui.theme.AchatTheme

/**
 * 工具安全设置页。
 *
 * 功能：
 * - 列出所有已注册工具，显示风险等级与说明
 * - 每个工具可单独启用/禁用（白名单）
 * - 顶部说明当前安全策略：高风险工具会在执行前弹窗确认
 *
 * 工具列表在进入页面时刷新一次，确保最新注册的工具都能被管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSecurityPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 进入页面时确保 ToolRegistry 已初始化
    LaunchedEffect(Unit) {
        try {
            ToolRegistry.init(ctx)
        } catch (e: Exception) {
            Log.e("ToolSecurityPage", "ToolRegistry.init failed", e)
        }
    }
    val tools = remember {
        try { ToolRegistry.getAll().sortedBy { it.riskLevel.ordinal } } catch (e: Exception) { Log.e("ToolSecurityPage", "getAll failed", e); emptyList() }
    }
    val disabledSet = remember {
        mutableStateOf(
            try { ToolWhitelist.disabledNames(ctx) } catch (e: Exception) { Log.e("ToolSecurityPage", "disabledNames failed", e); emptySet() }
        )
    }

    SubPageScaffold(title = "工具安全", onBack = onBack) {
        // 说明卡片
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = AchatTheme.colors.surface
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = AchatTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("工具调用安全策略", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            appendLine("• 低风险：自动执行（如时间、电量、计算器）")
                            appendLine("• 中风险：自动执行但记录（如位置、通知、网络搜索）")
                            appendLine("• 高风险：执行前弹窗确认（如发消息、运行脚本）")
                            appendLine("• 群聊场景：高风险工具自动拒绝")
                            appendLine("• 关闭某工具后，AI 调用将直接返回失败")
                        },
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.6f),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // 工具列表
        SettingsMainHeader("已注册工具（${tools.size}）")
        if (tools.isEmpty()) {
            Text(
                "尚未注册任何工具",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                fontSize = 13.sp,
                color = Color.Gray
            )
        } else {
            tools.forEach { tool ->
                val isDisabled = disabledSet.value.contains(tool.name)
                val riskColor = when (tool.riskLevel) {
                    RiskLevel.HIGH -> Color(0xFFD32F2F)
                    RiskLevel.MEDIUM -> Color(0xFFEF6C00)
                    RiskLevel.LOW -> Color(0xFF388E3C)
                }
                val riskText = when (tool.riskLevel) {
                    RiskLevel.HIGH -> "高"
                    RiskLevel.MEDIUM -> "中"
                    RiskLevel.LOW -> "低"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = AchatTheme.colors.surface
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 风险等级标签
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = riskColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                riskText,
                                color = riskColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
                            Text(tool.description, fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f), maxLines = 2)
                        }
                        Switch(
                            checked = !isDisabled,
                            onCheckedChange = { enabled ->
                                try {
                                    ToolWhitelist.setDisabled(ctx, tool.name, !enabled)
                                    disabledSet.value = ToolWhitelist.disabledNames(ctx)
                                } catch (e: Exception) {
                                    Log.e("ToolSecurityPage", "setDisabled failed for ${tool.name}", e)
                                    Toast.makeText(ctx, "设置失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AchatTheme.colors.primary,
                                uncheckedThumbColor = Color.Gray
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
