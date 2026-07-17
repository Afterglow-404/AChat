package com.aftglw.devapi.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.*
import com.aftglw.devapi.ui.theme.*

// ===== 布局常量 =====
private const val NODE_W = 340
private const val NODE_H = 150
private const val COL_W = 420
private const val GAP = 28
private const val PAD = 60

private data class NodeInfo(
    val id: String,
    val name: String,
    val desc: String,
    val column: Int,
    val row: Int,
    val status: String,   // completed / in_progress / unlocked / locked
    val unlocked: Boolean,
    val prereqFolder: String = "",
    val unlockText: String = ""
)

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
    var zoom by remember { mutableFloatStateOf(1f) }

    // 构建节点列表 + 状态 + 列分配
    val nodes = remember(refreshKey, scripts) {
        buildNodes(ctx, prefs, scripts)
    }

    // 画布尺寸
    val colCount = (nodes.maxOfOrNull { it.column } ?: 0) + 1
    val maxInCol = (0 until colCount).maxOfOrNull { col ->
        nodes.count { it.column == col }
    } ?: 1
    val canvasW = PAD * 2 + colCount * COL_W
    val canvasH = PAD * 2 + maxInCol * NODE_H + (maxInCol - 1) * GAP

    // 每个节点的像素位置
    data class Pos(val x: Float, val y: Float)
    val positions = remember(nodes) {
        val cols = nodes.groupBy { it.column }
        val map = mutableMapOf<String, Pos>()
        cols.forEach { (col, items) ->
            val totalH = items.size * NODE_H + (items.size - 1) * GAP
            val startY = (canvasH - totalH) / 2f
            items.forEachIndexed { row, node ->
                map[node.id] = Pos(
                    x = PAD + col * COL_W.toFloat(),
                    y = startY + row * (NODE_H + GAP)
                )
            }
        }
        map
    }

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        // 顶栏
        CenterAlignedTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("羁绊图谱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("${nodes.count { it.status == "completed" }}/${nodes.size}",
                        fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
            actions = {
                // 缩放按钮
                val btnSize = 32.dp
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { zoom = (zoom + 0.15f).coerceAtMost(2f) },
                        modifier = Modifier.size(btnSize), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 16.sp) }
                    Spacer(Modifier.width(4.dp))
                    Text("${(zoom * 100).toInt()}%", fontSize = 10.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = { zoom = (zoom - 0.15f).coerceAtLeast(0.4f) },
                        modifier = Modifier.size(btnSize), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 16.sp) }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AchatTheme.colors.surface)
        )
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        // ===== 图谱主体 =====
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无剧本", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f))
            }
        } else {
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()

            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(Unit) { detectTransformGestures { _, _, gestureZoom, _ -> zoom = (zoom * gestureZoom).coerceIn(0.4f, 2f) } }
            ) {
                Box(
                    Modifier.horizontalScroll(hScroll).verticalScroll(vScroll)
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f))
                ) {
                    Box(Modifier.size(canvasW.dp, canvasH.dp)) {
                        // 连线层
                        Canvas(Modifier.fillMaxSize()) {
                            for (node in nodes) {
                                if (node.prereqFolder.isBlank()) continue
                                val from = positions[node.prereqFolder] ?: continue
                                val to = positions[node.id] ?: continue
                                val sx = from.x + NODE_W
                                val sy = from.y + NODE_H / 2f
                                val ex = to.x
                                val ey = to.y + NODE_H / 2f
                                val ctrlOff = (ex - sx).coerceAtLeast(40f) * 0.45f
                                val path = Path().apply {
                                    moveTo(sx, sy)
                                    cubicTo(sx + ctrlOff, sy, ex - ctrlOff, ey, ex, ey)
                                }
                                val lineColor = when (node.status) {
                                    "completed" -> Color(0xFF4ADE80)
                                    "locked" -> Color.Gray.copy(alpha = 0.4f)
                                    else -> Color(0xFF07C160)
                                }
                                drawPath(path, lineColor, style = Stroke(width = if (node.status == "locked") 1.5f else 2.5f))
                                // 箭头
                                val arrowSize = 6f
                                val angle = kotlin.math.atan2(ey - sy, ex - sx)
                                val aPath = Path().apply {
                                    moveTo(ex, ey)
                                    lineTo(ex - arrowSize * kotlin.math.cos(angle - 0.4f), ey - arrowSize * kotlin.math.sin(angle - 0.4f))
                                    lineTo(ex - arrowSize * kotlin.math.cos(angle + 0.4f), ey - arrowSize * kotlin.math.sin(angle + 0.4f))
                                    close()
                                }
                                drawPath(aPath, lineColor)
                            }
                        }

                        // 节点层
                        for (node in nodes) {
                            val pos = positions[node.id] ?: continue
                            NodeCard(
                                node = node,
                                modifier = Modifier.offset(pos.x.dp, pos.y.dp).width(NODE_W.dp),
                                onClick = {
                                    if (node.unlocked) onPlay(scripts.first { it.id == node.id })
                                },
                                onReset = {
                                    val sk = "script_${node.name}"
                                    prefs.edit().remove("${sk}_chapter").remove("${sk}_event").apply()
                                    ScriptProgress.resetScript(ctx, node.id)
                                    refreshKey++
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeCard(node: NodeInfo, modifier: Modifier = Modifier, onClick: () -> Unit, onReset: () -> Unit) {
    val c = AchatTheme.colors
    val borderColor = when (node.status) {
        "completed" -> Color(0xFF4ADE80).copy(alpha = 0.6f)
        "locked" -> Color.Gray.copy(alpha = 0.2f)
        else -> Color(0xFF07C160).copy(alpha = 0.4f)
    }
    val bgColor = when (node.status) {
        "completed" -> c.surface
        "locked" -> c.surface.copy(alpha = 0.5f)
        else -> c.surface
    }
    val alpha = if (node.unlocked) 1f else 0.4f

    Surface(
        modifier = modifier.height(NODE_H.dp).clickable(enabled = node.unlocked) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        tonalElevation = if (node.status == "locked") 0.dp else 2.dp
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(when (node.status) {
                            "completed" -> Color(0xFF4ADE80).copy(alpha = 0.15f)
                            "locked" -> c.divider
                            else -> Color(0xFF07C160).copy(alpha = 0.12f)
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (node.status) { "completed" -> "✓"; "locked" -> "🔒"; else -> "📖" },
                        fontSize = 18.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = c.onSurface.copy(alpha = alpha), maxLines = 1)
                    StatusBadgeGraph(node.status, c)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(node.desc, fontSize = 11.sp, color = c.onSurface.copy(alpha = alpha * 0.45f),
                maxLines = 2, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
            if (node.status == "locked" && node.unlockText.isNotBlank()) {
                Text("🔒 $node.unlockText", fontSize = 10.sp, color = c.onSurface.copy(alpha = 0.3f))
            } else if (node.status == "in_progress" || node.status == "completed") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    var showReset by remember { mutableStateOf(false) }
                    if (showReset) {
                        AlertDialog(
                            onDismissRequest = { showReset = false },
                            title = { Text("重置「${node.name}」？", fontSize = 15.sp) },
                            text = { Text("通关记录和存档将被清除。", fontSize = 14.sp) },
                            confirmButton = { TextButton(onClick = { showReset = false; onReset() }) { Text("重置", color = Color(0xFFE53935)) } },
                            dismissButton = { TextButton(onClick = { showReset = false }) { Text("取消") } }
                        )
                    }
                    TextButton(onClick = { showReset = true }, modifier = Modifier.heightIn(min = 24.dp)) {
                        Text("重置", fontSize = 10.sp, color = Color(0xFFE53935).copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadgeGraph(status: String, c: AchatColors) {
    val (text, bg, fg) = when (status) {
        "completed" -> Triple("已完成", Color(0xFF4ADE80).copy(alpha = 0.15f), Color(0xFF4ADE80))
        "in_progress" -> Triple("进行中", Color(0xFFFF9800).copy(alpha = 0.15f), Color(0xFFFF9800))
        "unlocked" -> Triple("可游玩", Color(0xFF07C160).copy(alpha = 0.12f), Color(0xFF07C160))
        else -> Triple("未解锁", c.divider, c.onSurface.copy(alpha = 0.3f))
    }
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp))
}

// ===== 状态计算 =====
private fun buildNodes(ctx: Context, prefs: android.content.SharedPreferences, scripts: List<ScriptLoader.ScriptInfo>): List<NodeInfo> {
    val statusMap = mutableMapOf<String, String>()   // id → status
    val prereqMap = mutableMapOf<String, String>()   // id → prereq folder

    val sorted = scripts.sortedBy { it.order }

    for (info in sorted) {
        val unlocked = ScriptProgress.isScriptUnlocked(ctx, info.unlockConditions)
        val completed = ScriptProgress.isCompleted(ctx, info.id)
        val inProg = unlocked && !completed && prefs.contains("script_${info.name}_chapter")
        val status = when {
            completed -> "completed"
            inProg -> "in_progress"
            unlocked -> "unlocked"
            else -> "locked"
        }
        statusMap[info.id] = status

        val prereq = info.unlockConditions.firstOrNull { it.type == "adventure_completed" }
        if (prereq != null) prereqMap[info.id] = prereq.adventureFolder
    }

    // 列分配：从无依赖的开始，按依赖链深度分列
    val columnOf = mutableMapOf<String, Int>()
    fun getColumn(id: String): Int {
        columnOf[id]?.let { return it }
        val prereq = prereqMap[id]
        if (prereq == null || prereq !in statusMap) {
            columnOf[id] = 0; return 0
        }
        val col = getColumn(prereq) + 1
        columnOf[id] = col
        return col
    }
    for (info in sorted) getColumn(info.id)

    // 每列内的行号
    val rowCounters = mutableMapOf<Int, Int>()
    val rowOf = mutableMapOf<String, Int>()

    return sorted.map { info ->
        val col = columnOf[info.id] ?: 0
        val row = rowCounters.getOrDefault(col, 0).also { rowCounters[col] = it + 1 }
        rowOf[info.id] = row

        val prereq = prereqMap[info.id]
        val unlockText = if ((statusMap[info.id] ?: "locked") == "locked") {
            info.unlockConditions.joinToString("、") { cond ->
                when (cond.type) {
                    "adventure_completed" -> "完成「${cond.adventureFolder}」"
                    else -> cond.type
                }
            }
        } else ""

        NodeInfo(
            id = info.id,
            name = info.name,
            desc = info.description,
            column = col,
            row = row,
            status = statusMap[info.id] ?: "locked",
            unlocked = statusMap[info.id] != "locked",
            prereqFolder = prereq ?: "",
            unlockText = unlockText
        )
    }
}
