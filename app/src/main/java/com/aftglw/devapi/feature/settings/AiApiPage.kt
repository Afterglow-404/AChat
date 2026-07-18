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
fun AiApiPage(
    onBack: () -> Unit,
    apiUrl: String, onApiUrlChange: (String) -> Unit,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    model: String, onModelChange: (String) -> Unit,
    mockReplies: String, onMockRepliesChange: (String) -> Unit,
    mockDelay: String, onMockDelayChange: (String) -> Unit,
    longContext: Boolean, onLongContextChange: (Boolean) -> Unit,
    aiTemperature: String, onAiTemperatureChange: (String) -> Unit,
    aiTopP: String, onAiTopPChange: (String) -> Unit,
    aiMaxTokens: String, onAiMaxTokensChange: (String) -> Unit,
    aiSeed: String, onAiSeedChange: (String) -> Unit,
    aiFrequencyPenalty: String, onAiFrequencyPenaltyChange: (String) -> Unit,
    aiPresencePenalty: String, onAiPresencePenaltyChange: (String) -> Unit,
    aiStopSequences: String, onAiStopSequencesChange: (String) -> Unit,
    aiResponseFormat: String, onAiResponseFormatChange: (String) -> Unit,
    aiClaudeThinking: Boolean, onAiClaudeThinkingChange: (Boolean) -> Unit,
    aiDeepSeekThinking: Boolean, onAiDeepSeekThinkingChange: (Boolean) -> Unit,
    aiProtocol: String, onAiProtocolChange: (String) -> Unit
) {
    SubPageScaffold("AI 接口", onBack) {
        Spacer(Modifier.height(8.dp))
        TextFieldRow("API 地址", "https://api.114514.com/v1", apiUrl, onApiUrlChange)
        PasswordRow("API Key", "sk-1145141919810", apiKey, onApiKeyChange)
        TextFieldRow("模型名", "gpt-114514", model, onModelChange)
        ProtocolRow("协议", aiProtocol, onAiProtocolChange)
        SettingsMainHeader("基础参数")
        TextFieldRow("温度 (temperature)", "留空使用默认, 如 0.7", aiTemperature, onAiTemperatureChange)
        TextFieldRow("采样 (top_p)", "留空使用默认, 如 0.9", aiTopP, onAiTopPChange)
        TextFieldRow("最大 tokens", "留空使用默认, 如 4096", aiMaxTokens, onAiMaxTokensChange)
        TextFieldRow("随机种子 (seed)", "留空随机, >0 整数可复现", aiSeed, onAiSeedChange)
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("高级参数")
        TextFieldRow("频率惩罚 (frequency_penalty)", "留空使用默认, -2.0 ~ 2.0", aiFrequencyPenalty, onAiFrequencyPenaltyChange)
        TextFieldRow("存在惩罚 (presence_penalty)", "留空使用默认, -2.0 ~ 2.0", aiPresencePenalty, onAiPresencePenaltyChange)
        TextFieldRow("停止序列 (stop)", "逗号分隔, 如 再见,bye", aiStopSequences, onAiStopSequencesChange)
        TextFieldRow("输出格式", "留空=text, 或填 json_object", aiResponseFormat, onAiResponseFormatChange)
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("特殊功能")
        ToggleRow("Claude Extended Thinking", "仅 Claude 模型, 开启后温度/采样会忽略", aiClaudeThinking, onAiClaudeThinkingChange)
        ToggleRow("DeepSeek Thinking", "DeepSeek V4 推理模式, 开启后温度/采样会忽略", aiDeepSeekThinking, onAiDeepSeekThinkingChange)
        ToggleRow("长上下文模式", "DeepSeek/GPT 等长上下文模型无需重注入提示，关闭可节省小模型 tokens", longContext, onLongContextChange)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingsMainHeader("离线回复（未接入 API 时生效）")
        TextFieldRow("回复词库", "用 | 分隔，如：好的|行|不行|...", mockReplies, onMockRepliesChange)
        TextFieldRow("延迟(ms)", "默认800", mockDelay, onMockDelayChange)
    }
}

@Composable
private fun ProtocolRow(label: String, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "auto" to "自动（URL 识别）",
        "openai" to "OpenAI 兼容",
        "claude" to "Anthropic Claude",
        "local" to "本地模型"
    )
    val currentLabel = options.find { it.first == value }?.second ?: value

    Column {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(currentLabel, Modifier.weight(1f), textAlign = TextAlign.Start, color = AchatTheme.colors.onSurface)
                Text("▼", fontSize = 10.sp, color = Color.Gray)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (k, v) ->
                    DropdownMenuItem(
                        text = { Text(v, fontWeight = if (k == value) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onChange(k); expanded = false }
                    )
                }
            }
        }
    }
}
