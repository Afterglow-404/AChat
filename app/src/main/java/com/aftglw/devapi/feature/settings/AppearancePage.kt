package com.aftglw.devapi.feature.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.buildCustomTypography
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AppearancePage(
    onBack: () -> Unit,
    glassTransparent: Boolean, onGlassTransparentChange: (Boolean) -> Unit,
    physicsEnabled: Boolean, onPhysicsEnabledChange: (Boolean) -> Unit,
    customFont: Boolean, onCustomFontChange: (Boolean) -> Unit,
    showTimestamps: Boolean, onShowTimestampsChange: (Boolean) -> Unit,
    hitokotoType: String, onHitokotoTypeChange: (String) -> Unit,
    currentThemeId: String, onThemeChange: (String) -> Unit,
) {
    SubPageScaffold("界面设置", onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("主题")
        Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前主题", Modifier.weight(1f), fontSize = 16.sp, color = AchatTheme.colors.onSurface)
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    val label = when(currentThemeId) {
                        "newspaper" -> "报纸复古拼贴"
                        "washi" -> "和风纸绘"
                        else -> "默认主题"
                    }
                    Text(label, color = AchatTheme.colors.primary)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("默认主题") }, onClick = { onThemeChange("default"); expanded = false })
                    DropdownMenuItem(text = { Text("报纸复古拼贴") }, onClick = { onThemeChange("newspaper"); expanded = false })
                    DropdownMenuItem(text = { Text("和风纸绘") }, onClick = { onThemeChange("washi"); expanded = false })
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = AchatTheme.colors.divider)
        
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("视觉效果")
        ToggleRow("底栏通透效果", "更通透的底栏效果，性能有略微影响", glassTransparent, onGlassTransparentChange)
        ToggleRow("液态动效", "拖拽弹性、按压缩放、高光反馈，性能影响较大", physicsEnabled, onPhysicsEnabledChange)
        ToggleRow("一套新字体", "Noto Sans SC（中）+ Space Mono（英）", customFont, onCustomFontChange)
        ToggleRow("消息时间戳", "聊天气泡底部显示发送时间", showTimestamps, onShowTimestampsChange)

        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("实验室")
        Spacer(Modifier.height(8.dp))
        Text("一言类型（可多选）", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

        val types = listOf(
            "" to "随机", "a" to "动画", "b" to "漫画", "c" to "游戏", "d" to "文学",
            "e" to "原创", "f" to "网络", "h" to "影视", "i" to "诗词", "j" to "网易云", "k" to "哲学", "l" to "抖机灵"
        )
        val checked = remember(hitokotoType) { hitokotoType.split(",").filter { it.isNotEmpty() }.toMutableSet() }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 4.dp)) {
            types.forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = key in checked || (key == "" && checked.isEmpty()),
                        onCheckedChange = { isChecked ->
                            val newSet = checked.toMutableSet()
                            if (key == "") {
                                onHitokotoTypeChange("")
                            } else {
                                if (isChecked) newSet.add(key) else newSet.remove(key)
                                onHitokotoTypeChange(if (newSet.isEmpty()) "" else newSet.joinToString(","))
                            }
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF07C160))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 14.sp)
                }
            }
        }
    }
}

