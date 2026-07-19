package com.aftglw.devapi.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.aftglw.devapi.R
import com.kyant.backdrop.Backdrop
import com.aftglw.devapi.model.DiscoverItem
import com.aftglw.devapi.viewmodel.DiscoverViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.aftglw.devapi.ui.utils.DampedDragAnimation
import com.aftglw.devapi.ui.utils.InteractiveHighlight
import com.aftglw.devapi.viewmodel.TodoItem
import com.aftglw.devapi.viewmodel.TodoViewModel
import com.kyant.shapes.Capsule
import androidx.compose.ui.tooling.preview.Preview
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.utils.AnimationUtils
import com.aftglw.devapi.ui.utils.StaggeredEntrance
@Composable
fun DiscoverScreen(items: List<DiscoverItem> = emptyList(), onSubPageChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var hitokoto by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    // 调用方未传 items 时（如 WeChatApp 传 emptyList()），从 ViewModel 加载默认功能列表
    val effectiveItems = if (items.isEmpty()) {
        val vm: DiscoverViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
        val liveItems by vm.items.observeAsState(emptyList())
        liveItems
    } else items

    val cnFont = FontFamily(Font(R.font.notoserifsc_bold, weight = FontWeight.Bold))
    val enFont = FontFamily(Font(R.font.special_elite_regular, weight = FontWeight.Normal))

    var showPromptBuilder by remember { mutableStateOf(false) }

    var showTodo by remember { mutableStateOf(false) }
    val todoBackdrop = rememberLayerBackdrop(onDraw = { drawRect(Color.Transparent); drawContent() })

    var showChallenge by remember { mutableStateOf(false) }
    var challengeText by remember { mutableStateOf("") }
    var challengeDone by remember { mutableStateOf(false) }
    var challengeLoading by remember { mutableStateOf(false) }
    var isLeetCode by remember { mutableStateOf(false) }
    var leetCodeLink by remember { mutableStateOf("") }
    val challengeTabIndex = remember { derivedStateOf { if (isLeetCode) 1 else 0 } }
    val challengeBackdrop = rememberLayerBackdrop(onDraw = { drawRect(Color.Transparent); drawContent() })

    fun fetchBoredChallenge() {
        challengeLoading = true; challengeDone = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("https://bored-api.appbrewery.com/random").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val activity = json.optString("activity", "")
                val type = json.optString("type", "")
                val participants = json.optInt("participants", 1)
                val price = json.optDouble("price", 0.0)
                val duration = json.optString("duration", "")
                val text = buildString {
                    append(activity.takeIf { it.isNotEmpty() } ?: "放松一下")
                    if (type.isNotEmpty()) append("\n类型：$type")
                    if (participants > 1) append("\n适合 ${participants}人")
                    if (price > 0) append("\n预算：${(price * 100).toInt()}%")
                    if (duration.isNotEmpty()) append("\n时长：$duration")
                }
                withContext(Dispatchers.Main) {
                    challengeText = text; challengeLoading = false; isLeetCode = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    challengeText = "网络不佳，稍后再试喵～"; challengeLoading = false
                }
            }
        }
    }

    fun fetchLeetCodeChallenge() {
        challengeLoading = true; challengeDone = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("https://leetcode-api-pied.vercel.app/daily").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val date = json.optString("date", "")
                val link = json.optString("link", "")
                val question = json.optJSONObject("question")
                val title = question?.optString("title", "") ?: ""
                val difficulty = question?.optString("difficulty", "") ?: ""
                val text = buildString {
                    append("今日编程挑战")
                    if (date.isNotEmpty()) append("（$date）")
                    append("\n")
                    append(title.takeIf { it.isNotEmpty() } ?: "LeetCode 每日一题")
                    if (difficulty.isNotEmpty()) append("\n难度：$difficulty")
                }
                withContext(Dispatchers.Main) {
                    val fullLink = if (link.startsWith("/")) "https://leetcode.com$link" else link
                    challengeText = text; leetCodeLink = fullLink; challengeLoading = false; isLeetCode = true
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    challengeText = "LeetCode 不可用，换个挑战喵～"; challengeLoading = false
                }
            }
        }
    }

    var showCatPage by remember { mutableStateOf(false) }
    var catImg by remember { mutableStateOf<String?>(null) }
    var catLoading by remember { mutableStateOf(false) }
    val fortunes = arrayOf("大吉 🐱", "中吉 😺", "小吉 😸", "末吉 😿", "凶 😾", "大凶 🙀")
    var fortuneText by remember { mutableStateOf("") }
    var alreadyDrawn by remember { mutableStateOf(false) }
    var buttonLabel by remember { mutableStateOf("求签摸猫 🐱") }

    fun fetchCat() {
        catLoading = true
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                val lastDate = prefs.getString("cat_fortune_date", "") ?: ""

                if (today != lastDate) {
                    prefs.edit().putString("cat_fortune_date", today).apply()
                    withContext(Dispatchers.Main) { alreadyDrawn = false; buttonLabel = "求签摸猫 🐱" }
                }

                // 每日固定签文：用日期哈希做种子
                val seed = today.hashCode() and Int.MAX_VALUE
                val fi = seed % fortunes.size
                withContext(Dispatchers.Main) { alreadyDrawn = true; buttonLabel = "今日已抽 🐱" }

                var huangliText = ""
                try {
                    val hl = URL("https://api.suyanw.cn/api/huangli.php").openConnection() as HttpURLConnection
                    hl.connectTimeout = 5000; hl.readTimeout = 5000
                    val hlJson = JSONObject(hl.inputStream.bufferedReader().use { it.readText() })
                    hl.disconnect()
                    if (hlJson.optInt("code") == 1) {
                        val d = hlJson.optJSONObject("data")
                        val xz = d?.optJSONObject("星座运势")
                        val saying = xz?.optString("今日一言", "") ?: ""
                        val color = xz?.optString("幸运颜色", "") ?: ""
                        val num = xz?.optString("幸运数字", "") ?: ""
                        val star = xz?.optString("速配星座", "") ?: ""
                        val parts = listOfNotNull(
                            saying.takeIf { it.isNotEmpty() },
                            "幸运色：$color".takeIf { color.isNotEmpty() },
                            "幸运数字：$num".takeIf { num.isNotEmpty() },
                            "速配：$star".takeIf { star.isNotEmpty() }
                        )
                        huangliText = parts.joinToString("\n")
                    }
                } catch (_: Exception) { }

                withContext(Dispatchers.Main) {
                    val msg = if (huangliText.isNotEmpty()) huangliText else "喵神说今天宜摸鱼 🐱"
                    fortuneText = "${fortunes[fi]}\n$msg\n喵~"
                }

                val conn = URL("https://api.thecatapi.com/v1/images/search").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                catImg = json.optJSONObject(0)?.optString("url", "")?.takeIf { it.isNotEmpty() }
                withContext(Dispatchers.Main) { catLoading = false }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    fortuneText = if (fortuneText.isEmpty()) "网络不佳，摸摸猫头吧 🐱\n喵~" else fortuneText
                    catLoading = false
                }
            }
        }
    }

    fun fetchHitokoto() {
        loading = true
        val hitokotoType = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .getString("hitokoto_type", "") ?: ""
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val types = hitokotoType.split(",").filter { it.isNotEmpty() && it.length == 1 }
                val typeParam = if (types.isNotEmpty()) "&type=${types.random()}" else ""
                val conn = URL("https://v1.hitokoto.cn?_=${System.currentTimeMillis()}$typeParam").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val text = json.optString("hitokoto", "")
                val src = json.optString("from", "")
                val who = json.optString("from_who", "").takeIf { it != "null" } ?: ""
                val rawType = json.optString("type", "")
                val typeLabel = mapOf("a" to "动画", "b" to "漫画", "c" to "游戏", "d" to "文学",
                    "e" to "原创", "f" to "网络", "h" to "影视", "i" to "诗词", "j" to "网易云",
                    "k" to "哲学", "l" to "抖机灵").getOrDefault(rawType, "")
                val source = if (who.isNotEmpty()) "$who《$src》" else if (src.isNotEmpty() && src != "null") "《$src》" else ""
                val typeTag = if (typeLabel.isNotEmpty()) " #$typeLabel" else ""
                withContext(Dispatchers.Main) {
                    hitokoto = text; from = "$source$typeTag"; loading = false
                    ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE).edit()
                        .putString("hitokoto_text", text).putString("hitokoto_from", "$source$typeTag").apply()
                }
            } catch (_: Exception) {
                val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
                withContext(Dispatchers.Main) {
                    hitokoto = prefs.getString("hitokoto_text", "知识就是力量。") ?: "知识就是力量。"
                    from = prefs.getString("hitokoto_from", "") ?: ""
                    loading = false
                }
            }
        }
    }

    val subPageOpen = showCatPage || showChallenge || showTodo || showPromptBuilder
    LaunchedEffect(subPageOpen) { onSubPageChange(subPageOpen) }

    LaunchedEffect(Unit) { fetchHitokoto() }

    AnimatedContent(
        targetState = if (showCatPage) 1 else if (showChallenge) 2 else if (showTodo) 3 else if (showPromptBuilder) 4 else 0,
        transitionSpec = {
            AnimationUtils.slideHorizontal(forward = targetState > initialState)
        },
        label = "subpages"
    ) { state ->
        when (state) {
            1 -> CatFortunePage(
                catImg = catImg, catLoading = catLoading, fortuneText = fortuneText, buttonLabel = buttonLabel,
                onBack = { showCatPage = false }, onRefresh = { fetchCat() }, cnFont = cnFont, enFont = enFont
            )
            2 -> DailyChallengePage(
                challengeText = challengeText, challengeDone = challengeDone, challengeLoading = challengeLoading,
                isLeetCode = isLeetCode, leetCodeLink = leetCodeLink,
                cnFont = cnFont, enFont = enFont,
                selectedTab = { challengeTabIndex.value },
                selectedTabChange = { idx -> if (idx == 0) fetchBoredChallenge() else fetchLeetCodeChallenge() },
                backdrop = challengeBackdrop, onBack = { showChallenge = false },
                onBoredRefresh = { fetchBoredChallenge() }, onLeetCodeRefresh = { fetchLeetCodeChallenge() },
                onDone = { challengeDone = true }
            )
            3 -> TodoPage(onBack = { showTodo = false }, cnFont = cnFont, enFont = enFont, fortuneText = fortuneText, backdrop = todoBackdrop)
            4 -> PromptBuilderPage(onBack = { showPromptBuilder = false })
            else -> DiscoverScreenContent(items = effectiveItems, hitokoto = hitokoto, from = from, loading = loading, onRefresh = { fetchHitokoto() }, cnFont = cnFont, enFont = enFont,
                onCatClick = { showCatPage = true; fetchCat() },
                onChallengeClick = { showChallenge = true; challengeDone = false; fetchBoredChallenge() },
                onTodoClick = { showTodo = true },
                onPromptBuilderClick = { showPromptBuilder = true })
        }
    }
}

