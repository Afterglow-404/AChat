package com.aftglw.devapi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ScriptLoader
import com.aftglw.devapi.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptBrowserPage(
    scripts: List<ScriptLoader.ScriptInfo>,
    onPlay: (ScriptLoader.ScriptInfo) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(AchatTheme.colors.background)) {
        CenterAlignedTopAppBar(
            title = { Text("剧本选择", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AchatTheme.colors.surface)
        )
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        if (scripts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无可用剧本\n\n将剧本放在 assets/scripts/ 目录下即可显示", 
                    fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center, lineHeight = 22.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(scripts, key = { i, _ -> i }) { _, info ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onPlay(info) },
                        color = AchatTheme.colors.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(info.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AchatTheme.colors.onSurface)
                                if (info.description.isNotBlank()) {
                                    Text(info.description, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 2.dp), maxLines = 2)
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = AchatTheme.colors.divider)
                }
            }
        }
    }
}
