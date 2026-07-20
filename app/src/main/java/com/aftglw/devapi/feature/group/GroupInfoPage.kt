package com.aftglw.devapi.feature.group

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.core.character.BuiltInCharacterLoader
import com.aftglw.devapi.model.GroupChat
import com.aftglw.devapi.model.GroupChatMode
import com.aftglw.devapi.ui.theme.AchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群信息页 —— 重命名 / 成员管理 / 解散群。
 *
 * 任何修改都会通过 [onGroupChanged] 或 [onGroupDeleted] 回调通知上层，
 * 由上层负责刷新 GroupChatScreen 持有的 [GroupChat] 状态。
 */
@Composable
fun GroupInfoPage(
    group: GroupChat,
    onBack: () -> Unit,
    onGroupChanged: (GroupChat) -> Unit,
    onGroupDeleted: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentGroup by remember { mutableStateOf(group) }

    fun commitGroup(updated: GroupChat) {
        currentGroup = updated
        onGroupChanged(updated)
        scope.launch {
            withContext(Dispatchers.IO) {
                GroupChatManager.saveGroup(ctx, updated)
            }
        }
    }

    // 重命名对话框
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(group.name) }

    // 添加成员对话框
    var showAddDialog by remember { mutableStateOf(false) }

    var showModeDialog by remember { mutableStateOf(false) }

    // 解散确认
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 当前群成员 + 人设预览（每次 currentGroup 变化时刷新）
    var memberInfo by remember { mutableStateOf<List<Triple<String, androidx.compose.ui.graphics.ImageBitmap?, String>>>(emptyList()) }
    LaunchedEffect(currentGroup.members, currentGroup.memberEnabled) {
        val loaded = withContext(Dispatchers.IO) {
            currentGroup.members.map { name ->
                val avatarUri = GroupChatManager.getMemberAvatarUri(ctx, name)
                val avatar = if (avatarUri.isNotEmpty()) {
                    BuiltInCharacterLoader.loadAvatarBitmap(ctx, avatarUri)?.asImageBitmap()
                } else null
                val persona = GroupChatManager.getMemberPersona(ctx, name)
                Triple(name, avatar, persona)
            }
        }
        memberInfo = loaded
    }

    // 可添加的候选成员（去重当前已加入的）
    var availableCandidates by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(currentGroup.members) {
        val existing = currentGroup.members.toSet()
        val all = GroupChatManager.getAvailableMembers(ctx)
        availableCandidates = all.filter { it.first !in existing }
    }

    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        // 顶栏
        Surface(
            Modifier.fillMaxWidth().statusBarsPadding(),
            color = AchatTheme.colors.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface)
                }
                Text(
                    "群信息",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AchatTheme.colors.onSurface
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 群名
            item(key = "group_name") {
                CardRow(
                    title = "群聊名称",
                    subtitle = currentGroup.name,
                    trailingIcon = Icons.Filled.Edit,
                    onClick = {
                        renameText = currentGroup.name
                        showRenameDialog = true
                    }
                )
                HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
            }

            item(key = "group_mode") {
                CardRow(
                    title = "发言模式",
                    subtitle = currentGroup.mode.title,
                    trailingIcon = Icons.Filled.Tune,
                    onClick = { showModeDialog = true }
                )
                HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
            }

            // 成员标题
            item(key = "members_header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "群成员（${currentGroup.members.size}）",
                        fontSize = 13.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加", fontSize = 13.sp)
                    }
                }
            }

            // 成员列表
            items(memberInfo, key = { it.first }) { (name, avatar, persona) ->
                val memberIndex = currentGroup.members.indexOf(name)
                val enabled = currentGroup.memberEnabled[name] != false
                MemberRow(
                    name = name,
                    persona = persona,
                    avatar = avatar,
                    enabled = enabled,
                    canMoveUp = memberIndex > 0,
                    canMoveDown = memberIndex in 0 until (currentGroup.members.size - 1),
                    onToggle = {
                        val activeCount = currentGroup.members.count { currentGroup.memberEnabled[it] != false }
                        if (enabled && activeCount <= 1) {
                            Toast.makeText(ctx, "至少保留一名可发言成员", Toast.LENGTH_SHORT).show()
                        } else {
                            commitGroup(currentGroup.copy(
                                memberEnabled = currentGroup.memberEnabled + (name to !enabled)
                            ))
                        }
                    },
                    onMoveUp = {
                        if (memberIndex > 0) {
                            val reordered = currentGroup.members.toMutableList()
                            val item = reordered.removeAt(memberIndex)
                            reordered.add(memberIndex - 1, item)
                            commitGroup(currentGroup.copy(members = reordered))
                        }
                    },
                    onMoveDown = {
                        if (memberIndex in 0 until (currentGroup.members.size - 1)) {
                            val reordered = currentGroup.members.toMutableList()
                            val item = reordered.removeAt(memberIndex)
                            reordered.add(memberIndex + 1, item)
                            commitGroup(currentGroup.copy(members = reordered))
                        }
                    },
                    onRemove = {
                        if (currentGroup.members.size <= 1) {
                            Toast.makeText(ctx, "至少保留一名群成员", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                if (GroupChatManager.removeMember(ctx, currentGroup.id, name)) {
                                    commitGroup(currentGroup.copy(
                                        members = currentGroup.members.filter { it != name },
                                        memberEnabled = currentGroup.memberEnabled - name
                                    ))
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
            }

            // 解散群聊按钮
            item(key = "delete_group") {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("解散群聊")
                    }
                }
            }
        }
    }

    // 重命名对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改群名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("群聊名称") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && renameText != currentGroup.name,
                    onClick = {
                        scope.launch {
                            GroupChatManager.renameGroup(ctx, currentGroup.id, renameText.trim())
                            currentGroup = currentGroup.copy(name = renameText.trim())
                            onGroupChanged(currentGroup)
                            showRenameDialog = false
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    // 添加成员对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加群成员") },
            text = {
                if (availableCandidates.isEmpty()) {
                    Text(
                        "没有可添加的候选角色。\n请先在「对话」标签中创建角色，或确保已加载内置角色。",
                        fontSize = 13.sp,
                        color = AchatTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    Column {
                        availableCandidates.forEach { (name, persona) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            if (GroupChatManager.addMember(ctx, currentGroup.id, name)) {
                                                commitGroup(currentGroup.copy(
                                                    members = currentGroup.members + name,
                                                    memberEnabled = currentGroup.memberEnabled + (name to true)
                                                ))
                                            }
                                            showAddDialog = false
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    persona.take(40),
                                    fontSize = 11.sp,
                                    color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("关闭") } }
        )
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择群聊发言模式") },
            text = {
                Column {
                    GroupChatMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val updated = currentGroup.copy(mode = mode)
                                    commitGroup(updated)
                                    showModeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentGroup.mode == mode,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    mode.description,
                                    fontSize = 12.sp,
                                    color = AchatTheme.colors.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("关闭") }
            }
        )
    }

    // 解散确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("解散群聊") },
            text = { Text("解散后群聊和聊天记录将无法恢复，确定解散「${currentGroup.name}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            GroupChatManager.deleteGroup(ctx, currentGroup.id)
                            showDeleteDialog = false
                            onGroupDeleted()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("解散") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CardRow(
    title: String,
    subtitle: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 16.sp,
                color = AchatTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(trailingIcon, "编辑", tint = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun MemberRow(
    name: String,
    persona: String,
    avatar: androidx.compose.ui.graphics.ImageBitmap?,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(AchatTheme.colors.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (avatar != null) {
                Image(
                    avatar, null,
                    Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(
                    name.take(1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AchatTheme.colors.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
            if (persona.isNotEmpty()) {
                Text(
                    persona.take(50),
                    fontSize = 11.sp,
                    color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, "上移", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "下移", modifier = Modifier.size(18.dp))
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(36.dp, 24.dp)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                "移除",
                tint = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
