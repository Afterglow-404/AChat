package com.aftglw.devapi.feature.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.character.CharacterFields
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.feature.settings.SubPageScaffold
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 字段化角色编辑器 — 把 persona 拆成 5 个字段分别编辑。
 * 保存时拼装回 persona 字符串，写回 chats 表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorPage(
    chatName: String,
    persona: String,
    onBack: () -> Unit,
    onSaved: (newPersona: String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val initial = remember { CharacterFields.fromPersona(persona) }
    var description by remember { mutableStateOf(initial.description) }
    var personality by remember { mutableStateOf(initial.personality) }
    var scenario by remember { mutableStateOf(initial.scenario) }
    var mesExample by remember { mutableStateOf(initial.mesExample) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }

    SubPageScaffold("编辑角色", onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "角色名称：$chatName",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AchatTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            FieldSection(
                title = "角色描述",
                hint = "角色的基本介绍，例如：名叫小白的猫娘，18岁，和主人住在一起",
                value = description,
                onChange = { description = it }
            )
            FieldSection(
                title = "性格特点",
                hint = "例如：傲娇、爱撒娇、嘴硬心软、害怕打雷",
                value = personality,
                onChange = { personality = it }
            )
            FieldSection(
                title = "场景设定",
                hint = "对话发生的背景，例如：现代都市公寓，雨天傍晚",
                value = scenario,
                onChange = { scenario = it }
            )
            FieldSection(
                title = "示例对话",
                hint = "格式：\n用户：你好\n角色：哼，才不是特意等你回来的喵！",
                value = mesExample,
                onChange = { mesExample = it },
                minLines = 4
            )
            FieldSection(
                title = "系统指令（高级）",
                hint = "自定义 system prompt 指令，例如：请用第一人称回复，每句不超过20字",
                value = systemPrompt,
                onChange = { systemPrompt = it }
            )

            Spacer(Modifier.height(24.dp))

            // 预览拼装结果
            val previewPersona = remember(description, personality, scenario, mesExample, systemPrompt) {
                CharacterFields(description, personality, scenario, mesExample, systemPrompt).toPersona()
            }
            if (previewPersona.isNotBlank()) {
                Text(
                    "预览（拼装后的 persona）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AchatTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Surface(
                    color = AchatTheme.colors.surface,
                    shape = AchatTheme.shapes.card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        previewPersona,
                        fontSize = 12.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // 保存按钮
            Button(
                onClick = {
                    val newPersona = CharacterFields(
                        description, personality, scenario, mesExample, systemPrompt
                    ).toPersona()
                    if (savePersona(ctx, chatName, newPersona)) {
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        onSaved(newPersona)
                        onBack()
                    } else {
                        Toast.makeText(ctx, "保存失败：未找到角色", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160))
            ) {
                Text("保存", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            // 清空按钮
            TextButton(
                onClick = {
                    description = ""
                    personality = ""
                    scenario = ""
                    mesExample = ""
                    systemPrompt = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空所有字段", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun FieldSection(
    title: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    minLines: Int = 2
) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = AchatTheme.colors.onSurface,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 56).dp),
        placeholder = { Text(hint, fontSize = 13.sp, color = Color.Gray) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF07C160),
            unfocusedBorderColor = Color(0xFFE0E0E0)
        )
    )
}

/** 把 persona 写回 chats 表中匹配 name 的项，返回是否找到并保存成功 */
private fun savePersona(ctx: Context, chatName: String, persona: String): Boolean = runBlocking {
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).chatDao()
        val e = dao.getAll().firstOrNull { it.name == chatName } ?: return@withContext false
        dao.upsert(e.copy(persona = persona))
        true
    }
}
