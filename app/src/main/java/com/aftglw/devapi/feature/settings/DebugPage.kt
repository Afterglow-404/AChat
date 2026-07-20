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
    }
}
