package com.example.wechatclone.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.example.wechatclone.R
import com.example.wechatclone.SettingsActivity
import com.example.wechatclone.model.MeItem
import com.example.wechatclone.viewmodel.MeViewModel
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun MeScreen(vm: MeViewModel = viewModel<MeViewModel>()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refresh() }
    val items: List<Any> by vm.items.observeAsState(emptyList())
    val ctx = LocalContext.current
    MeScreenContent(
        items = items,
        onItemClick = { item ->
            if (item.id == "settings") {
                val intent = Intent(ctx, SettingsActivity::class.java)
                if (ctx !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
    )
}

@Composable
fun MeScreenContent(
    items: List<Any>,
    onItemClick: (MeItem) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 80.dp)
    ) {
        itemsIndexed(items) { index, item ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            val animProgress by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "me_entry"
            )

            Box(Modifier.graphicsLayer {
                alpha = animProgress
                translationY = (1f - animProgress) * 30f
            }) {
                when (item) {
                    is String -> {
                        val p = item.split("|")
                        val name = p.getOrElse(0) { "用户" }
                        val wId = p.getOrElse(1) { "" }
                        val clr = try { Color(p.getOrElse(2) { "#07C160" }.toColorInt()) } catch (_: Exception) { Color(0xFF07C160) }
                        val avt = p.getOrElse(3) { "" }
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            onClick = { /* TODO: Profile page */ }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(clr), contentAlignment = Alignment.Center) {
                                    if (avt.isNotEmpty()) {
                                        val bmp = remember(avt) {
                                            try { BitmapFactory.decodeFile(avt)?.asImageBitmap() }
                                            catch (_: Exception) { null }
                                        }
                                        if (bmp != null) {
                                            Image(bmp, null, Modifier.size(60.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                        } else {
                                            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                                        }
                                    } else {
                                        Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                                    if (wId.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(wId, fontSize = 14.sp, color = Color.Gray)
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    is MeItem -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            onClick = { onItemClick(item) }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(item.iconResId),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = Color(0xFF07C160)
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = item.title,
                                    fontSize = 16.sp,
                                    color = Color(0xFF1A1A1A),
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color(0xFFCCCCCC),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    is Unit -> Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MeScreenPreview() {
    val sampleItems = listOf(
        "Android Dev|微信号: wx_id_123456|#07C160",
        Unit,
        MeItem("pay", "支付", R.drawable.ic_wallet),
        Unit,
        MeItem("favorites", "收藏", R.drawable.ic_favorites),
        MeItem("album", "相册", R.drawable.ic_album),
        MeItem("cards", "卡包", R.drawable.ic_cards),
        MeItem("emoji", "表情", R.drawable.ic_emoji),
        Unit,
        MeItem("settings", "设置", R.drawable.ic_settings)
    )
    MaterialTheme {
        MeScreenContent(items = sampleItems)
    }
}
