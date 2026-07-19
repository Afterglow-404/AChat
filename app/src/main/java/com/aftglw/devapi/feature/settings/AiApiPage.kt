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
import com.aftglw.devapi.core.security.SecureKeyStore
import com.aftglw.devapi.network.HttpClient
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.buildCustomTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        ConnectionTestRow(apiUrl = apiUrl, apiKey = apiKey, model = model, protocol = aiProtocol)
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
    // “local” 本地模型协议暂未真正集成 llama.cpp，先从可选项中隐藏，避免误用
    val options = listOf(
        "auto" to "自动（URL 识别）",
        "openai" to "OpenAI 兼容",
        "claude" to "Anthropic Claude"
    )
    val currentLabel = options.find { it.first == value }?.second
        ?: if (value == "local") "本地模型（已禁用）" else value

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

/**
 * 接口连接测试按钮：发送一个最小的 chat completions 请求，校验 URL/Key/Model 是否可用。
 *
 * 协议判定逻辑与 AiServiceFactory 一致：
 * - claude → POST {base}/messages，header x-api-key
 * - openai / 其他 → POST {base}/chat/completions，header Authorization Bearer
 *
 * 结果以 Toast + 行内文字呈现：成功显示模型名与延迟；失败显示 HTTP 状态与首段错误体。
 */
@Composable
private fun ConnectionTestRow(
    apiUrl: String,
    apiKey: String,
    model: String,
    protocol: String
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (testing) return@Button
                    if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
                        resultMsg = "请先填写 URL / Key / 模型名"
                        resultOk = false
                        return@Button
                    }
                    testing = true
                    resultMsg = null
                    resultOk = null
                    scope.launch {
                        val (ok, msg) = withContext(Dispatchers.IO) {
                            runConnectionTest(apiUrl, apiKey, model, protocol)
                        }
                        testing = false
                        resultOk = ok
                        resultMsg = msg
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("测试中…")
                } else {
                    Text("测试接口")
                }
            }
        }
        resultMsg?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                fontSize = 12.sp,
                color = when (resultOk) {
                    true -> Color(0xFF388E3C)
                    false -> Color(0xFFD32F2F)
                    null -> Color.Gray
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 真正执行 HTTP 测试请求；返回 (ok, message) */
private fun runConnectionTest(
    apiUrl: String,
    apiKey: String,
    model: String,
    protocol: String
): Pair<Boolean, String> {
    val base = apiUrl.trimEnd('/')
    val effectiveProtocol = when (protocol) {
        "claude" -> "claude"
        "openai" -> "openai"
        "local" -> return false to "本地模式无需测试"
        else -> if (base.contains("claude", ignoreCase = true) || base.contains("anthropic", ignoreCase = true)) "claude" else "openai"
    }
    val t0 = System.currentTimeMillis()
    return try {
        val (url, headers, body) = if (effectiveProtocol == "claude") {
            Triple(
                "$base/messages",
                listOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 8)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", "ping") })
                    })
                }.toString()
            )
        } else {
            Triple(
                "$base/chat/completions",
                listOf("Authorization" to "Bearer $apiKey", "content-type" to "application/json"),
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 8)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", "ping") })
                    })
                }.toString()
            )
        }
        val builder = okhttp3.Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        builder.post(okhttp3.RequestBody.create(HttpClient.JSON_MEDIA_TYPE, body))
        val resp = HttpClient.client.newCall(builder.build()).execute()
        val elapsed = System.currentTimeMillis() - t0
        val respBody = resp.body?.string() ?: ""
        resp.close()
        if (resp.isSuccessful) {
            // 尝试从响应中提取模型名
            val respModel = try {
                JSONObject(respBody).optJSONObject("model")?.toString()
                    ?: JSONObject(respBody).optString("model", model)
            } catch (_: Exception) { model }
            true to "✓ 连接成功（${elapsed}ms，模型: $respModel）"
        } else {
            val errSnippet = respBody.take(120).replace("\n", " ")
            false to "✗ HTTP ${resp.code}：$errSnippet"
        }
    } catch (e: Exception) {
        val elapsed = System.currentTimeMillis() - t0
        false to "✗ 请求失败（${elapsed}ms）：${e.javaClass.simpleName}: ${e.message?.take(80)}"
    }
}
