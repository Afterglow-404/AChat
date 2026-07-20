package com.aftglw.devapi.feature.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.buildCustomTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ManageRolesPage(
    onBack: () -> Unit,
    newChatName: String, onNewChatNameChange: (String) -> Unit,
    newChatPersona: String, onNewChatPersonaChange: (String) -> Unit,
    newChatAvatarUri: String,
    onPickAvatar: () -> Unit, onClearAvatar: () -> Unit,
    onCreateChat: () -> Unit
) {
    val ctx = LocalContext.current
    SubPageScaffold("管理角色", onBack) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Color(0xFFF0F0F0), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    if (newChatAvatarUri.isNotEmpty()) {
                        var bmp by remember(newChatAvatarUri) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(newChatAvatarUri) {
                            bmp = withContext(Dispatchers.IO) {
                                try { BitmapFactory.decodeFile(newChatAvatarUri)?.asImageBitmap() } catch (e: Exception) { Log.w("ManageRolesPage", "avatar decode failed", e); null }
                            }
                        }
                        val bmpVal = bmp
                        if (bmpVal != null) Image(bmpVal, null, Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
                    } else Text("+", fontSize = 20.sp, color = Color.Gray)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onPickAvatar) { Text(if (newChatAvatarUri.isNotEmpty()) "更换头像" else "选择头像", color = Color(0xFF07C160)) }
                if (newChatAvatarUri.isNotEmpty()) TextButton(onClick = onClearAvatar) { Text("清除", color = Color.Gray) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newChatName, onNewChatNameChange, Modifier.fillMaxWidth(), placeholder = { Text("对话名称") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newChatPersona, onNewChatPersonaChange, Modifier.fillMaxWidth().height(100.dp),
                placeholder = { Text("角色人设，例如：你是一个傲娇的猫娘喵，说话带喵...") }, maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF07C160), unfocusedBorderColor = Color(0xFFE0E0E0)))
            Spacer(Modifier.height(4.dp))
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val importSkillLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    try {
                        val raw = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
                        val chatName = newChatName.trim().ifEmpty { "imported" }
                        // 1. 去掉 YAML frontmatter（--- ... ---）
                        val cleaned = raw.replace(Regex("""^---[\s\S]*?---\n*"""), "").trim()
                        // 2. 分离记忆（PART A）和人设（PART B）
                        val parts = cleaned.split(Regex("""## PART [AB]"""))
                        val personaText = parts.getOrElse(2) { parts.getOrElse(1) { cleaned } }.trim()
                        val memoryText = parts.getOrElse(1) { "" }
                        // 3. 填人设（只填 PART B，不含运行规则）
                        val personaClean = personaText.split("## 运行规则").firstOrNull()?.trim() ?: personaText
                        onNewChatPersonaChange(personaClean)
                        // 4. 从记忆段提取具体条目
                        val memories = memoryText.lines().filter { it.trim().startsWith("- ") || it.trim().startsWith("• ") }
                            .map { it.trim().removePrefix("- ").removePrefix("• ") }.filter { it.length > 5 }
                        com.aftglw.devapi.core.memory.MemoryStore.init(ctx)
                        // MemoryStore.save 已 suspend 化（含 embedding 网络调用），切 IO 异步执行
                        val memoriesToSave = memories.take(15)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    memoriesToSave.forEach { com.aftglw.devapi.core.memory.MemoryStore.save(ctx, it, "skill:$chatName") }
                                }
                            } catch (e: Exception) {
                                Log.w("ManageRolesPage", "memory save after import failed", e)
                            }
                            android.widget.Toast.makeText(ctx, "Skill 导入成功（${memories.size} 条记忆）", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("ManageRolesPage", "import skill failed", e)
                        android.widget.Toast.makeText(ctx, "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            TextButton(onClick = { importSkillLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("导入 Skill（从 ex-skill 文件）", fontSize = 13.sp, color = Color(0xFF888888))
            }
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCreateChat, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)), shape = CircleShape) { Text("创建角色") }
        }
    }
}
