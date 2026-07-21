package com.aftglw.devapi.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aftglw.devapi.core.time.ProactiveApprovalQueue as Q
import com.aftglw.devapi.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DebugPage(
    onBack: () -> Unit,
    debug: Boolean, onDebugChange: (Boolean) -> Unit,
    moodEnabled: Boolean, onMoodEnabledChange: (Boolean) -> Unit,
    affinityEnabled: Boolean, onAffinityEnabledChange: (Boolean) -> Unit,
    openBookMode: Boolean, onOpenBookModeChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SubPageScaffold("调试", onBack) {
        Spacer(Modifier.height(8.dp))
        ToggleRow("Debug 窗", "显示调试信息(待完善)", debug, onDebugChange)
        val sysPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        var sysEnter by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_enter", true)) }
        var sysTimer by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_timer", true)) }
        var sysHotline by remember { mutableStateOf(sysPrefs.getBoolean("sysmsg_hotline", true)) }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("系统消息", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Switch(checked = sysEnter || sysTimer || sysHotline, onCheckedChange = { v -> sysEnter = v; sysTimer = v; sysHotline = v; sysPrefs.edit().putBoolean("sysmsg_enter", v).putBoolean("sysmsg_timer", v).putBoolean("sysmsg_hotline", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("进入提示", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysEnter, onCheckedChange = { v -> sysEnter = v; sysPrefs.edit().putBoolean("sysmsg_enter", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("2小时提醒", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysTimer, onCheckedChange = { v -> sysTimer = v; sysPrefs.edit().putBoolean("sysmsg_timer", v).apply() })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("求助热线", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
            Switch(checked = sysHotline, onCheckedChange = { v -> sysHotline = v; sysPrefs.edit().putBoolean("sysmsg_hotline", v).apply() })
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("情绪感知", "使用模型本地分析对话情绪(待完善)", moodEnabled, onMoodEnabledChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模型已集成", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("🔓 开诚布公", "允许 AI 访问位置、通知、应用使用统计（仅单聊可用，群聊自动拒绝）", openBookMode, onOpenBookModeChange)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 调用前会先询问你的许可，数据仅当前对话内存中有效", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        }
        if (openBookMode) {
            // 位置权限状态 + 跳转
            val hasLocationPerm = remember {
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("位置权限", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
                Text(if (hasLocationPerm) "✓ 已授予" else "✗ 未授予", fontSize = 13.sp, color = if (hasLocationPerm) Color(0xFF07C160) else Color(0xFFD14343))
                if (!hasLocationPerm) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", ctx.packageName, null)
                        }
                        ctx.startActivity(intent)
                    }) { Text("去授权", color = Color(0xFF07C160)) }
                }
            }
            // 使用情况访问权限状态 + 跳转
            val hasUsagePerm = remember {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    ctx.packageName
                ) == android.app.AppOpsManager.MODE_ALLOWED
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("使用情况访问", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF888888))
                Text(if (hasUsagePerm) "✓ 已授予" else "✗ 未授予", fontSize = 13.sp, color = if (hasUsagePerm) Color(0xFF07C160) else Color(0xFFD14343))
                if (!hasUsagePerm) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        ctx.startActivity(intent)
                    }) { Text("去授权", color = Color(0xFF07C160)) }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("通知读取无需额外权限（仅本应用展示的活跃通知）", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("好感度系统", "AI 语气随相处变化(待完善)", affinityEnabled, onAffinityEnabledChange)
        Spacer(Modifier.height(8.dp))
        // 语音 TTS 设置
        var ttsEnabled by remember { mutableStateOf(sysPrefs.getBoolean("tts_enabled", false)) }
        ToggleRow("AI 语音朗读", "在 AI 回复气泡显示朗读按钮", ttsEnabled) { v ->
            ttsEnabled = v; sysPrefs.edit().putBoolean("tts_enabled", v).apply()
        }
        if (ttsEnabled) {
            // 语速
            var ttsRate by remember { mutableStateOf(sysPrefs.getFloat("tts_rate", 1.0f)) }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("语速", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
                Slider(
                    value = ttsRate,
                    onValueChange = { ttsRate = it; sysPrefs.edit().putFloat("tts_rate", it).apply() },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f)
                )
                Text("${"%.1f".format(ttsRate)}x", fontSize = 13.sp, color = Color(0xFF1A1A1A), modifier = Modifier.width(36.dp))
            }
            // 音调
            var ttsPitch by remember { mutableStateOf(sysPrefs.getFloat("tts_pitch", 1.0f)) }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("音调", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
                Slider(
                    value = ttsPitch,
                    onValueChange = { ttsPitch = it; sysPrefs.edit().putFloat("tts_pitch", it).apply() },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f)
                )
                Text("${"%.1f".format(ttsPitch)}", fontSize = 13.sp, color = Color(0xFF1A1A1A), modifier = Modifier.width(36.dp))
            }
            // 引擎选择（本地 / 云端 / PC GPT-SoVITS）
            var ttsEngine by remember { mutableStateOf(sysPrefs.getString("tts_engine", "local") ?: "local") }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("引擎", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
                val engineOptions = listOf("local" to "本地", "cloud" to "云端", "gpt_sovits" to "PC GPT-SoVITS", "qwen3_tts" to "PC Qwen3-TTS")
                val engineExpanded = remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    val engineLabel = engineOptions.find { it.first == ttsEngine }?.second ?: "本地"
                    Text(engineLabel, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { engineExpanded.value = true })
                    DropdownMenu(expanded = engineExpanded.value, onDismissRequest = { engineExpanded.value = false }) {
                        engineOptions.forEach { (k, v) ->
                            DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { ttsEngine = k; sysPrefs.edit().putString("tts_engine", k).apply(); engineExpanded.value = false })
                        }
                    }
                }
            }
            // 云端配置
            if (ttsEngine == "cloud") {
                // 音色选择（按引擎存储，避免污染其他引擎）
                val cloudVoiceKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.enginePrefsKey("cloud")
                var ttsVoice by remember { mutableStateOf(sysPrefs.getString(cloudVoiceKey, "alloy") ?: "alloy") }
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("音色", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
                    val voiceExpanded = remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(ttsVoice, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { voiceExpanded.value = true })
                        DropdownMenu(expanded = voiceExpanded.value, onDismissRequest = { voiceExpanded.value = false }) {
                            com.aftglw.devapi.core.voice.CloudTtsService.AVAILABLE_VOICES.forEach { v ->
                                DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { ttsVoice = v; sysPrefs.edit().putString(cloudVoiceKey, v).apply(); voiceExpanded.value = false })
                            }
                        }
                    }
                }
                TextFieldRow("TTS 端点", "留空则用 AI API URL + /v1/audio/speech", sysPrefs.getString("tts_cloud_url", "") ?: "") { v -> sysPrefs.edit().putString("tts_cloud_url", v).apply() }
                PasswordRow("TTS Key", "留空则复用 AI API Key", com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "tts_cloud_key")) { v -> com.aftglw.devapi.core.security.SecureKeyStore.putString(ctx, "tts_cloud_key", v) }
                TextFieldRow("TTS 模型", "默认 tts-1", sysPrefs.getString("tts_cloud_model", "tts-1") ?: "tts-1") { v -> sysPrefs.edit().putString("tts_cloud_model", v).apply() }
            }
            // PC GPT-SoVITS 配置
            if (ttsEngine == "gpt_sovits") {
                TextFieldRow("PC 服务地址", "如 http://192.168.1.10:9880", sysPrefs.getString("tts_gptsovits_url", "") ?: "") { v -> sysPrefs.edit().putString("tts_gptsovits_url", v).apply() }
                val gptLanguageKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.languageEnginePrefsKey("gpt_sovits")
                var gptLanguage by remember { mutableStateOf(sysPrefs.getString(gptLanguageKey, "zh") ?: "zh") }
                val languageOptions = listOf("zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "yue" to "粤语")
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("默认语言", Modifier.width(72.dp), fontSize = 13.sp, color = Color(0xFF888888))
                    val languageExpanded = remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(languageOptions.find { it.first == gptLanguage }?.second ?: gptLanguage, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { languageExpanded.value = true })
                        DropdownMenu(expanded = languageExpanded.value, onDismissRequest = { languageExpanded.value = false }) {
                            languageOptions.forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { gptLanguage = code; sysPrefs.edit().putString(gptLanguageKey, code).apply(); languageExpanded.value = false })
                            }
                        }
                    }
                }
                // 探活按钮 + 状态显示（availableVoices 随引擎切换重置，避免上一次引擎残留）
                var healthStatus by remember { mutableStateOf("") }
                var checking by remember { mutableStateOf(false) }
                var availableVoices by remember(ttsEngine) { mutableStateOf<List<String>>(emptyList()) }
                val healthScope = rememberCoroutineScope()
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            val url = sysPrefs.getString("tts_gptsovits_url", "")?.trim() ?: ""
                            if (url.isEmpty()) {
                                healthStatus = "请先填写服务地址"
                                return@TextButton
                            }
                            checking = true
                            healthStatus = "检测中..."
                            healthScope.launch {
                                try {
                                    val ok = com.aftglw.devapi.core.voice.RemoteGptSoVitsTtsProvider(ctx).let { p ->
                                        // 复用 Provider 的 isAvailable（含 5min 缓存）
                                        kotlinx.coroutines.withTimeoutOrNull(3000L) { p.isAvailable() } ?: false
                                    }
                                    healthStatus = if (ok) "✓ 在线" else "✗ 无法连接"
                                    if (ok) {
                                        // 拉取音色列表
                                        val voices = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val req = okhttp3.Request.Builder()
                                                    .url("${url.trimEnd('/')}/speakers")
                                                    .get().build()
                                                val resp = com.aftglw.devapi.network.HttpClient.client.newCall(req).execute()
                                                resp.use { r ->
                                                    if (r.isSuccessful) {
                                                        org.json.JSONArray(r.body?.string() ?: "[]")
                                                            .let { arr -> List(arr.length()) { arr.optString(it) } }
                                                    } else emptyList()
                                                }
                                            } catch (e: Exception) { Log.w("DebugPage", "fetch voices failed", e); emptyList() }
                                        }
                                        availableVoices = voices
                                        if (voices.isNotEmpty()) healthStatus = "✓ 在线（${voices.size} 个音色）"
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("DebugPage", "TTS health check failed", e)
                                    healthStatus = "✗ ${e.message?.take(40)}"
                                }
                                checking = false
                            }
                        }
                    ) { Text(if (checking) "检测中..." else "检测连接", color = Color(0xFF07C160)) }
                    Spacer(Modifier.width(12.dp))
                    Text(healthStatus, fontSize = 13.sp, color = if (healthStatus.startsWith("✓")) Color(0xFF07C160) else Color(0xFF888888))
                }
                // 音色选择（从 /speakers 拉取的列表，按引擎存储）
                if (availableVoices.isNotEmpty()) {
                    val gptVoiceKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.enginePrefsKey("gpt_sovits")
                    var ttsVoice by remember(availableVoices) {
                        mutableStateOf(sysPrefs.getString(gptVoiceKey, availableVoices.first()) ?: availableVoices.first())
                    }
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("音色", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
                        val voiceExpanded = remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Text(ttsVoice, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { voiceExpanded.value = true })
                            DropdownMenu(expanded = voiceExpanded.value, onDismissRequest = { voiceExpanded.value = false }) {
                                availableVoices.forEach { v ->
                                    DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { ttsVoice = v; sysPrefs.edit().putString(gptVoiceKey, v).apply(); voiceExpanded.value = false })
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text("PC 离线时自动降级到云端 / 系统 TTS", fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
            // PC Qwen3-TTS 配置
            if (ttsEngine == "qwen3_tts") {
                TextFieldRow("PC 服务地址", "如 http://192.168.1.10:17890", sysPrefs.getString("tts_qwen3_url", "") ?: "") { v -> sysPrefs.edit().putString("tts_qwen3_url", v).apply() }
                val qwenLanguageKey = com.aftglw.devapi.core.voice.TtsVoiceRouter.languageEnginePrefsKey("qwen3_tts")
                var qwenLanguage by remember { mutableStateOf(sysPrefs.getString(qwenLanguageKey, "zh") ?: "zh") }
                val languageOptions = listOf("zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "de" to "德语", "fr" to "法语", "ru" to "俄语", "pt" to "葡萄牙语", "es" to "西班牙语", "it" to "意大利语", "auto" to "自动")
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("默认语言", Modifier.width(72.dp), fontSize = 13.sp, color = Color(0xFF888888))
                    val languageExpanded = remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(languageOptions.find { it.first == qwenLanguage }?.second ?: qwenLanguage, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { languageExpanded.value = true })
                        DropdownMenu(expanded = languageExpanded.value, onDismissRequest = { languageExpanded.value = false }) {
                            languageOptions.forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { qwenLanguage = code; sysPrefs.edit().putString(qwenLanguageKey, code).apply(); languageExpanded.value = false })
                            }
                        }
                    }
                }
                TextFieldRow("默认音色", "如 Vivian、Ryan", sysPrefs.getString(com.aftglw.devapi.core.voice.TtsVoiceRouter.enginePrefsKey("qwen3_tts"), "Vivian") ?: "Vivian") { v -> sysPrefs.edit().putString(com.aftglw.devapi.core.voice.TtsVoiceRouter.enginePrefsKey("qwen3_tts"), v).apply() }
                TextFieldRow("默认语气", "如 温柔、亲切地说", sysPrefs.getString(com.aftglw.devapi.core.voice.TtsVoiceRouter.instructionEnginePrefsKey("qwen3_tts"), "") ?: "") { v -> sysPrefs.edit().putString(com.aftglw.devapi.core.voice.TtsVoiceRouter.instructionEnginePrefsKey("qwen3_tts"), v).apply() }
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text("PC 离线时自动降级到 GPT-SoVITS / 云端 / 系统 TTS", fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
            // 试听按钮（统一走 TtsProviderManager，自动降级；voiceId 由 router 按引擎解析）
            val previewScope = rememberCoroutineScope()
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                TextButton(onClick = {
                    val engine = sysPrefs.getString("tts_engine", "local") ?: "local"
                    val sentence = "你好呀，我是你的 AI 伙伴。"
                    previewScope.launch {
                        com.aftglw.devapi.core.voice.TtsProviderManager.configure(ctx, engine)
                        val outcome = com.aftglw.devapi.core.voice.TtsProviderManager.speak(
                            ctx = ctx,
                            text = sentence,
                            utteranceId = "tts_test"
                        )
                        if (outcome is com.aftglw.devapi.core.voice.TtsOutcome.Failed) {
                            Toast.makeText(ctx, "TTS 失败: ${outcome.reason.take(60)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("试听", color = Color(0xFF07C160)) }
            }
        }

        // ── 情绪语气映射预览（P2.3a 新增）──
        AffectiveTtsPreviewPanel(ctx, sysPrefs)

        Spacer(Modifier.height(8.dp))
        // 语音 STT 设置（与 TTS 对称：引擎选择 + cloud/remote_whisper 配置）
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("语音转文字 (STT)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("引擎", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
            var sttEngine by remember { mutableStateOf(sysPrefs.getString("stt_engine", "system") ?: "system") }
            val sttEngineOptions = listOf(
                "system" to "系统",
                "cloud" to "云端 Whisper",
                "remote_whisper" to "PC Whisper",
                "local_whisper" to "本地 Whisper",
                "local_sensevoice" to "本地 SenseVoice",
                "xfyun" to "讯飞 RTASR"
            )
            val sttEngineExpanded = remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                val engineLabel = sttEngineOptions.find { it.first == sttEngine }?.second ?: "系统"
                Text(engineLabel, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { sttEngineExpanded.value = true })
                DropdownMenu(expanded = sttEngineExpanded.value, onDismissRequest = { sttEngineExpanded.value = false }) {
                    sttEngineOptions.forEach { (k, v) ->
                        DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { sttEngine = k; sysPrefs.edit().putString("stt_engine", k).apply(); sttEngineExpanded.value = false })
                    }
                }
            }
        }
        // 云端 Whisper 配置
        if (sysPrefs.getString("stt_engine", "system") == "cloud") {
            TextFieldRow("STT 端点", "留空则用 AI API URL + /v1/audio/transcriptions", sysPrefs.getString("stt_cloud_url", "") ?: "") { v -> sysPrefs.edit().putString("stt_cloud_url", v).apply() }
            PasswordRow("STT Key", "留空则复用 AI API Key", com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "stt_cloud_key")) { v -> com.aftglw.devapi.core.security.SecureKeyStore.putString(ctx, "stt_cloud_key", v) }
            TextFieldRow("STT 模型", "默认 whisper-1", sysPrefs.getString("stt_cloud_model", "whisper-1") ?: "whisper-1") { v -> sysPrefs.edit().putString("stt_cloud_model", v).apply() }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("云端不可用时自动降级到系统 STT", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        // PC Whisper 配置
        if (sysPrefs.getString("stt_engine", "system") == "remote_whisper") {
            TextFieldRow("PC 服务地址", "如 http://192.168.1.10:9000", sysPrefs.getString("stt_whisper_url", "") ?: "") { v -> sysPrefs.edit().putString("stt_whisper_url", v).apply() }
            // 探活按钮
            var whisperHealthStatus by remember { mutableStateOf("") }
            var whisperChecking by remember { mutableStateOf(false) }
            val whisperHealthScope = rememberCoroutineScope()
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !whisperChecking,
                    onClick = {
                        val url = sysPrefs.getString("stt_whisper_url", "")?.trim() ?: ""
                        if (url.isEmpty()) {
                            whisperHealthStatus = "请先填写服务地址"
                            return@TextButton
                        }
                        whisperChecking = true
                        whisperHealthStatus = "检测中..."
                        whisperHealthScope.launch {
                            try {
                                val ok = com.aftglw.devapi.core.voice.RemoteWhisperSttProvider(ctx).let { p ->
                                    kotlinx.coroutines.withTimeoutOrNull(3000L) { p.isAvailable() } ?: false
                                }
                                whisperHealthStatus = if (ok) "✓ 在线" else "✗ 无法连接"
                            } catch (e: Exception) {
                                android.util.Log.e("DebugPage", "Whisper health check failed", e)
                                whisperHealthStatus = "✗ ${e.message?.take(40)}"
                            }
                            whisperChecking = false
                        }
                    }
                ) { Text(if (whisperChecking) "检测中..." else "检测连接", color = Color(0xFF07C160)) }
                Spacer(Modifier.width(12.dp))
                Text(whisperHealthStatus, fontSize = 13.sp, color = if (whisperHealthStatus.startsWith("✓")) Color(0xFF07C160) else Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("PC 离线时自动降级到云端 / 系统 STT", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        // 本地 Whisper 配置（sherpa-onnx：SAF 导入 encoder + decoder + tokens）
        if (sysPrefs.getString("stt_engine", "system") == "local_whisper") {
            val importScope = rememberCoroutineScope()
            // 当前模型三件套
            val currentEncoder = remember { mutableStateOf(com.aftglw.devapi.core.voice.LocalWhisperSttProvider.getEncoderFile(ctx)?.name ?: "未导入") }
            val currentDecoder = remember { mutableStateOf(com.aftglw.devapi.core.voice.LocalWhisperSttProvider.getDecoderFile(ctx)?.name ?: "未导入") }
            val currentTokens = remember { mutableStateOf(com.aftglw.devapi.core.voice.LocalWhisperSttProvider.getTokensFile(ctx)?.name ?: "未导入") }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Encoder：", fontSize = 13.sp, color = Color(0xFF888888))
                Text(currentEncoder.value, fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Decoder：", fontSize = 13.sp, color = Color(0xFF888888))
                Text(currentDecoder.value, fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Tokens：", fontSize = 13.sp, color = Color(0xFF888888))
                Text(currentTokens.value, fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            // SAF 选择器：导入 ONNX 模型（encoder/decoder 自动识别）或 tokens.txt
            val pickOnnx = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                importScope.launch {
                    val imported = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aftglw.devapi.core.voice.LocalWhisperSttProvider.importFromUri(ctx, uri)
                    }
                    if (imported != null) {
                        val lower = imported.name.lowercase()
                        when {
                            lower.contains("encoder") -> currentEncoder.value = imported.name
                            lower.contains("decoder") -> currentDecoder.value = imported.name
                            lower.contains("tokens") -> currentTokens.value = imported.name
                        }
                        Toast.makeText(ctx, "已导入：${imported.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "导入失败（需 .onnx 含 encoder/decoder，或 .txt 含 tokens）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                TextButton(onClick = { pickOnnx.launch(arrayOf("application/octet-stream", "*/*")) }) {
                    Text("导入 ONNX / tokens（自动识别）", color = Color(0xFF07C160))
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("推荐：sherpa-onnx-whisper-tiny（int8 量化，encoder 13MB + decoder 90MB + tokens 0.8MB，强制 zh + transcribe）",
                    fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("模型缺失时自动降级到云端 / 系统 STT", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        // 本地 SenseVoice 配置（sherpa-onnx：SAF 导入 model.int8.onnx + tokens.txt）
        if (sysPrefs.getString("stt_engine", "system") == "local_sensevoice") {
            val importScope = rememberCoroutineScope()
            val currentModel = remember { mutableStateOf(com.aftglw.devapi.core.voice.LocalSenseVoiceSttProvider.getModelFile(ctx)?.name ?: "未导入") }
            val currentTokens = remember { mutableStateOf(com.aftglw.devapi.core.voice.LocalSenseVoiceSttProvider.getTokensFile(ctx)?.name ?: "未导入") }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Model：", fontSize = 13.sp, color = Color(0xFF888888))
                Text(currentModel.value, fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Tokens：", fontSize = 13.sp, color = Color(0xFF888888))
                Text(currentTokens.value, fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            val pickSvFile = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                importScope.launch {
                    val imported = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aftglw.devapi.core.voice.LocalSenseVoiceSttProvider.importFromUri(ctx, uri)
                    }
                    if (imported != null) {
                        val lower = imported.name.lowercase()
                        when {
                            lower.endsWith(".onnx") -> currentModel.value = imported.name
                            lower.endsWith(".txt") -> currentTokens.value = imported.name
                        }
                        Toast.makeText(ctx, "已导入：${imported.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "导入失败（需 .onnx 或 .txt）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                TextButton(onClick = { pickSvFile.launch(arrayOf("application/octet-stream", "*/*")) }) {
                    Text("导入 ONNX / tokens（自动识别）", color = Color(0xFF07C160))
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("推荐：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17（int8 229MB，中文准确率显著优于 whisper-tiny）",
                    fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("模型缺失时自动降级到 讯飞 → 云端 → 系统", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        // 讯飞 RTASR 配置（APPID + APIKey，在线 WebSocket 流式转写）
        if (sysPrefs.getString("stt_engine", "system") == "xfyun") {
            TextFieldRow("APPID", "讯飞开放平台应用 ID", sysPrefs.getString("stt_xfyun_appid", "") ?: "") { v -> sysPrefs.edit().putString("stt_xfyun_appid", v).apply() }
            PasswordRow("APIKey", "实时语音转写服务 apiKey", com.aftglw.devapi.core.security.SecureKeyStore.getString(ctx, "stt_xfyun_api_key")) { v -> com.aftglw.devapi.core.security.SecureKeyStore.putString(ctx, "stt_xfyun_api_key", v) }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("需在讯飞开放平台开通「实时语音转写」服务，免费额度 50000 次/日",
                    fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("网络异常时自动降级到云端 / 系统 STT", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        // 系统 STT 提示
        if (sysPrefs.getString("stt_engine", "system") == "system") {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("部分 ROM 系统 STT 不回调，建议改用云端 / PC / 本地 Whisper", fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val dbPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            Text("历史消息数", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.width(80.dp))
            val ctxw = dbPrefs.getInt("context_window", 0)
            val cwOptions = listOf(0 to "自动", 10 to "10条", 20 to "20条", 30 to "30条", 50 to "50条", 80 to "80条", 100 to "100条")
            val cwExpanded = remember { mutableStateOf(false) }
            Box { val cwLabel = cwOptions.find { it.first == ctxw }?.second ?: "自动"
                Text(cwLabel, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { cwExpanded.value = true })
                DropdownMenu(expanded = cwExpanded.value, onDismissRequest = { cwExpanded.value = false }) {
                    cwOptions.forEach { (v, label) -> DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { dbPrefs.edit().putInt("context_window", v).apply(); cwExpanded.value = false }) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val shareLog: () -> Unit = shareLog@{
            scope.launch {
                val result = runCatching {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aftglw.devapi.core.debug.DebugLogExporter.export(ctx)
                    }
                }
                result.onSuccess { logFile ->
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", logFile))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(shareIntent, "鍒嗕韩璋冭瘯鏃ュ織"))
                }.onFailure {
                    Toast.makeText(ctx, "瀵煎嚭澶辫触", Toast.LENGTH_SHORT).show()
                }
            }
            return@shareLog
            // Legacy export code is unreachable after return@shareLog.
            try {
                val sb = StringBuilder()
                sb.appendLine("=== Wisp Debug Log ===")
                sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                sb.appendLine("Device: ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                sb.appendLine(); val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                sb.appendLine("API URL: ${prefs.getString("ai_api_url", "")?.take(60)}")
                sb.appendLine("Mood: ${prefs.getBoolean("mood_enabled", false)}")
                sb.appendLine("Affinity: ${prefs.getBoolean("affinity_enabled", false)}")
                sb.appendLine("Protocol: ${com.aftglw.devapi.network.AiServiceFactory.getProtocolName()}")
                val logDir = java.io.File(ctx.cacheDir, "log"); logDir.mkdirs()
                val logFile = java.io.File(logDir, "Wisp_debug_log.txt"); logFile.writeText(sb.toString())
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", logFile))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(shareIntent, "分享调试日志"))
            } catch (e: Exception) {
                Log.e("DebugPage", "shareLog failed", e)
                Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
        OutlinedButton(onClick = { shareLog() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("导出调试日志", fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("时区", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(0.3f))
            Spacer(Modifier.width(8.dp))
            val tzPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val tzId = tzPrefs.getString("timezone_id", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id
            val tzExpanded = remember { mutableStateOf(false) }
            Box { Text(tzId, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { tzExpanded.value = true })
                DropdownMenu(expanded = tzExpanded.value, onDismissRequest = { tzExpanded.value = false }) {
                    com.aftglw.devapi.core.time.TimeService.getAvailableTimezones().forEach { (id, label) -> DropdownMenuItem(text = { Text("$id  $label", fontSize = 13.sp) }, onClick = { com.aftglw.devapi.core.time.TimeService.setTimezone(ctx, id); tzExpanded.value = false }) }
                }
            }
        }

        // ── AffectiveField 状态展示（P0 新增，设计文档第十四章十节验证基础设施）──
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("情绪场 (AffectiveField)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        var affectChatName by remember { mutableStateOf("") }
        var affectSyncEnabled by remember { mutableStateOf(sysPrefs.getBoolean("wisp_affect_sync_enabled", false)) }
        var affectSyncUrl by remember { mutableStateOf(sysPrefs.getString("wisp_affect_sync_url", "") ?: "") }
        var affectSyncToken by remember { mutableStateOf(sysPrefs.getString("wisp_affect_sync_token", "") ?: "") }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AffectiveField Desktop sync", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Explicit opt-in; local or LAN Wisp Desktop only", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(checked = affectSyncEnabled, onCheckedChange = { enabled ->
                affectSyncEnabled = enabled
                sysPrefs.edit().putBoolean("wisp_affect_sync_enabled", enabled).apply()
            })
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = affectSyncUrl,
                onValueChange = { value -> affectSyncUrl = value; sysPrefs.edit().putString("wisp_affect_sync_url", value.trim()).apply() },
                label = { Text("Desktop URL", fontSize = 12.sp) },
                placeholder = { Text("http://192.168.x.x:17890", fontSize = 12.sp) },
                singleLine = true,
                enabled = affectSyncEnabled,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = affectSyncToken,
                onValueChange = { value -> affectSyncToken = value; sysPrefs.edit().putString("wisp_affect_sync_token", value.trim()).apply() },
                label = { Text("Desktop API token (optional)", fontSize = 12.sp) },
                singleLine = true,
                enabled = affectSyncEnabled,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        // P1.5: 同步失败重试队列状态展示
        var syncQueueStatus by remember { mutableStateOf<com.aftglw.devapi.core.affect.AffectSyncRetryQueue.QueueStatus?>(null) }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = syncQueueStatus
            val statusText = if (status == null) "未查询" else "队列 ${status.queueSize} 条（待重试 ${status.pendingRetry}，就绪 ${status.readyToRetry}）"
            val statusColor = if (status != null && status.queueSize > 0) Color(0xFFD14343) else Color(0xFF888888)
            Text("重试队列：$statusText", fontSize = 12.sp, color = statusColor, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                syncQueueStatus = com.aftglw.devapi.core.affect.AffectSyncRetryQueue.getStatus()
            }) { Text("刷新", color = Color(0xFF07C160), fontSize = 13.sp) }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = {
                com.aftglw.devapi.core.affect.AffectSyncRetryQueue.clear()
                syncQueueStatus = com.aftglw.devapi.core.affect.AffectSyncRetryQueue.getStatus()
                Toast.makeText(ctx, "已清空重试队列", Toast.LENGTH_SHORT).show()
            }) { Text("清空", color = Color(0xFFD14343), fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text("说明：可重试错误（408/425/429/5xx + 网络异常）入队指数退避（10s→30s→90s，最多 3 次）；永久错误（400/401/403/404 等 4xx）直接丢弃。队列纯内存，app 重启后清空。",
                fontSize = 10.sp, color = Color(0xFFAAAAAA))
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("角色名", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = affectChatName,
                onValueChange = { affectChatName = it.trim() },
                placeholder = { Text("输入角色名查看状态", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        if (affectChatName.isNotBlank()) {
            val affectPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            val aKey = "affective_field_$affectChatName"
            val tension = affectPrefs.getFloat("${aKey}_tension", 0f)
            val warmth = affectPrefs.getFloat("${aKey}_warmth", 0f)
            val anticipation = affectPrefs.getFloat("${aKey}_anticipation", 0f)
            val drift = affectPrefs.getFloat("${aKey}_drift", 0f)
            val affectTs = affectPrefs.getLong("${aKey}_ts", 0L)
            val daysSinceUpdate = if (affectTs > 0) ((System.currentTimeMillis() - affectTs) / 86_400_000L).toInt() else -1
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("张力", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(String.format("%.2f", tension), fontSize = 13.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.width(12.dp))
                Text(labelForTension(tension), fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("温度", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(String.format("%.2f", warmth), fontSize = 13.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.width(12.dp))
                Text(labelForWarmth(warmth), fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("期待", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(String.format("%.2f", anticipation), fontSize = 13.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.width(12.dp))
                Text(labelForAnticipation(anticipation), fontSize = 12.sp, color = Color(0xFF888888))
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("漂移", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(String.format("%.2f", drift), fontSize = 13.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.width(12.dp))
                Text(labelForDrift(drift), fontSize = 12.sp, color = Color(0xFF888888))
            }
            if (daysSinceUpdate >= 0) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text("上次更新", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$daysSinceUpdate 天前", fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
            // Pending events + RhythmSensor 样本数（异步加载）
            var pendingCount by remember(affectChatName) { mutableStateOf(-1) }
            var pendingList by remember(affectChatName) { mutableStateOf<List<com.aftglw.devapi.core.affect.PendingEvent>>(emptyList()) }
            var rhythmSampleCount by remember(affectChatName) { mutableStateOf(0) }
            var rhythmHint by remember(affectChatName) { mutableStateOf("") }
            LaunchedEffect(affectChatName) {
                scope.launch {
                    val snapshot = com.aftglw.devapi.core.affect.AffectiveEngine.snapshot(ctx, affectChatName)
                    pendingCount = snapshot.activePendingEvents.size
                    pendingList = snapshot.activePendingEvents
                    rhythmSampleCount = snapshot.rhythmProfile.sampleCount
                    rhythmHint = if (snapshot.stateHint.isNonEmpty()) snapshot.stateHint.observation else ""
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("韵律样本", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text("$rhythmSampleCount 条", fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            if (rhythmHint.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text("韵律观测", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(rhythmHint, fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("未完成事件", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (pendingCount >= 0) "$pendingCount 条" else "加载中…", fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }
            pendingList.take(5).forEach { pe ->
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 32.dp, vertical = 2.dp)) {
                    Text("• ${pe.summary} (staleness=${String.format("%.2f", pe.staleness())}, attempts=${pe.attemptCount})", fontSize = 11.sp, color = Color(0xFF888888))
                }
            }
            // 清除按钮
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        com.aftglw.devapi.core.affect.AffectiveEngine.clearForChat(ctx, affectChatName)
                        Toast.makeText(ctx, "已清除 $affectChatName 的情绪场状态", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("清除该角色情绪场数据", color = Color(0xFFD14343), fontSize = 13.sp) }
            }
        }

        // ── 群聊关系场面板（P2.2c 新增）──
        GroupAffectPanel(ctx, scope)

        // ── P2.4 可观测性面板 ──
        DecisionLogPanel(ctx, scope)
        FailureStatsPanel(ctx, scope)
        EventReplayPanel(ctx, scope)
        DataControlPanel(ctx, scope)

        // ── ProactiveScheduler dry-run 建议展示（P0.1 新增）──
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("主动消息 dry-run 建议", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text("说明：每次 ProactiveWorker 触发时（约 30min），扫描所有角色 AffectiveField，输出建议但不发送。与 runOnce 并行运行。",
                fontSize = 11.sp, color = Color(0xFF888888))
        }
        // 风险 1 防护：dry-run only 全局开关
        var dryRunOnly by remember { mutableStateOf(sysPrefs.getBoolean("proactive_dry_run_only", false)) }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("只读模式（dry-run only）", fontSize = 13.sp, color = Color(0xFF1A1A1A))
                Text("开启后 runOnce 不发送任何主动消息，只跑 dryRunScan 记录建议。验证决策合理后再关闭。",
                    fontSize = 11.sp, color = Color(0xFF888888))
            }
            Switch(checked = dryRunOnly, onCheckedChange = { v ->
                dryRunOnly = v
                sysPrefs.edit().putBoolean("proactive_dry_run_only", v).apply()
            })
        }
        var dryRunEntries by remember { mutableStateOf<List<com.aftglw.devapi.core.time.ProactiveDryRunEntry>>(emptyList()) }
        var dryRunFilterChat by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("筛选角色", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = dryRunFilterChat,
                onValueChange = { dryRunFilterChat = it.trim() },
                placeholder = { Text("留空查看全部", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                scope.launch {
                    val all = com.aftglw.devapi.core.time.ProactiveDryRunStore.loadAll(ctx)
                    dryRunEntries = if (dryRunFilterChat.isBlank()) all else all.filter { it.chatName == dryRunFilterChat }
                }
            }) { Text("刷新日志", color = Color(0xFF07C160), fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                scope.launch {
                    // 立即触发一次 dry-run 扫描（不等 Worker 周期）
                    com.aftglw.devapi.core.time.ProactiveScheduler.dryRunScan(ctx)
                    val all = com.aftglw.devapi.core.time.ProactiveDryRunStore.loadAll(ctx)
                    dryRunEntries = if (dryRunFilterChat.isBlank()) all else all.filter { it.chatName == dryRunFilterChat }
                    Toast.makeText(ctx, "已触发一次 dry-run 扫描", Toast.LENGTH_SHORT).show()
                }
            }) { Text("立即扫描", color = Color(0xFF07C160), fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                scope.launch {
                    com.aftglw.devapi.core.time.ProactiveDryRunStore.clearAll(ctx)
                    dryRunEntries = emptyList()
                    Toast.makeText(ctx, "已清除 dry-run 日志", Toast.LENGTH_SHORT).show()
                }
            }) { Text("清空", color = Color(0xFFD14343), fontSize = 13.sp) }
        }
        if (dryRunEntries.isEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("暂无 dry-run 记录（点击「立即扫描」生成）", fontSize = 12.sp, color = Color(0xFF888888))
            }
        } else {
            dryRunEntries.take(15).forEach { entry ->
                Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    val recommendColor = if (entry.recommendContact) Color(0xFF07C160) else Color(0xFF888888)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (entry.recommendContact) "✓ 建议" else "✗ 跳过", fontSize = 12.sp, color = recommendColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.chatName, fontSize = 12.sp, color = Color(0xFF1A1A1A))
                        Spacer(Modifier.width(8.dp))
                        val gapStr = if (entry.msSinceLastActive < 0) "从未" else "${entry.msSinceLastActive / 3_600_000L}h"
                        Text("· warmth=${"%.2f".format(entry.field.warmth)} · gap=$gapStr", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                    Text(entry.reason, fontSize = 12.sp, color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 0.dp, top = 2.dp))
                    if (entry.rhythmSignals.isNotEmpty()) {
                        Text("signals: ${entry.rhythmSignals.joinToString(", ") { it.name }}", fontSize = 10.sp, color = Color(0xFF888888))
                    }
                    if (entry.rhythmObservation.isNotBlank()) {
                        Text("韵律：${entry.rhythmObservation}", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                    if (entry.pendingCount > 0) {
                        Text("pending=${entry.pendingCount} (closure=${entry.closureCandidateCount})", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                    val ageMin = (System.currentTimeMillis() - entry.timestamp) / 60_000L
                    Text("$ageMin 分钟前", fontSize = 10.sp, color = Color(0xFFAAAAAA))
                }
            }
        }

        // ── ProactiveGatekeeper 审批状态展示（P1.2 新增）──
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ProactiveGatekeeper 审批状态", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text("说明：runOnce 在调用 generateMessage 前会调用 gatekeeper.decide()。三层审批：临时免打扰(tension) → AffectiveField → 动态冷却(warmth)。",
                fontSize = 11.sp, color = Color(0xFF888888))
        }
        var gateEntries by remember { mutableStateOf<List<Pair<String, com.aftglw.devapi.core.time.ProactiveGatekeeper.LastDecision?>>>(emptyList()) }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                scope.launch {
                    val chats = com.aftglw.devapi.core.storage.room.AppDatabase.get(ctx).chatDao().getAll()
                    gateEntries = chats.map { item ->
                        val name = item.id.ifEmpty { item.name }
                        name to com.aftglw.devapi.core.time.ProactiveGatekeeper.getLastDecision(ctx, name)
                    }
                }
            }) { Text("刷新", color = Color(0xFF07C160), fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                // 清除所有角色的临时免打扰 + 最近决策记录（一键重置审批状态）
                scope.launch {
                    val chats = com.aftglw.devapi.core.storage.room.AppDatabase.get(ctx).chatDao().getAll()
                    chats.forEach { item ->
                        val name = item.id.ifEmpty { item.name }
                        com.aftglw.devapi.core.time.ProactiveGatekeeper.clearTensionDnd(ctx, name)
                        com.aftglw.devapi.core.time.ProactiveGatekeeper.clearLastDecision(ctx, name)
                    }
                    gateEntries = chats.map { item ->
                        val name = item.id.ifEmpty { item.name }
                        name to com.aftglw.devapi.core.time.ProactiveGatekeeper.getLastDecision(ctx, name)
                    }
                    Toast.makeText(ctx, "已清除所有角色的临时免打扰 + 决策记录", Toast.LENGTH_SHORT).show()
                }
            }) { Text("清空全部", color = Color(0xFFD14343), fontSize = 13.sp) }
        }
        if (gateEntries.isEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("点击「刷新」加载各角色最近审批决策", fontSize = 12.sp, color = Color(0xFF888888))
            }
        } else {
            gateEntries.forEach { (chatName, decision) ->
                Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chatName, fontSize = 12.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        if (decision == null) {
                            Text("· 未决策过", fontSize = 11.sp, color = Color(0xFF888888))
                        } else {
                            val allowColor = if (decision.allow) Color(0xFF07C160) else Color(0xFFD14343)
                            Text("· ${if (decision.allow) "✓ 允许" else "✗ 拒绝"}", fontSize = 11.sp, color = allowColor, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            val ageMin = (System.currentTimeMillis() - decision.timestamp) / 60_000L
                            Text("$ageMin 分钟前", fontSize = 10.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                    if (decision != null) {
                        Text(decision.reason, fontSize = 12.sp, color = Color(0xFF1A1A1A))
                        val gapStr = if (decision.msSinceLastActive < 0) "从未" else "${decision.msSinceLastActive / 3_600_000L}h"
                        Text("warmth=${"%.2f".format(decision.warmth)} · tension=${"%.2f".format(decision.tension)} · gap=$gapStr",
                            fontSize = 11.sp, color = Color(0xFF888888))
                        // 临时免打扰状态（实时查询 prefs，不依赖决策时的快照）
                        val dndUntil = com.aftglw.devapi.core.time.ProactiveGatekeeper.getTensionDndUntil(ctx, chatName)
                        val nowMs = System.currentTimeMillis()
                        if (dndUntil > nowMs) {
                            val remainMin = (dndUntil - nowMs) / 60_000L
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔴 临时免打扰中，剩余 ${remainMin}min", fontSize = 11.sp, color = Color(0xFFD14343))
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    com.aftglw.devapi.core.time.ProactiveGatekeeper.clearTensionDnd(ctx, chatName)
                                    Toast.makeText(ctx, "已解除 $chatName 的临时免打扰", Toast.LENGTH_SHORT).show()
                                }) { Text("解除", color = Color(0xFFD14343), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }

        // P2.1 面板抽取为独立 composable，避免 DebugPage 的 $lambda$0 方法超过 JVM 65535 字节上限
        TimeWindowDndPanel(ctx, sysPrefs)
        ApprovalQueuePanel(ctx)
    }
}

@Composable
private fun TimeWindowDndPanel(ctx: Context, sysPrefs: android.content.SharedPreferences) {
    // ── 时间窗免打扰配置（P2.1 新增）──
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("时间窗免打扰（P2.1）", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text("说明：gatekeeper 第 0 层检查。当前小时在窗口内则拒绝发送。start == end 时禁用。",
            fontSize = 11.sp, color = Color(0xFF888888))
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        var dndStartHour by remember { mutableStateOf(sysPrefs.getInt("proactive_dnd_start_hour", 23)) }
        var dndEndHour by remember { mutableStateOf(sysPrefs.getInt("proactive_dnd_end_hour", 7)) }
        Text("时段", Modifier.width(48.dp), fontSize = 13.sp, color = Color(0xFF888888))
        val startExpanded = remember { mutableStateOf(false) }
        Box {
            Text("%02d:00".format(dndStartHour), fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { startExpanded.value = true })
            DropdownMenu(expanded = startExpanded.value, onDismissRequest = { startExpanded.value = false }) {
                (0..23).forEach { h ->
                    DropdownMenuItem(text = { Text("%02d:00".format(h), fontSize = 13.sp) }, onClick = {
                        dndStartHour = h; sysPrefs.edit().putInt("proactive_dnd_start_hour", h).apply()
                        startExpanded.value = false
                    })
                }
            }
        }
        Text(" → ", fontSize = 14.sp, color = Color(0xFF888888))
        val endExpanded = remember { mutableStateOf(false) }
        Box {
            Text("%02d:00".format(dndEndHour), fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { endExpanded.value = true })
            DropdownMenu(expanded = endExpanded.value, onDismissRequest = { endExpanded.value = false }) {
                (0..23).forEach { h ->
                    DropdownMenuItem(text = { Text("%02d:00".format(h), fontSize = 13.sp) }, onClick = {
                        dndEndHour = h; sysPrefs.edit().putInt("proactive_dnd_end_hour", h).apply()
                        endExpanded.value = false
                    })
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        TextButton(onClick = {
            dndStartHour = 0; dndEndHour = 0
            sysPrefs.edit().putInt("proactive_dnd_start_hour", 0).putInt("proactive_dnd_end_hour", 0).apply()
            Toast.makeText(ctx, "已禁用时间窗 DND", Toast.LENGTH_SHORT).show()
        }) { Text("禁用", color = Color(0xFF888888), fontSize = 13.sp) }
    }
}

@Composable
private fun ApprovalQueuePanel(ctx: Context) {
    // ── 主动消息审批队列（P2.1 新增）──
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("主动消息审批队列（P2.1）", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text("说明：gatekeeper 通过后进入此队列，等待 Desktop/本地批准后才实际发送。默认开启审批模式，不开放无确认自动发送。",
            fontSize = 11.sp, color = Color(0xFF888888))
    }
    var approvalEntries by remember { mutableStateOf<List<Q.PendingMessage>>(emptyList()) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(onClick = {
            approvalEntries = Q.listPending(ctx)
        }) { Text("刷新", color = Color(0xFF07C160), fontSize = 13.sp) }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            Q.clearAll(ctx)
            approvalEntries = Q.listPending(ctx)
            Toast.makeText(ctx, "已清空审批队列（保留终态用于幂等）", Toast.LENGTH_SHORT).show()
        }) { Text("清空全部", color = Color(0xFFD14343), fontSize = 13.sp) }
    }
    if (approvalEntries.isEmpty()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("点击「刷新」加载审批队列（runOnce 周期 30min，需等待或用 DebugPage triggerNow）", fontSize = 12.sp, color = Color(0xFF888888))
        }
    } else {
        approvalEntries.take(20).forEach { msg ->
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 6.dp)) {
                // 行 1：chatName · status · $ageMin 分钟前
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg.chatName, fontSize = 13.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    val statusColor = when (msg.status) {
                        Q.Status.PENDING -> Color(0xFF888888)
                        Q.Status.APPROVED -> Color(0xFF07C160)
                        Q.Status.REJECTED -> Color(0xFFD14343)
                        Q.Status.CANCELLED -> Color(0xFF888888)
                        Q.Status.SENT -> Color(0xFFAAAAAA)
                    }
                    Text("· ${msg.status.name}", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    val ageMin = (System.currentTimeMillis() - msg.createdAt) / 60_000L
                    Text("$ageMin 分钟前", fontSize = 10.sp, color = Color(0xFFAAAAAA))
                    if (!msg.syncedToDesktop) {
                        Spacer(Modifier.width(8.dp))
                        Text("· 未同步", fontSize = 10.sp, color = Color(0xFFD14343))
                    }
                }
                // 行 2：内容预览
                Text(msg.content.take(80), fontSize = 12.sp, color = Color(0xFF1A1A1A))
                // 行 3：gatekeeper 通过原因
                if (msg.gatekeeperReason.isNotBlank()) {
                    Text("gate: ${msg.gatekeeperReason.take(60)}", fontSize = 10.sp, color = Color(0xFF888888))
                }
                // 行 4：关系场上下文
                val gapStr = if (msg.msSinceLastActive < 0) "从未" else "${msg.msSinceLastActive / 3_600_000L}h"
                Text("warmth=${"%.2f".format(msg.warmth)} · tension=${"%.2f".format(msg.tension)} · gap=$gapStr",
                    fontSize = 10.sp, color = Color(0xFF888888))
                // 行 5：操作按钮（仅 PENDING/APPROVED 状态显示）
                if (msg.status == Q.Status.PENDING || msg.status == Q.Status.APPROVED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (msg.status == Q.Status.PENDING) {
                            TextButton(onClick = {
                                Q.decideLocally(ctx, msg.id, Q.DecisionAction.APPROVE)
                                approvalEntries = Q.listPending(ctx)
                                // 立即触发发送（不必等 60s 轮询）
                                com.aftglw.devapi.core.time.ProactiveScheduler.triggerSendApproved(ctx)
                                Toast.makeText(ctx, "已批准并触发发送", Toast.LENGTH_SHORT).show()
                            }) { Text("批准并发送", color = Color(0xFF07C160), fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                Q.decideLocally(ctx, msg.id, Q.DecisionAction.REJECT)
                                approvalEntries = Q.listPending(ctx)
                            }) { Text("拒绝", color = Color(0xFFD14343), fontSize = 12.sp) }
                        }
                        TextButton(onClick = {
                            Q.cancel(ctx, msg.id)
                            approvalEntries = Q.listPending(ctx)
                        }) { Text("取消", color = Color(0xFF888888), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

private fun labelForTension(v: Float) = when {
    v > 0.6f -> "剑拔弩张"
    v > 0.2f -> "紧张"
    v > -0.2f -> "平和"
    v > -0.6f -> "和谐"
    else -> "融洽"
}

private fun labelForWarmth(v: Float) = when {
    v > 0.6f -> "亲密"
    v > 0.2f -> "熟络"
    v > -0.2f -> "初识"
    v > -0.6f -> "疏远"
    else -> "冷淡"
}

/**
 * P2.2c: 群聊关系场面板。
 *
 * 展示内容：
 * - 群聊选择下拉框（从 GroupChatManager.loadGroups 读取）
 * - 群整体氛围（snapshotGroup）：tension/warmth/anticipation/drift + sourceLabel
 * - 群成员 per-member field 列表：每成员 tension/warmth + 上次更新
 * - 操作按钮：清除群级覆盖（恢复派生模式）、清除所有成员关系场数据
 *
 * 设计原则：
 * - 与单聊 affect 面板独立，不共享 affectChatName 状态
 * - 异步加载群列表 + 异步 snapshotGroup，避免阻塞 UI
 */
@Composable
private fun GroupAffectPanel(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("群聊关系场 (P2.2)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("展示群整体氛围（派生/覆盖）与各成员关系场。群整体 warmth/drift=均值，tension/anticipation=最大值。",
            fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }

    // 群聊选择
    var groups by remember { mutableStateOf<List<com.aftglw.devapi.model.GroupChat>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var groupDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            try { groups = com.aftglw.devapi.feature.group.GroupChatManager.loadGroups(ctx) }
            catch (e: Exception) { android.util.Log.w("DebugPage", "loadGroups failed", e) }
        }
    }

    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("群聊", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            val selectedLabel = groups.find { it.id == selectedGroupId }?.name
                ?: if (groups.isEmpty()) "（无群聊）" else "请选择群聊"
            Text(selectedLabel, fontSize = 14.sp, color = Color(0xFF1A1A1A), modifier = Modifier.clickable { groupDropdownExpanded = true })
            DropdownMenu(expanded = groupDropdownExpanded, onDismissRequest = { groupDropdownExpanded = false }) {
                groups.forEach { g ->
                    DropdownMenuItem(text = { Text(g.name, fontSize = 13.sp) }, onClick = {
                        selectedGroupId = g.id
                        groupDropdownExpanded = false
                    })
                }
            }
        }
    }

    if (selectedGroupId != null) {
        val group = groups.find { it.id == selectedGroupId }
        val memberNames = group?.members?.filter { it.isNotBlank() } ?: emptyList()
        // 异步加载群整体 snapshot
        var groupSnapshot by remember(selectedGroupId) {
            mutableStateOf<com.aftglw.devapi.core.affect.AffectiveEngine.GroupSnapshot?>(null)
        }
        var groupLoadError by remember(selectedGroupId) { mutableStateOf<String?>(null) }
        LaunchedEffect(selectedGroupId) {
            scope.launch {
                try {
                    groupSnapshot = com.aftglw.devapi.core.affect.AffectiveEngine.snapshotGroup(
                        ctx, selectedGroupId!!, memberNames,
                    )
                    groupLoadError = null
                } catch (e: Exception) {
                    groupLoadError = e.message
                    android.util.Log.w("DebugPage", "snapshotGroup failed", e)
                }
            }
        }

        // 群整体氛围展示
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text("群整体", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
            Spacer(Modifier.width(8.dp))
            val snap = groupSnapshot
            if (groupLoadError != null) {
                Text("加载失败：$groupLoadError", fontSize = 12.sp, color = Color(0xFFD14343))
            } else if (snap == null) {
                Text("加载中…", fontSize = 12.sp, color = Color(0xFF888888))
            } else {
                Column {
                    Text("来源：${snap.sourceLabel}（${snap.observedMemberCount}/${memberNames.size} 成员有数据）",
                        fontSize = 12.sp, color = Color(0xFF888888))
                    val f = snap.field
                    Text("张力 ${"%.2f".format(f.tension)}（${f.tensionLabel}）  温度 ${"%.2f".format(f.warmth)}（${f.warmthLabel}）",
                        fontSize = 13.sp, color = Color(0xFF1A1A1A))
                    Text("期待 ${"%.2f".format(f.anticipation)}（${f.anticipationLabel}）  走向 ${"%.2f".format(f.drift)}（${f.driftLabel}）",
                        fontSize = 13.sp, color = Color(0xFF1A1A1A))
                    if (snap.isOverride) {
                        Text("⚠ 手动覆盖模式", fontSize = 11.sp, color = Color(0xFFD14343))
                    }
                }
            }
        }

        // 成员列表 per-member field
        if (memberNames.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("成员关系场", fontSize = 13.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)
            }
            memberNames.forEach { memberName ->
                val chatKey = "group_${selectedGroupId}_$memberName"
                val affectPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                val aKey = "affective_field_$chatKey"
                val hasData = affectPrefs.contains("${aKey}_ts")
                val tension = affectPrefs.getFloat("${aKey}_tension", 0f)
                val warmth = affectPrefs.getFloat("${aKey}_warmth", 0f)
                val affectTs = affectPrefs.getLong("${aKey}_ts", 0L)
                val minsAgo = if (affectTs > 0) ((System.currentTimeMillis() - affectTs) / 60_000L).toInt() else -1
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                    Text(memberName, fontSize = 13.sp, color = Color(0xFF1A1A1A), modifier = Modifier.width(72.dp))
                    Spacer(Modifier.width(4.dp))
                    if (hasData) {
                        Text("张力 ${"%.2f".format(tension)}  温度 ${"%.2f".format(warmth)}",
                            fontSize = 12.sp, color = Color(0xFF1A1A1A), modifier = Modifier.weight(1f))
                        Text(if (minsAgo >= 0) "${minsAgo}min 前" else "",
                            fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    } else {
                        Text("未初始化", fontSize = 12.sp, color = Color(0xFFAAAAAA), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 操作按钮
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                scope.launch {
                    com.aftglw.devapi.core.affect.AffectiveEngine.clearGroupFieldOverride(ctx, selectedGroupId!!)
                    // 重新加载 snapshot
                    groupSnapshot = com.aftglw.devapi.core.affect.AffectiveEngine.snapshotGroup(
                        ctx, selectedGroupId!!, memberNames,
                    )
                    Toast.makeText(ctx, "已清除群级覆盖，恢复派生模式", Toast.LENGTH_SHORT).show()
                }
            }) { Text("清除群级覆盖", color = Color(0xFF07C160), fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                scope.launch {
                    memberNames.forEach { memberName ->
                        com.aftglw.devapi.core.affect.AffectiveEngine.clearForChat(
                            ctx, "group_${selectedGroupId}_$memberName",
                        )
                    }
                    groupSnapshot = com.aftglw.devapi.core.affect.AffectiveEngine.snapshotGroup(
                        ctx, selectedGroupId!!, memberNames,
                    )
                    Toast.makeText(ctx, "已清除所有成员关系场数据", Toast.LENGTH_SHORT).show()
                }
            }) { Text("清除所有成员数据", color = Color(0xFFD14343), fontSize = 13.sp) }
        }
    }
}

/**
 * P2.3a: 情绪语气映射预览面板。
 *
 * 展示 AffectiveTtsMapper.fromField 的映射结果：
 * - 输入角色名 → 读取该角色 AffectiveField → 计算映射
 * - 展示 promptBlock（注入 PromptBuilder 的【说话方式】块）
 * - 展示 instruction（传给 Qwen3 的语气指令）
 * - 展示 pauseMs（段间停顿毫秒）
 * - 用映射后的 instruction 试听（调用 TtsProviderManager.speak）
 *
 * 设计原则：
 * - 与单聊 affect 面板的 affectChatName 独立，避免互相干扰
 * - 试听使用当前配置的 TTS 引擎（自动降级）
 * - AffectiveEngine.snapshot 失败时显示"无数据"，不阻塞试听
 */
@Composable
private fun AffectiveTtsPreviewPanel(
    ctx: android.content.Context,
    sysPrefs: android.content.SharedPreferences,
) {
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("情绪语气映射预览 (P2.3)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("输入角色名查看 AffectiveField → TTS 提示映射结果，并用映射后的语气指令试听。",
            fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }

    var previewName by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("角色名", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = previewName,
            onValueChange = { previewName = it.trim() },
            placeholder = { Text("输入角色名", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }

    if (previewName.isNotBlank()) {
        // 读取 AffectiveField 四维值
        val aKey = "affective_field_$previewName"
        val tension = sysPrefs.getFloat("${aKey}_tension", 0f)
        val warmth = sysPrefs.getFloat("${aKey}_warmth", 0f)
        val anticipation = sysPrefs.getFloat("${aKey}_anticipation", 0f)
        val drift = sysPrefs.getFloat("${aKey}_drift", 0f)
        val hasData = sysPrefs.contains("${aKey}_ts")

        if (!hasData) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("该角色无 AffectiveField 数据（未对话过）", fontSize = 12.sp, color = Color(0xFFAAAAAA))
            }
        } else {
            // 构造 AffectiveField 并计算映射
            val field = com.aftglw.devapi.core.affect.AffectiveField(
                tension = tension, warmth = warmth,
                anticipation = anticipation, drift = drift,
            )
            val hints = com.aftglw.devapi.core.affect.AffectiveTtsMapper.fromField(field)

            // 展示四维值
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                Text("张力 ${"%.2f".format(tension)}  温度 ${"%.2f".format(warmth)}  期待 ${"%.2f".format(anticipation)}  走向 ${"%.2f".format(drift)}",
                    fontSize = 12.sp, color = Color(0xFF888888))
            }

            // pauseMs
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                Text("段间停顿", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(8.dp))
                Text("${hints.pauseMs} ms", fontSize = 13.sp, color = Color(0xFF1A1A1A))
            }

            // instruction
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                Text("语气指令", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(8.dp))
                Text(hints.instruction.ifBlank { "（空，使用引擎默认）" },
                    fontSize = 13.sp, color = if (hints.instruction.isBlank()) Color(0xFFAAAAAA) else Color(0xFF1A1A1A))
            }

            // promptBlock（多行展示）
            if (hints.promptBlock.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                    Text("说话方式块", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(hints.promptBlock.trim(), fontSize = 12.sp, color = Color(0xFF1A1A1A))
                }
            }

            // 试听按钮：用映射后的 instruction 调用 TtsProviderManager
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                TextButton(onClick = {
                    val engine = sysPrefs.getString("tts_engine", "local") ?: "local"
                    val sentence = "你好呀，我是你的 AI 伙伴。"
                    scope.launch {
                        com.aftglw.devapi.core.voice.TtsProviderManager.configure(ctx, engine)
                        val outcome = com.aftglw.devapi.core.voice.TtsProviderManager.speak(
                            ctx = ctx,
                            text = sentence,
                            utteranceId = "tts_affect_preview",
                            characterName = previewName.takeIf { it.isNotBlank() },
                            // P2.3a: 传入映射后的语气指令（仅 Qwen3 真正使用，其他引擎静默忽略）
                            instructionOverride = hints.instruction.ifBlank { null },
                        )
                        if (outcome is com.aftglw.devapi.core.voice.TtsOutcome.Failed) {
                            Toast.makeText(ctx, "TTS 失败: ${outcome.reason.take(60)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("用此语气试听", color = Color(0xFF07C160), fontSize = 13.sp) }
            }
        }
    }
}

private fun labelForAnticipation(v: Float) = when {
    v > 0.7f -> "焦急等待"
    v > 0.3f -> "有所期待"
    else -> "平静"
}

private fun labelForDrift(v: Float) = when {
    v > 0.3f -> "靠近中"
    v > -0.1f -> "稳定"
    v > -0.5f -> "疏远中"
    else -> "渐行渐远"
}

// ── P2.4 可观测性面板（独立 @Composable，避免 DebugPage 主体 MethodTooLargeException）──

/** P2.4: 从 Desktop 拉取的事件解析后的轻量数据类（Compose 友好，不持有 JSONObject 引用）。 */
private data class ReplayEvent(
    val chatName: String,
    val eventId: String,
    val receivedAt: Long,
    val tension: Float,
    val warmth: Float,
    val anticipation: Float,
    val drift: Float,
    val pendingCount: Int,
    val responseAssessmentFlags: String,
)

/**
 * P2.4: 调度决策日志面板。
 *
 * 展示 [com.aftglw.devapi.core.time.ProactiveDecisionLog] 的决策轨迹：
 * runOnce 每个决策节点（SKIP/ENQUEUE/SENT）的结构化日志，50 条环形覆盖。
 */
@Composable
private fun DecisionLogPanel(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("调度决策日志 (P2.4)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("runOnce 每个决策节点的结构化日志，50 条环形覆盖", fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }
    var entries by remember { mutableStateOf<List<com.aftglw.devapi.core.time.ProactiveDecisionLog.DecisionEntry>>(emptyList()) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(onClick = {
            scope.launch {
                entries = com.aftglw.devapi.core.time.ProactiveDecisionLog.loadAll(ctx)
            }
        }) { Text("刷新", color = Color(0xFF07C160), fontSize = 13.sp) }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
        TextButton(onClick = {
            scope.launch {
                com.aftglw.devapi.core.time.ProactiveDecisionLog.clearAll(ctx)
                entries = emptyList()
                Toast.makeText(ctx, "已清空决策日志", Toast.LENGTH_SHORT).show()
            }
        }) { Text("清空", color = Color(0xFFD14343), fontSize = 13.sp) }
    }
    if (entries.isEmpty()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("点击「刷新」加载决策日志（runOnce 周期 30min）", fontSize = 12.sp, color = Color(0xFF888888))
        }
    } else {
        entries.take(20).forEach { entry ->
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                val actionColor = when (entry.action) {
                    "SKIP" -> Color(0xFFD14343)
                    "ENQUEUE" -> Color(0xFF07C160)
                    "SENT" -> Color(0xFF1A1A1A)
                    else -> Color(0xFF888888)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeStr, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                    Text(entry.chatName.ifBlank { "(全局)" }, fontSize = 12.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                    Text(entry.node, fontSize = 11.sp, color = Color(0xFF888888))
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                    Text(entry.action, fontSize = 11.sp, color = actionColor, fontWeight = FontWeight.Bold)
                }
                Text(entry.reason, fontSize = 12.sp, color = Color(0xFF1A1A1A))
                val hasCtx = entry.warmth != 0f || entry.tension != 0f || entry.msSinceLastActive >= 0
                if (hasCtx) {
                    val gapStr = if (entry.msSinceLastActive < 0) "从未" else "${entry.msSinceLastActive / 3_600_000L}h"
                    Text("warmth=${"%.2f".format(entry.warmth)} · tension=${"%.2f".format(entry.tension)} · gap=$gapStr",
                        fontSize = 10.sp, color = Color(0xFF888888))
                }
            }
        }
    }
}

/**
 * P2.4: 同步失败统计面板。
 *
 * 展示 [com.aftglw.devapi.core.affect.AffectSyncRetryQueue] 的失败统计：
 * 成功率 / 成功次数 / 失败次数 / 可重试 / 永久失败 / 丢弃 + 最近失败/成功时间。
 * 纯内存统计，app 重启清空。
 */
@Composable
private fun FailureStatsPanel(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("同步失败统计 (P2.4)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("AffectiveField 同步失败统计，纯内存，app 重启清空", fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }
    var stats by remember { mutableStateOf<com.aftglw.devapi.core.affect.AffectSyncRetryQueue.FailureStats?>(null) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(onClick = {
            stats = com.aftglw.devapi.core.affect.AffectSyncRetryQueue.getFailureStats()
        }) { Text("刷新", color = Color(0xFF07C160), fontSize = 13.sp) }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
        TextButton(onClick = {
            com.aftglw.devapi.core.affect.AffectSyncRetryQueue.clearFailureStats()
            stats = null
            Toast.makeText(ctx, "已清空失败统计", Toast.LENGTH_SHORT).show()
        }) { Text("清空统计", color = Color(0xFFD14343), fontSize = 13.sp) }
    }
    val s = stats
    if (s == null) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("点击「刷新」加载失败统计", fontSize = 12.sp, color = Color(0xFF888888))
        }
    } else {
        val rate = s.successRate
        val ratePct = (rate * 100).toInt()
        val rateColor = when {
            rate > 0.9f -> Color(0xFF07C160)
            rate > 0.7f -> Color(0xFFD97706)
            else -> Color(0xFFD14343)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text("成功率", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
            Text("$ratePct%", fontSize = 14.sp, color = rateColor, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text("成功 ${s.successCount}  ·  失败 ${s.totalCount}  ·  可重试 ${s.retryableCount}  ·  永久 ${s.permanentCount}  ·  丢弃 ${s.droppedCount}",
                fontSize = 12.sp, color = Color(0xFF1A1A1A))
        }
        if (s.lastFailureAt > 0) {
            val minAgo = ((System.currentTimeMillis() - s.lastFailureAt) / 60_000L).toInt()
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                Text("最近失败", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
                Text("code=${s.lastFailureCode} · ${s.lastFailureReason} · ${minAgo}min 前",
                    fontSize = 12.sp, color = Color(0xFFD14343))
            }
        }
        if (s.lastSuccessAt > 0) {
            val minAgo = ((System.currentTimeMillis() - s.lastSuccessAt) / 60_000L).toInt()
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
                Text("最近成功", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(72.dp))
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
                Text("${minAgo}min 前", fontSize = 12.sp, color = Color(0xFF07C160))
            }
        }
    }
}

/**
 * P2.4: 关系场事件回放面板（只读）。
 *
 * 从 Desktop 只读拉取 affectEvents，不提供任何删除/发送按钮。
 * GET /api/v1/debug/affect?chatName=xxx → 解析 events 数组展示。
 */
@Composable
private fun EventReplayPanel(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("关系场事件回放 (P2.4)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("从 Desktop 只读拉取 affectEvents，默认只读不触发实际消息", fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }
    val sysPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    var desktopUrl by remember { mutableStateOf(sysPrefs.getString("wisp_affect_sync_url", "") ?: "") }
    var filter by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<ReplayEvent>>(emptyList()) }
    var retention by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = desktopUrl,
            onValueChange = { desktopUrl = it.trim(); sysPrefs.edit().putString("wisp_affect_sync_url", it.trim()).apply() },
            label = { Text("Desktop URL", fontSize = 12.sp) },
            placeholder = { Text("http://192.168.x.x:17890", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it.trim() },
            label = { Text("角色名筛选（可选）", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(
            enabled = !loading,
            onClick = {
                if (desktopUrl.isBlank()) {
                    error = "未配置 Desktop URL"
                    return@TextButton
                }
                if (!isSafeDesktopUrl(desktopUrl)) {
                    error = "URL 不安全（仅允许本地/私有 IP + 17890-17909 端口）"
                    return@TextButton
                }
                loading = true
                error = null
                scope.launch {
                    try {
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val token = sysPrefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
                            val urlBase = desktopUrl.trimEnd('/')
                            val url = if (filter.isNotBlank()) {
                                "$urlBase/api/v1/debug/affect?chatName=${java.net.URLEncoder.encode(filter, "UTF-8")}"
                            } else {
                                "$urlBase/api/v1/debug/affect"
                            }
                            val builder = okhttp3.Request.Builder().url(url).get().header("X-Wisp-Source", "android")
                            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
                            com.aftglw.devapi.network.HttpClient.clientFor(urlBase).newCall(builder.build()).execute().use { response ->
                                val body = response.body?.string() ?: ""
                                if (!response.isSuccessful) {
                                    throw java.io.IOException("HTTP ${response.code}: ${body.take(120)}")
                                }
                                val json = org.json.JSONObject(body)
                                retention = json.optString("retention", "")
                                val arr = json.optJSONArray("events") ?: org.json.JSONArray()
                                (0 until arr.length()).mapNotNull { i ->
                                    try {
                                        val e = arr.getJSONObject(i)
                                        val field = e.optJSONObject("affectiveField")
                                        val ra = e.optJSONObject("responseAssessment")
                                        val raFlags = if (ra != null) {
                                            val flags = mutableListOf<String>()
                                            if (ra.optBoolean("userDisclosed", false)) flags.add("userDisclosed")
                                            if (ra.optBoolean("userAskedQuestion", false)) flags.add("userAskedQuestion")
                                            if (ra.optBoolean("userSharedPositive", false)) flags.add("userSharedPositive")
                                            if (ra.optBoolean("aiRespondedToEmotion", false)) flags.add("aiRespondedToEmotion")
                                            if (ra.optBoolean("aiAnsweredContent", false)) flags.add("aiAnsweredContent")
                                            if (ra.optBoolean("aiCelebrated", false)) flags.add("aiCelebrated")
                                            flags.joinToString(", ")
                                        } else ""
                                        ReplayEvent(
                                            chatName = e.optString("chatName", ""),
                                            eventId = e.optString("eventId", ""),
                                            // 修复 P2: 服务端 receivedAt 是 ISO 字符串（如 "2026-07-21T12:00:00.000Z"），optLong 得到 0
                                            receivedAt = parseIsoTimestamp(e.optString("receivedAt", "")),
                                            tension = field?.optDouble("tension", 0.0)?.toFloat() ?: 0f,
                                            warmth = field?.optDouble("warmth", 0.0)?.toFloat() ?: 0f,
                                            anticipation = field?.optDouble("anticipation", 0.0)?.toFloat() ?: 0f,
                                            drift = field?.optDouble("drift", 0.0)?.toFloat() ?: 0f,
                                            pendingCount = e.optInt("pendingCount", 0),
                                            responseAssessmentFlags = raFlags,
                                        )
                                    } catch (_: Exception) { null }
                                }
                            }
                        }
                        events = result
                        loading = false
                        Toast.makeText(ctx, "拉取到 ${result.size} 条事件", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        loading = false
                        error = e.message?.take(80) ?: "拉取失败"
                    }
                }
            }
        ) { Text(if (loading) "拉取中..." else "拉取事件", color = Color(0xFF07C160), fontSize = 13.sp) }
    }
    if (error != null) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text(error!!, fontSize = 12.sp, color = Color(0xFFD14343))
        }
    }
    if (retention.isNotBlank()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text("服务端 retention: $retention", fontSize = 11.sp, color = Color(0xFF888888))
        }
    }
    if (events.isEmpty() && error == null && !loading) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("点击「拉取事件」从 Desktop 加载 affectEvents（只读）", fontSize = 12.sp, color = Color(0xFF888888))
        }
    } else {
        events.take(20).forEach { ev ->
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
                val timeStr = if (ev.receivedAt > 0) {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ev.receivedAt))
                } else "--"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeStr, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                    Text(ev.chatName, fontSize = 12.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                    Text(ev.eventId.ifBlank { "(no eventId)" }, fontSize = 11.sp, color = Color(0xFF888888))
                }
                Text("tension=${"%.2f".format(ev.tension)} · warmth=${"%.2f".format(ev.warmth)} · anticipation=${"%.2f".format(ev.anticipation)} · drift=${"%.2f".format(ev.drift)}",
                    fontSize = 11.sp, color = Color(0xFF1A1A1A))
                Text("pendingCount=${ev.pendingCount}", fontSize = 11.sp, color = Color(0xFF888888))
                if (ev.responseAssessmentFlags.isNotBlank()) {
                    Text("responseAssessment: ${ev.responseAssessmentFlags}", fontSize = 10.sp, color = Color(0xFF888888))
                }
            }
        }
    }
}

/**
 * P2.4: 数据控制面板。
 *
 * - 按角色清理（[com.aftglw.devapi.core.affect.AffectiveEngine.clearForChat]）— 需确认
 * - 按时间范围删除服务端事件（POST /api/v1/debug/affect/delete）— 需确认
 * - 手动 GC（POST /api/v1/debug/affect/gc）
 * - 保留期限展示（从 /api/v1/debug/affect 的 retention 字段读取，只读）
 */
@Composable
private fun DataControlPanel(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("数据控制 (P2.4)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text("删除操作需明确确认，保留 eventId 幂等记录", fontSize = 10.sp, color = Color(0xFFAAAAAA))
    }
    val sysPrefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)

    // ── 按角色清理 ──
    var clearChatName by remember { mutableStateOf("") }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("角色名", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.width(56.dp))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
        OutlinedTextField(
            value = clearChatName,
            onValueChange = { clearChatName = it.trim() },
            placeholder = { Text("输入角色名", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(
            enabled = clearChatName.isNotBlank(),
            onClick = { showClearChatConfirm = true }
        ) { Text("清除该角色全部数据", color = Color(0xFFD14343), fontSize = 13.sp) }
    }
    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text("确认清除") },
            text = { Text("将清除 $clearChatName 的全部情绪场数据（AffectiveField + pendingEvents + 日志），此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearChatConfirm = false
                    val target = clearChatName
                    scope.launch {
                        try {
                            com.aftglw.devapi.core.affect.AffectiveEngine.clearForChat(ctx, target)
                            Toast.makeText(ctx, "已清除 $target 的全部数据", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "清除失败: ${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认清除", color = Color(0xFFD14343)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) { Text("取消") }
            }
        )
    }

    // ── 按时间范围删除服务端事件 ──
    var desktopUrl by remember { mutableStateOf(sysPrefs.getString("wisp_affect_sync_url", "") ?: "") }
    var sinceInput by remember { mutableStateOf("") }
    var untilInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = desktopUrl,
            onValueChange = { desktopUrl = it.trim(); sysPrefs.edit().putString("wisp_affect_sync_url", it.trim()).apply() },
            label = { Text("Desktop URL", fontSize = 12.sp) },
            placeholder = { Text("http://192.168.x.x:17890", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = sinceInput,
            onValueChange = { sinceInput = it },
            label = { Text("since (ISO 或 Xh前)", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
        OutlinedTextField(
            value = untilInput,
            onValueChange = { untilInput = it },
            label = { Text("until (可选)", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(onClick = {
            if (desktopUrl.isBlank()) {
                deleteResult = "未配置 Desktop URL"
                return@TextButton
            }
            if (!isSafeDesktopUrl(desktopUrl)) {
                deleteResult = "URL 不安全（仅允许本地/私有 IP + 17890-17909 端口）"
                return@TextButton
            }
            showDeleteConfirm = true
        }) { Text("删除服务端事件", color = Color(0xFFD14343), fontSize = 13.sp) }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
        TextButton(onClick = {
            if (desktopUrl.isBlank()) {
                deleteResult = "未配置 Desktop URL"
                return@TextButton
            }
            if (!isSafeDesktopUrl(desktopUrl)) {
                deleteResult = "URL 不安全（仅允许本地/私有 IP + 17890-17909 端口）"
                return@TextButton
            }
            scope.launch {
                try {
                    val msg = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val token = sysPrefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
                        val urlBase = desktopUrl.trimEnd('/')
                        val request = if (token.isBlank()) {
                            com.aftglw.devapi.network.HttpClient.postJson("$urlBase/api/v1/debug/affect/gc", "{}", "X-Wisp-Source" to "android")
                        } else {
                            com.aftglw.devapi.network.HttpClient.postJson("$urlBase/api/v1/debug/affect/gc", "{}", "Authorization" to "Bearer $token", "X-Wisp-Source" to "android")
                        }
                        com.aftglw.devapi.network.HttpClient.clientFor(urlBase).newCall(request).execute().use { response ->
                            val body = response.body?.string() ?: ""
                            if (response.isSuccessful) "GC 完成: ${body.take(80)}" else "GC 失败: HTTP ${response.code}"
                        }
                    }
                    deleteResult = msg
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    deleteResult = "GC 失败: ${e.message?.take(60)}"
                    Toast.makeText(ctx, "GC 失败", Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("手动 GC", color = Color(0xFFD97706), fontSize = 13.sp) }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除服务端事件") },
            text = { Text("将从 Desktop 删除 since=${sinceInput.ifBlank { "(空)" }} until=${untilInput.ifBlank { "(空)" }} 范围内的 affectEvents。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        try {
                            // 修复 P1: 客户端时间参数校验 — 输入非空但解析失败时拒绝请求
                            val sinceMs = parseTimeInput(sinceInput)
                            val untilMs = parseTimeInput(untilInput)
                            if (sinceInput.isNotBlank() && sinceMs == null) {
                                Toast.makeText(ctx, "since 格式无效，请用 '2026-07-21T12:00:00' 或 '3h前'", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            if (untilInput.isNotBlank() && untilMs == null) {
                                Toast.makeText(ctx, "until 格式无效，请用 '2026-07-21T12:00:00' 或 '3h前'", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            if (sinceMs != null && untilMs != null && sinceMs > untilMs) {
                                Toast.makeText(ctx, "since 不能晚于 until", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val msg = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val token = sysPrefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
                                val urlBase = desktopUrl.trimEnd('/')
                                val body = org.json.JSONObject().apply {
                                    if (sinceMs != null) put("since", sinceMs)
                                    if (untilMs != null) put("until", untilMs)
                                }
                                val request = if (token.isBlank()) {
                                    com.aftglw.devapi.network.HttpClient.postJson("$urlBase/api/v1/debug/affect/delete", body.toString(), "X-Wisp-Source" to "android")
                                } else {
                                    com.aftglw.devapi.network.HttpClient.postJson("$urlBase/api/v1/debug/affect/delete", body.toString(), "Authorization" to "Bearer $token", "X-Wisp-Source" to "android")
                                }
                                com.aftglw.devapi.network.HttpClient.clientFor(urlBase).newCall(request).execute().use { response ->
                                    val respBody = response.body?.string() ?: ""
                                    if (response.isSuccessful) "删除完成: ${respBody.take(80)}" else "删除失败: HTTP ${response.code}"
                                }
                            }
                            deleteResult = msg
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            deleteResult = "删除失败: ${e.message?.take(60)}"
                            Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认删除", color = Color(0xFFD14343)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
    if (deleteResult != null) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text(deleteResult!!, fontSize = 12.sp, color = Color(0xFF888888))
        }
    }

    // ── 保留期限展示（只读，从 /api/v1/debug/affect 的 retention 字段读取）──
    var retentionText by remember { mutableStateOf<String?>(null) }
    var retentionLoading by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 4.dp)) {
        TextButton(
            enabled = !retentionLoading,
            onClick = {
                if (desktopUrl.isBlank()) {
                    retentionText = "未配置 Desktop URL"
                    return@TextButton
                }
                if (!isSafeDesktopUrl(desktopUrl)) {
                    retentionText = "URL 不安全"
                    return@TextButton
                }
                retentionLoading = true
                scope.launch {
                    try {
                        val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val token = sysPrefs.getString("wisp_affect_sync_token", "")?.trim().orEmpty()
                            val urlBase = desktopUrl.trimEnd('/')
                            val builder = okhttp3.Request.Builder().url("$urlBase/api/v1/debug/affect").get().header("X-Wisp-Source", "android")
                            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
                            com.aftglw.devapi.network.HttpClient.clientFor(urlBase).newCall(builder.build()).execute().use { response ->
                                val body = response.body?.string() ?: ""
                                if (response.isSuccessful) {
                                    val json = org.json.JSONObject(body)
                                    "retention: ${json.optString("retention", "(未设置)")}"
                                } else {
                                    "读取失败: HTTP ${response.code}"
                                }
                            }
                        }
                        retentionText = r
                    } catch (e: Exception) {
                        retentionText = "读取失败: ${e.message?.take(50)}"
                    }
                    retentionLoading = false
                }
            }
        ) { Text(if (retentionLoading) "读取中..." else "读取保留期限", color = Color(0xFF07C160), fontSize = 13.sp) }
    }
    if (retentionText != null) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 2.dp)) {
            Text(retentionText!!, fontSize = 12.sp, color = Color(0xFF1A1A1A))
        }
    }
}

// ── P2.4 辅助函数 ──

/** Desktop URL 安全校验（与 ProactiveApprovalQueue.isSafeDesktopUrl 逻辑一致）。 */
private fun isSafeDesktopUrl(value: String): Boolean {
    return try {
        val uri = java.net.URI(value)
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase() ?: return false
        val port = uri.port
        val localHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
        val privateV4 = host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
        val privateV6 = host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        uri.scheme in listOf("http", "https") && port in 17890..17909 && (localHost || privateV4 || privateV6)
    } catch (_: Exception) {
        false
    }
}

/** 解析时间输入（ISO 格式 "yyyy-MM-dd'T'HH:mm:ss" / "yyyy-MM-dd HH:mm:ss" 或 "Xh前"）为毫秒时间戳。 */
private fun parseTimeInput(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val hMatch = Regex("^(\\d+)h前?$").find(trimmed)
    if (hMatch != null) {
        val hours = hMatch.groupValues[1].toLong()
        return System.currentTimeMillis() - hours * 3_600_000L
    }
    return try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(trimmed)?.time
    } catch (_: Exception) {
        try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).parse(trimmed)?.time
        } catch (_: Exception) { null }
    }
}

/**
 * 修复 P2: 解析服务端 ISO 字符串时间戳。
 * 服务端 receivedAt 格式为 "2026-07-21T12:00:00.123Z"（toISOString() 输出）。
 * 解析失败返回 0（UI 显示 "--"）。
 */
private fun parseIsoTimestamp(iso: String): Long {
    if (iso.isBlank()) return 0L
    return try {
        // 优先用 ISO 8601 with timezone（带 Z 后缀）
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(iso)?.time ?: 0L
    } catch (_: Exception) {
        try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time ?: 0L
        } catch (_: Exception) {
            try {
                // 兜底：无时区的本地时间
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(iso)?.time ?: 0L
            } catch (_: Exception) { 0L }
        }
    }
}