@Composable
fun DiscoverScreenContent(items: List<DiscoverItem>, hitokoto: String = "", from: String = "", loading: Boolean = false, onRefresh: () -> Unit = {}, cnFont: FontFamily = FontFamily.Default, enFont: FontFamily = FontFamily.Default, onCatClick: () -> Unit = {}, onChallengeClick: () -> Unit = {}, onTodoClick: () -> Unit = {}, onPromptBuilderClick: () -> Unit = {}) {
    val scrollState = rememberLazyListState()
    val collapseFraction by remember {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f
            else {
                val offset = scrollState.firstVisibleItemScrollOffset.toFloat()
                (offset / 400f).coerceIn(0f, 1f)
            }
        }
    }

    val discoverBgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    Box(Modifier.fillMaxSize().then(discoverBgModifier)) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 90.dp)
        ) {
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                
                StaggeredEntrance(index = 0, visible = visible) {
                    val alpha = 1f - collapseFraction
                    val scale = 1f - (0.1f * collapseFraction)
                    val translateY = -collapseFraction * 50f
                    val paddingV = lerp(16f, 4f, collapseFraction).dp

                    Box(
                        Modifier.fillMaxWidth()
                            .graphicsLayer {
                                this.alpha = alpha
                                this.scaleX = scale
                                this.scaleY = scale
                                this.translationY = translateY
                            }
                            .padding(vertical = 4.dp)
                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier)
                            .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider) else Modifier)
                            .clip(AchatTheme.shapes.card)
                            .background(AchatTheme.colors.surface)
                            .padding(horizontal = 16.dp, vertical = paddingV)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (loading) {
                                    Text("正在加载喵...", fontSize = 15.sp, color = Color.Gray, fontStyle = FontStyle.Italic, fontFamily = AchatTheme.typography.body)
                                } else {
                                    Text(
                                        buildMixedText("「$hitokoto」", cnFont, enFont),
                                        fontSize = lerp(17f, 14f, collapseFraction).sp,
                                        color = AchatTheme.colors.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = lerp(26f, 20f, collapseFraction).sp,
                                        maxLines = if (collapseFraction > 0.5f) 1 else Int.MAX_VALUE,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (from.isNotEmpty() && collapseFraction < 0.6f) {
                                        Spacer(Modifier.height(lerp(8f, 0f, collapseFraction * 2).dp))
                                        Text(
                                            buildMixedText("—— $from", cnFont, enFont),
                                            fontSize = 13.sp,
                                            color = AchatTheme.colors.onSurface.copy(alpha = (0.6f - collapseFraction * 2.5f).coerceIn(0f, 0.6f)),
                                            fontStyle = FontStyle.Italic
                                        )
                                    }
                                }
                            }
                            if (!loading && hitokoto.isNotEmpty() && collapseFraction < 0.8f) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !loading) { onRefresh() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("↻", fontSize = 22.sp, color = AchatTheme.colors.primary.copy(alpha = (1f - collapseFraction * 5f).coerceIn(0f, 1f)), fontFamily = AchatTheme.typography.mono, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                
                StaggeredEntrance(index = 1, visible = visible) {
                    Surface(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(bottom = true) else Modifier)
                            .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, 1) else Modifier),
                        shape = AchatTheme.shapes.card,
                        color = AchatTheme.colors.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(AchatTheme.colors.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📢", fontSize = 18.sp)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("更新公告", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
                                Spacer(Modifier.height(2.dp))
                                val announcement = """
                                **v0.1.2** 内容：
                                  Wispの第三个版本
                                - 修正部分内容
                                - 暂时不像微信
                                - 根据《人工智能拟人化互动服务管理暂行办法》做出修改
                                - 加入Herobrine
                                给Wisp点点Star谢谢喵！
                            """.trimIndent()
                                Text(
                                    text = parseMarkdown(announcement),
                                    fontSize = 13.sp,
                                    color = AchatTheme.colors.onSurface.copy(alpha = 0.7f),
                                    lineHeight = 18.sp,
                                    fontFamily = AchatTheme.typography.body
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                
                StaggeredEntrance(index = index + 2, visible = visible) {
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                            .padding(vertical = 4.dp)
                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(bottom = true) else Modifier)
                            .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, index) else Modifier)
                            .clip(AchatTheme.shapes.card)
                            .background(AchatTheme.colors.surface)
                            .clickable {
                                when (item.id) {
                                    "2" -> onCatClick()
                                    "3" -> onChallengeClick()
                                    "4" -> onTodoClick()
                                    "8" -> onPromptBuilderClick()
                                }
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(item.iconResId), null, Modifier.size(24.dp), tint = AchatTheme.colors.primary)
                        Spacer(Modifier.width(16.dp)); Text(item.title, fontSize = 16.sp, modifier = Modifier.weight(1f), color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = collapseFraction > 0.6f,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 }
        ) {
            Surface(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = Capsule(),
                color = Color.White.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("「", fontSize = 12.sp, color = Color(0xFF07C160), fontWeight = FontWeight.Bold)
                    Text(
                        hitokoto,
                        fontSize = 12.sp,
                        color = Color(0xFF333333),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("」", fontSize = 12.sp, color = Color(0xFF07C160), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CatFortunePage(
    catImg: String?,
    catLoading: Boolean,
    fortuneText: String,
    buttonLabel: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    cnFont: FontFamily,
    enFont: FontFamily
) {
    val pageBgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }
    
    Column(Modifier.fillMaxSize().then(pageBgModifier).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(AchatTheme.colors.surface).padding(horizontal = 16.dp)
                .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.headerDoubleRule() else Modifier)
                .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) }
            Spacer(Modifier.width(4.dp))
            Text("🐱 喵神谕", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
        }
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (catLoading && catImg == null) {
                Text("召唤猫猫中...", fontSize = 16.sp, color = Color.Gray, fontStyle = FontStyle.Italic, fontFamily = AchatTheme.typography.body)
            } else {
                if (fortuneText.isNotEmpty()) {
                    Text(fortuneText, fontSize = 16.sp, lineHeight = 24.sp, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
                    Spacer(Modifier.height(16.dp))
                }
                if (catImg != null) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(catImg).crossfade(true).build(),
                        contentDescription = "猫猫",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(AchatTheme.shapes.card),
                        contentScale = ContentScale.Fit,
                        loading = {
                            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AchatTheme.colors.primary)
                            }
                        },
                        error = {
                            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text("图片加载失败喵", color = Color.Gray, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontFamily = AchatTheme.typography.body)
                            }
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.primary),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !catLoading
                ) { Text(buttonLabel, fontSize = 15.sp, fontFamily = AchatTheme.typography.title) }
            }
        }
    }
}

