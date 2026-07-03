package com.aftglw.devapi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.WeChatApp

import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProactiveScheduler.enqueue(this)
        setContent {
            val prefs = remember { getSharedPreferences("wechat_settings", MODE_PRIVATE) }
            val skipDialog = remember { prefs.getBoolean("skip_startup_dialog", false) }
            var agreed by remember { mutableStateOf(skipDialog) }
            var neverShowAgain by rememberSaveable { mutableStateOf(false) }
            val onBoarded = remember { prefs.getBoolean("onboarding_done", false) }
            var onboardingDone by remember { mutableStateOf(onBoarded) }

            if (agreed && (onboardingDone || onBoarded)) {
                WeChatApp()
            }

            if (!agreed) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("// 注意 //", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                buildAnnotatedString {
                                    append("此版本 AChat 为 ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Pre-Alpha (Dev)") }
                                    append(" 应用\n\n")
                                    append("• AI 输出内容可能不准确\n")
                                    append("• 功能可能随时变动\n")
                                    append("• 你很有可能会看到 Bug\n")
                                    append("• “发现”页正在完善中...\n")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("• 请勿依赖本应用处理重要事务\n") }
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("• 请勿随意分享本应用\n") }
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("• 请勿轻信本应用 AI 提供内容\n") }
                                    append("• 目前无聊天记录加密功能，如果分享了应用喵...数据可能会以奇怪的方式泄漏喵...\n\n")
                                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("请...好好的使用 AChat...") }
                                },
                                fontSize = 14.sp, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = neverShowAgain, onCheckedChange = { neverShowAgain = it })
                                Spacer(Modifier.width(4.dp))
                                Text("我知道了！我不想看到这个了！", fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton({
                            if (neverShowAgain) prefs.edit().putBoolean("skip_startup_dialog", true).apply()
                            agreed = true
                        }) {
                            Text("哦好的", color = Color(0xFF07C160))
                        }
                    },
                    dismissButton = {
                        TextButton({ finish() }) { Text("这啥啊", color = Color.Gray) }
                    }
                )
            }

            if (agreed && !onboardingDone) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("欢迎来到 AChat", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "AChat 是一个模仿微信风格的 AI 聊天应用。\n\n" +
                                "你可以：\n" +
                                "• 配置 API 后和 AI 自由对话\n" +
                                "• 在「发现」页面看看趣味功能\n" +
                                "• 用人设工坊打造专属角色\n" +
                                "• 未完待续...\n\n" +
                                "你想从哪里开始？",
                                fontSize = 14.sp, lineHeight = 20.sp
                            )
                        }
                    },
                    confirmButton = {
                        TextButton({
                            prefs.edit().putBoolean("onboarding_done", true).apply()
                            onboardingDone = true
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        }) {
                            Text("设置 API", color = Color(0xFF07C160), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton({
                            prefs.edit().putBoolean("onboarding_done", true).apply()
                            onboardingDone = true
                        }) {
                            Text("自由探索", color = Color.Gray)
                        }
                    }
                )
            }
        }
    }
}
