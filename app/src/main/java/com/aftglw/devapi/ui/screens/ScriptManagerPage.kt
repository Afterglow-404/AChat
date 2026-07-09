package com.aftglw.devapi.ui.screens
import android.content.Context

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.*
import com.aftglw.devapi.ui.theme.*

private data class ScriptStatus(
    val id: String,
    val name: String,
    val description: String,
    val characterPrompt: String,
    val isAdventure: Boolean,
    val unlockConditions: List<UnlockCondition>,
    val unlocked: Boolean,
    val completed: Boolean,
    val inProgress: Boolean
)

/**
 * 剧本管理器：查看所有剧本状态、重置进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptManagerPage(
    scripts: List<ScriptLoader.ScriptInfo>,
    onPlay: (ScriptLoader.ScriptInfo) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
    var refreshKey by remember { mutableIntStateOf(0) }

    val statuses = remember(refreshKey, scripts) {
        scripts.map { info ->
            val unlocked = ScriptProgress.isScriptUnlocked(ctx, info.unlockConditions)
            val completed = ScriptProgress.isCompleted(ctx, info.id)
            val saveKey = "script_${info.name}"
            val ch = prefs.getString("${saveKey}_chapter", null)
            val ev = prefs.getInt("${saveKey}_event", -1)
            ScriptStatus(
                id = info.id,
                name = info.name,
                description = info.description,
                characterPrompt = info.characterPrompt,
                isAdventure = info.isAdventure,
                unlockConditions = info.unlockConditions,
                unlocked = unlocked,
                completed = completed,
                inProgress = unlocked && !completed && ch != null && ev >= 0
            )
        }
    }

    fun resetOne(id: String, name: String) {
        val saveKey = "script_$name"
        prefs.edit()
            .remove("${saveKey}_chapter")
            .remove("${saveKey}_event")
            .apply()
        ScriptProgress.resetScript(ctx, id)
        refreshKey++
    }

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("剧本管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AchatTheme.colors.surface)
        )
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        if (statuses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无剧本", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(statuses, key = { i, _ -> i }) { _, st ->
                    ManagerCard(
                        st = st,
                        onClick = { if (st.unlocked) onPlay(scripts.first { it.id == st.id }) },
                        onReset = { resetOne(st.id, st.name) }
                    )
                }
            }

            // 底部：重置全部
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                var showResetAll by remember { mutableStateOf(false) }
                if (showResetAll) {
                    AlertDialog(
                        onDismissRequest = { showResetAll = false },
                        title = { Text("重置所有剧本进度？", fontSize = 16.sp) },
                        text = { Text("所有通关记录和存档将被清除，不可撤销。", fontSize = 14.sp) },
                        confirmButton = {
                            TextButton(onClick = {
                                ScriptProgress.resetAll(ctx)
                                for (st in statuses) {
                                    prefs.edit()
                                        .remove("script_${st.name}_chapter")
                                        .remove("script_${st.name}_event")
                                        .apply()
                                }
                                showResetAll = false
                                refreshKey++
                            }) { Text("重置", color = Color(0xFFE53935)) }
                        },
                        dismissButton = { TextButton(onClick = { showResetAll = false }) { Text("取消") } }
                    )
                }
                TextButton(onClick = { showResetAll = true }) {
                    Text("重置所有进度", fontSize = 13.sp, color = Color(0xFFE53935))
                }
            }
        }
    }
}

@Composable
private fun ManagerCard(st: ScriptStatus, onClick: () -> Unit, onReset: () -> Unit) {
    val c = AchatTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = st.unlocked) { onClick() },
        color = c.surface
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.name, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = if (st.unlocked) c.onSurface else c.onSurface.copy(alpha = 0.3f))
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(st)
                }
                if (st.description.isNotBlank()) {
                    Text(st.description, fontSize = 12.sp,
                        color = c.onSurface.copy(alpha = if (st.unlocked) 0.4f else 0.2f),
                        modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                }
                if (!st.unlocked && st.unlockConditions.isNotEmpty()) {
                    val need = st.unlockConditions.joinToString("、") { it.adventureFolder.ifEmpty { "?" } }
                    Text("需要先完成: $need", fontSize = 11.sp,
                        color = c.onSurface.copy(alpha = 0.3f), modifier = Modifier.padding(top = 2.dp))
                }
            }

            if (st.unlocked && (st.completed || st.inProgress)) {
                var showReset by remember { mutableStateOf(false) }
                if (showReset) {
                    AlertDialog(
                        onDismissRequest = { showReset = false },
                        title = { Text("重置「${st.name}」？", fontSize = 15.sp) },
                        text = { Text("进度和通关记录将被清除。", fontSize = 14.sp) },
                        confirmButton = {
                            TextButton(onClick = { showReset = false; onReset() }) { Text("重置", color = Color(0xFFE53935)) }
                        },
                        dismissButton = { TextButton(onClick = { showReset = false }) { Text("取消") } }
                    )
                }
                TextButton(onClick = { showReset = true }, modifier = Modifier.heightIn(min = 32.dp)) {
                    Text("重置", fontSize = 12.sp, color = Color(0xFFE53935))
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = c.divider)
}

@Composable
private fun StatusBadge(st: ScriptStatus) {
    val pageText = AchatTheme.colors.onSurface
    val (text, bgColor, fgColor) = when {
        !st.unlocked -> Triple("未解锁", AchatTheme.colors.divider, pageText.copy(alpha = 0.3f))
        st.completed -> Triple("已完成", Color(0xFF07C160).copy(alpha = 0.15f), Color(0xFF07C160))
        st.inProgress -> Triple("进行中", Color(0xFFFF9800).copy(alpha = 0.15f), Color(0xFFFF9800))
        else -> Triple("未开始", AchatTheme.colors.divider.copy(alpha = 0.5f), pageText.copy(alpha = 0.4f))
    }
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fgColor,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bgColor).padding(horizontal = 6.dp, vertical = 2.dp))
}