@Composable
private fun DailyChallengePage(
    challengeText: String,
    challengeDone: Boolean,
    challengeLoading: Boolean,
    isLeetCode: Boolean,
    leetCodeLink: String,
    cnFont: FontFamily = FontFamily.Default,
    enFont: FontFamily = FontFamily.Default,
    selectedTab: () -> Int,
    selectedTabChange: (Int) -> Unit,
    backdrop: Backdrop,
    onBack: () -> Unit,
    onBoredRefresh: () -> Unit,
    onLeetCodeRefresh: () -> Unit,
    onDone: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (challengeDone) 1f else 0.3f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f)
    )

    val pageBgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    val surfaceColor = AchatTheme.colors.surface
    Box(Modifier.fillMaxSize().then(pageBgModifier).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().layerBackdrop(backdrop as LayerBackdrop)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(AchatTheme.colors.surface).padding(horizontal = 16.dp)
                    .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.headerDoubleRule() else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) }
                Spacer(Modifier.width(4.dp))
                Text("🎯 喵神の试炼", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
            }
            HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(60.dp))

                if (challengeLoading || challengeText.isEmpty()) {
                    Text("加载中...", fontSize = 14.sp, color = Color.Gray, fontStyle = FontStyle.Italic, fontFamily = AchatTheme.typography.body)
                } else {
                    Text(buildMixedText(challengeText, cnFont, enFont), fontSize = 18.sp, lineHeight = 28.sp, color = AchatTheme.colors.onSurface, fontWeight = FontWeight.Medium)

                    if (isLeetCode && leetCodeLink.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(leetCodeLink, fontSize = 11.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.mono)
                    }

                    Spacer(Modifier.height(32.dp))
                    if (!challengeDone) {
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("✅ 完成挑战", fontSize = 16.sp, fontFamily = AchatTheme.typography.title) }
                    } else {
                        Text("🎉", fontSize = 64.sp, modifier = Modifier.scale(scale))
                        Spacer(Modifier.height(12.dp))
                        Text("挑战完成！", fontSize = 20.sp, color = AchatTheme.colors.primary, fontWeight = FontWeight.Bold, fontFamily = AchatTheme.typography.title)
                        Spacer(Modifier.height(8.dp))
                        Text("很棒喵！要不要再来一个挑战呢喵？", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f), fontFamily = AchatTheme.typography.body)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = if (isLeetCode) onLeetCodeRefresh else onBoredRefresh) {
                    Text("🔀 换一个", fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), fontFamily = AchatTheme.typography.body)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 58.dp).align(Alignment.TopCenter)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { blur(8f.dp.toPx()); lens(12f.dp.toPx(), 24f.dp.toPx()) },
                    onDrawSurface = { drawRect(surfaceColor.copy(alpha = 0.85f)) }
                )
                .height(44.dp)
        ) {
            Box(Modifier.weight(1f).fillMaxSize().clickable { selectedTabChange(0) }, contentAlignment = Alignment.Center) {
                Text("🎲 趣味", color = if (!isLeetCode) AchatTheme.colors.primary else AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = if (!isLeetCode) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, fontFamily = AchatTheme.typography.title)
            }
            Box(Modifier.weight(1f).fillMaxSize().clickable { selectedTabChange(1) }, contentAlignment = Alignment.Center) {
                Text("💻 编程", color = if (isLeetCode) AchatTheme.colors.primary else AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = if (isLeetCode) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, fontFamily = AchatTheme.typography.title)
            }
        }
    }
}

