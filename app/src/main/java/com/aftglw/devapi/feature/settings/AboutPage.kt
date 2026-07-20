package com.aftglw.devapi.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutPage(onBack: () -> Unit) {
    SubPageScaffold("关于", onBack) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Wisp", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(4.dp))
            Text("Preview Version", fontSize = 13.sp, color = Color.Gray)
            Text("Pre-Alpha (Dev)", fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            Text("WeChat 的拙劣模仿品，你能在「Wisp」中与 AI 模型聊天。", fontSize = 12.sp, color = Color(0xFF888888))
            Text("支持 OpenAI API or Anthropic API 对话，支持自定义对话人设。", fontSize = 12.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(8.dp))
            Text("💗 爱来自 AFTGLW 与 Deepseek-Reasonix 💗", fontSize = 11.sp, color = Color(0xFFBBBBBB))
        }
    }
}
