package com.example.wechatclone.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.wechatclone.ui.utils.LiquidBottomTab
import com.example.wechatclone.ui.utils.LiquidBottomTabs
import com.kyant.backdrop.backdrops.LayerBackdrop

private val tabData = listOf(
    Triple("对话", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    Triple("发现", Icons.Filled.Explore, Icons.Outlined.Explore),
    Triple("我的", Icons.Filled.Person, Icons.Outlined.Person)
)

@Composable
fun GlassNavBar(backdrop: LayerBackdrop, selectedIndex: Int, onTabSelected: (Int) -> Unit, physicsEnabled: Boolean = true, modifier: Modifier = Modifier) {
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val currentIndex by rememberUpdatedState(selectedIndex)
    LiquidBottomTabs(
        selectedTabIndex = { currentIndex },
        onTabSelected = { currentOnTabSelected(it) },
        backdrop = backdrop,
        tabsCount = tabData.size,
        physicsEnabled = physicsEnabled,
        modifier = modifier
            .fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding()
    ) {
        tabData.forEachIndexed { i, (title, selIcon, unselIcon) ->
            val sel = i == selectedIndex
            LiquidBottomTab(onClick = { currentOnTabSelected(i) }) {
                Icon(
                    if (sel) selIcon else unselIcon, title,
                    tint = if (sel) Color(0xFF07C160) else Color(0xFF888888),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(title, color = if (sel) Color(0xFF07C160) else Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