@Composable
private fun TodoPage(onBack: () -> Unit, cnFont: FontFamily, enFont: FontFamily, fortuneText: String, backdrop: Backdrop? = null) {
    val vm = viewModel<TodoViewModel>()
    val items by vm.items.observeAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var showChains by remember { mutableStateOf(false) }
    var chains by remember { mutableStateOf(vm.loadChains()) }
    var chainTitle by remember { mutableStateOf("") }
    var chainStepText by remember { mutableStateOf("") }
    var chainDialogStep by remember { mutableIntStateOf(0) }
    var pendingSteps by remember { mutableStateOf(listOf<String>()) }
    val animationScope = rememberCoroutineScope()

    val clearDampedDrag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f, valueRange = 0f..1f, visibilityThreshold = 0.001f,
            initialScale = 1f, pressedScale = 1.05f,
            onDragStarted = {}, onDragStopped = { animateToValue(0f) },
            onDrag = { _, _ -> }
        )
    }
    val clearHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    LaunchedEffect(Unit) { vm.refresh() }

    val pageBgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    Box(Modifier.fillMaxSize().then(pageBgModifier).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().layerBackdrop(backdrop as LayerBackdrop)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(AchatTheme.colors.surface).padding(horizontal = 16.dp)
                    .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.headerDoubleRule() else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) }
                Spacer(Modifier.width(4.dp))
                Text("📋 Too-Do", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
            }
            HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

            Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🐱 ", fontSize = 13.sp)
                Text("喵神说：", fontSize = 12.sp, fontStyle = FontStyle.Italic, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.body)
                Spacer(Modifier.width(4.dp))
                if (fortuneText.isNotEmpty()) {
                    Text(fortuneText.split("\n").firstOrNull() ?: "", fontSize = 12.sp, fontStyle = FontStyle.Italic, color = AchatTheme.colors.primary, fontFamily = AchatTheme.typography.body)
                } else {
                    Text("先去喵神谕求个签吧～", fontSize = 12.sp, fontStyle = FontStyle.Italic, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), fontFamily = AchatTheme.typography.body)
                }
            }

            Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface)) {
                TextButton(
                    onClick = { showChains = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📋 自由", color = if (!showChains) AchatTheme.colors.primary else AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = if (!showChains) FontWeight.Bold else FontWeight.Normal, fontFamily = AchatTheme.typography.title)
                }
                TextButton(
                    onClick = { showChains = true; chains = vm.loadChains() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📜 任务链", color = if (showChains) AchatTheme.colors.primary else AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontWeight = if (showChains) FontWeight.Bold else FontWeight.Normal, fontFamily = AchatTheme.typography.title)
                }
            }

            AnimatedContent(
                targetState = showChains,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "todo_tabs",
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { isChainTab ->
                if (!isChainTab) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                input, { input = it }, Modifier.weight(1f),
                                placeholder = { Text("写一个新任务...", fontSize = 14.sp) },
                                singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { if (input.isNotBlank()) { vm.add(input.trim()); input = "" } },
                                colors = ButtonDefaults.textButtonColors(contentColor = AchatTheme.colors.primary)
                            ) { Text("添加", fontWeight = FontWeight.Bold, fontFamily = AchatTheme.typography.title) }
                        }

                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 80.dp)) {
                            if (items.isEmpty()) {
                                item { Text("还没有任务，写一个吧～", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(16.dp), fontFamily = AchatTheme.typography.body) }
                            }
                            itemsIndexed(items, key = { _, it -> it.id }) { index, todo ->
                                var visible by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) { visible = true }
                                val strikeAlpha by animateFloatAsState(
                                    targetValue = if (todo.done) 1f else 0f,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f)
                                )
                                
                                StaggeredEntrance(index = index, visible = visible) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(bottom = true) else Modifier)
                                            .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider, index) else Modifier)
                                            .clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = todo.done, onCheckedChange = {
                                            vm.toggle(todo.id)
                                        }, colors = CheckboxDefaults.colors(checkedColor = AchatTheme.colors.primary))
                                        Spacer(Modifier.width(8.dp))
                                        Box(Modifier.weight(1f)) {
                                            Text(todo.text, fontSize = 15.sp, color = if (todo.done) AchatTheme.colors.onSurface.copy(alpha = 0.5f) else AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
                                            if (strikeAlpha > 0.01f) {
                                                HorizontalDivider(modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(), thickness = 1.5.dp, color = AchatTheme.colors.onSurface.copy(alpha = strikeAlpha * 0.5f))
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { vm.delete(todo.id) }) { Text("✕", fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.2f), fontFamily = AchatTheme.typography.mono) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(8.dp))
                        if (chains.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("还没有任务链，创建一个吧～", fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.4f), fontFamily = AchatTheme.typography.body)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 80.dp)) {
                                itemsIndexed(chains) { index, chain ->
                                    var visible by remember { mutableStateOf(false) }
                                    LaunchedEffect(Unit) { visible = true }
                                    val doneCount = chain.steps.count { it.done }
                                    val total = chain.steps.size
                                    val targetProgress = if (total > 0) doneCount.toFloat() / total else 0f
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = targetProgress,
                                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                                        label = "chain_progress"
                                    )
                                    val allDone = doneCount == total

                                    val trophyScale by animateFloatAsState(
                                        targetValue = if (allDone) 1f else 0f,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                        label = "trophy_scale"
                                    )
                                    
                                    StaggeredEntrance(index = index, visible = visible) {
                                        Box(Modifier.fillMaxWidth()
                                            .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.printRule(all = true) else Modifier)
                                            .padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(16.dp)) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(chain.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
                                                    Box(Modifier.scale(trophyScale)) {
                                                        Text("🏆", fontSize = 20.sp)
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                LinearProgressIndicator(
                                                    progress = { animatedProgress },
                                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                    color = if (allDone) AchatTheme.colors.primary else AchatTheme.colors.primary.copy(alpha = 0.5f),
                                                    trackColor = AchatTheme.colors.divider.copy(alpha = 0.5f)
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text("$doneCount / $total", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.mono)
                                                Spacer(Modifier.height(8.dp))
                                                chain.steps.forEachIndexed { si, step ->
                                                    val prevDone = si == 0 || chain.steps[si - 1].done
                                                    val stepAlpha by animateFloatAsState(
                                                        targetValue = if (prevDone) 1f else 0.4f,
                                                        label = "step_lock"
                                                    )
                                                    Row(
                                                        Modifier.fillMaxWidth().padding(vertical = 2.dp).graphicsLayer { alpha = stepAlpha },
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Checkbox(
                                                            checked = step.done,
                                                            onCheckedChange = {
                                                                if (prevDone) {
                                                                    vm.toggleChainStep(chain.id, si)
                                                                    chains = vm.loadChains()
                                                                }
                                                            },
                                                            enabled = prevDone,
                                                            colors = CheckboxDefaults.colors(checkedColor = AchatTheme.colors.primary, disabledUncheckedColor = AchatTheme.colors.divider)
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(step.text, fontSize = 14.sp, color = if (step.done) AchatTheme.colors.onSurface.copy(alpha = 0.5f) else AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
                                                    }
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                    TextButton(onClick = { vm.deleteChain(chain.id); chains = vm.loadChains() }) {
                                                        Text("删除链", fontSize = 12.sp, color = AchatTheme.colors.primary, fontFamily = AchatTheme.typography.body)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { chainDialogStep = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text("+ 新建任务链", fontSize = 14.sp, fontFamily = AchatTheme.typography.title) }
                    }
                }
            }

            if (chainDialogStep == 1) {
                AlertDialog(
                    onDismissRequest = { chainDialogStep = 0 },
                    title = { Text("你的目标是什么？", fontFamily = AchatTheme.typography.title) },
                    text = {
                        OutlinedTextField(chainTitle, { chainTitle = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text("例：学习 Compose", fontSize = 14.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary))
                    },
                    confirmButton = {
                        TextButton({ if (chainTitle.isNotBlank()) chainDialogStep = 2 }) {
                            Text("下一步 →", color = AchatTheme.colors.primary, fontFamily = AchatTheme.typography.title)
                        }
                    },
                    dismissButton = { TextButton({ chainDialogStep = 0 }) { Text("取消", color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.body) } }
                )
            }
            if (chainDialogStep >= 2) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (chainDialogStep > 2) "还要添加步骤吗？" else "添加第一个步骤", fontFamily = AchatTheme.typography.title) },
                    text = {
                        OutlinedTextField(chainStepText, { chainStepText = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text("写一个具体步骤...", fontSize = 14.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary))
                    },
                    confirmButton = {
                        TextButton({
                            if (chainStepText.isNotBlank()) {
                                pendingSteps = pendingSteps + chainStepText.trim()
                                chainStepText = ""
                                chainDialogStep++
                            }
                        }) { Text("添加并继续", color = AchatTheme.colors.primary, fontFamily = AchatTheme.typography.title) }
                    },
                    dismissButton = {
                        TextButton({
                            val finalSteps = if (chainStepText.isNotBlank()) pendingSteps + chainStepText.trim() else pendingSteps
                            if (finalSteps.isNotEmpty()) vm.addChain(chainTitle.trim(), finalSteps)
                            chainTitle = ""; chainStepText = ""; pendingSteps = emptyList(); chainDialogStep = 0
                            chains = vm.loadChains()
                        }) { Text(if (pendingSteps.isNotEmpty()) "完成" else "跳过", color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.body) }
                    }
                )
            }
        }

        val primaryColor = AchatTheme.colors.primary
        if (!showChains && items.any { it.done }) {
            Box(Modifier.fillMaxWidth().padding(12.dp).align(Alignment.BottomCenter).navigationBarsPadding()) {
                Row(
                    Modifier.drawBackdrop(
                        backdrop = backdrop, shape = { Capsule() },
                        effects = { blur(4f.dp.toPx()); lens(6f.dp.toPx(), 12f.dp.toPx()) },
                        layerBlock = {
                            val s = lerp(1f, clearDampedDrag.pressedScale, clearDampedDrag.pressProgress)
                            scaleX = s; scaleY = s
                        },
                        onDrawSurface = { drawRect(primaryColor.copy(alpha = 0.9f)) }
                    )
                    .then(clearHighlight.modifier)
                    .then(clearDampedDrag.modifier)
                    .clickable { vm.clearDone() }.height(44.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) { Text("🗑️ 清除已完成", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = AchatTheme.typography.title) }
            }
        }
    }
}

@Composable
private fun PromptBuilderPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var species by remember { mutableStateOf("") }
    var appearance by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var userNick by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var backstory by remember { mutableStateOf("") }
    var banEmoji by remember { mutableStateOf(true) }
    var banWaveLine by remember { mutableStateOf(true) }
    var minSentences by remember { mutableStateOf(true) }
    var banSelfAware by remember { mutableStateOf(true) }
    var showPreview by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    val pageBgModifier = when(AchatTheme.colors.themeId) {
        "newspaper" -> Modifier.newspaperBackground(AchatTheme.colors.background)
        "washi" -> Modifier.washiBackground(AchatTheme.colors.background)
        else -> Modifier.background(AchatTheme.colors.background)
    }

    val generatedPrompt = buildString {
        append("以下是你的人设：\n")
        if (name.isNotEmpty()) append("你叫${name}") else append("你叫[未填]")
        if (species.isNotEmpty()) append("，是一个${species}。\n") else append("。\n")
        if (appearance.isNotEmpty()) append("${appearance}\n")
        if (personality.isNotEmpty()) append("${personality}\n")
        if (backstory.isNotEmpty()) append("\n以下是你的背景故事：\n${backstory}\n")
        if (name.isNotEmpty()) append("你会用\"我\"称呼自己。\n")
        if (userName.isNotEmpty()) {
            append("\n以下是我的设定：\n")
            append("我是你的朋友，我的名字是\"${userName}\"。")
            if (userNick.isNotEmpty()) append("你对我的爱称是\"${userNick}\"。")
            if (relation.isNotEmpty()) append("\n${relation}")
            append("\n")
        }
        append("\n以下是对话格式要求：\n")
        if (banEmoji) append("你绝对禁止使用任何颜文字！\n")
        if (banWaveLine) append("不能出现用波浪号链接的句子。\n")
        if (minSentences) append("你的每次回复最好不少于三到四句。\n")
        append("你可以用括号（）来描述自己的动作。\n")
        if (banSelfAware) append("你绝对不允许通过任何方式说自己是AI或结束对话。\n")
        append("你必须严格遵守以上格式规定。")
    }

    Column(Modifier.fillMaxSize().then(pageBgModifier).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(AchatTheme.colors.surface).padding(horizontal = 16.dp)
                .then(if (AchatTheme.colors.themeId == "newspaper") Modifier.headerDoubleRule() else Modifier)
                .then(if (AchatTheme.colors.themeId == "washi") Modifier.sumiBorder(AchatTheme.colors.divider) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = AchatTheme.colors.onSurface) }
            Spacer(Modifier.width(4.dp))
            Text("人设工坊", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.title)
        }
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            PromptSectionHeader("ta是？")
            CardField("名字", name, { name = it }, "例：鹿鸣")
            CardField("种族", species, { species = it }, "例：猫娘 / 狐仙 / 机器人")

            Spacer(Modifier.height(8.dp))
            PromptSectionHeader("ta長？")
            CardField("外貌描述", appearance, { appearance = it }, "例：银白长发，琥珀色眼瞳，穿白色连衣裙")

            Spacer(Modifier.height(8.dp))
            PromptSectionHeader("ta会？")
            CardField("性格特质", personality, { personality = it }, "例：温柔体贴，偶尔傲娇，喜欢捉弄人")

            Spacer(Modifier.height(8.dp))
            PromptSectionHeader("ta的过往？")
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("背景故事", fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.body)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    backstory, { backstory = it }, Modifier.fillMaxWidth().height(100.dp),
                    placeholder = { Text("例：曾是流浪猫，被主角收养后化为人形", fontSize = 14.sp) },
                    maxLines = 4, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider)
                )
            }

            Spacer(Modifier.height(8.dp))
            PromptSectionHeader("与ta的关系？")
            CardField("我的名字", userName, { userName = it }, "例：云帆")
            CardField("对我的爱称", userNick, { userNick = it }, "例：小帆 / 哥哥 / 主人")
            CardField("关系描述", relation, { relation = it }, "例：从小一起长大的青梅竹马")

            Spacer(Modifier.height(8.dp))
            PromptSectionHeader("对话格式")
            CheckRow("禁止颜文字", banEmoji, { banEmoji = it })
            CheckRow("禁止波浪号", banWaveLine, { banWaveLine = it })
            CheckRow("最少三句回复", minSentences, { minSentences = it })
            CheckRow("禁止说自己是AI", banSelfAware, { banSelfAware = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showPreview = !showPreview },
                    colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.onSurface.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (showPreview) "隐藏预览" else "预览", fontSize = 14.sp, fontFamily = AchatTheme.typography.title) }
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(generatedPrompt))
                        Toast.makeText(ctx, "已复制到剪贴板喵~", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AchatTheme.colors.primary),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("复制人设", fontSize = 14.sp, fontFamily = AchatTheme.typography.title) }
            }

            if (showPreview) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(12.dp)
                ) {
                    Text(generatedPrompt, fontSize = 13.sp, lineHeight = 18.sp, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PromptSectionHeader(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.title)
}

@Composable
private fun CardField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f), fontFamily = AchatTheme.typography.body)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider)
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(AchatTheme.shapes.card).background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = AchatTheme.colors.onSurface, fontFamily = AchatTheme.typography.body)
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = AchatTheme.colors.primary))
    }
}

private fun buildMixedText(text: String, cn: FontFamily, en: FontFamily) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val isAscii = c.code in 0x0020..0x007E || c.code in 0x0030..0x0039
        val start = i
        while (i < text.length && isAscii == (text[i].code in 0x0020..0x007E || text[i].code in 0x0030..0x0039)) i++
        val font = if (isAscii) en else cn
        withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = font)) { append(text.substring(start, i)) }
    }
}

/**
 * Markdown 解析器
 * 支持：行首列表 (- ), **粗体**, *斜体*, `行内代码`
 */
private fun parseMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { index, line ->
        var currentLine = line.trim()
        if (currentLine.isEmpty()) {
            if (index != lines.lastIndex) append("\n")
            return@forEachIndexed
        }

        // 处理行首列表
        if (currentLine.startsWith("- ")) {
            append("  • ")
            currentLine = currentLine.substring(2)
        }

        var cursor = 0
        // 匹配 **bold**, *italic*, `code`
        val pattern = Regex("""(\*\*.*?\*\*|\*.*?\*|`.*?`)""")
        val matches = pattern.findAll(currentLine)

        for (match in matches) {
            append(currentLine.substring(cursor, match.range.first))

            val matchText = match.value
            when {
                matchText.startsWith("**") && matchText.endsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF333333))) {
                        append(matchText.removeSurrounding("**"))
                    }
                }
                matchText.startsWith("*") && matchText.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(matchText.removeSurrounding("*"))
                    }
                }
                matchText.startsWith("`") && matchText.endsWith("`") -> {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFFF0F0F0),
                        color = Color(0xFFE83E8C)
                    )) {
                        append(" ${matchText.removeSurrounding("`")} ")
                    }
                }
                else -> append(matchText)
            }
            cursor = match.range.last + 1
        }
        
        if (cursor < currentLine.length) {
            append(currentLine.substring(cursor))
        }
        
        if (index != lines.lastIndex) append("\n")
    }
}


//发现页内容预览
@Preview(showBackground = true)
@Composable
fun DiscoverScreenContentPreview() {
    val sampleItems = listOf(
        DiscoverItem("1", "朋友圈", R.drawable.ic_moments),
        DiscoverItem("2", "喵神谕", R.drawable.ic_cat_fortune),
        DiscoverItem("3", "喵神の试炼", R.drawable.ic_shake),
        DiscoverItem("4", "Too-Do", R.drawable.ic_discover)
    )
    val previewCn = FontFamily(Font(R.font.notoserifsc_bold))
    val previewEn = FontFamily(Font(R.font.special_elite_regular))
    MaterialTheme {
        DiscoverScreenContent(
            items = sampleItems,
            hitokoto = "蛋糕店里卖蛋糕。",
            from = "梅川·库子「真理言」",
            cnFont = previewCn,
            enFont = previewEn,
            onCatClick = {},
            onChallengeClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DiscoverScreenPreview() {
    val sampleItems = listOf(
        DiscoverItem("1", "朋友圈", R.drawable.ic_moments),
        DiscoverItem("2", "喵神谕", R.drawable.ic_cat_fortune),
        DiscoverItem("3", "喵神の试炼", R.drawable.ic_shake),
        DiscoverItem("4", "Too-Do", R.drawable.ic_discover)
    )
    MaterialTheme {
        DiscoverScreen(items = sampleItems)
    }
}
