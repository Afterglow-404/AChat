# Tasks

按 4 个 Phase 推进，每个 Phase 独立可验证、可 commit。建议每个 Phase 完成后本地 build + 装机测试再进入下一阶段。

## Phase 1: 主线程阻塞清理（P0，收益最大）— ✅ 完成（编译 + 测试通过）

- [x] Task 1.1: 创建 `WispApplication` 类继承 `Application`，注册到 `AndroidManifest.xml` 的 `<application android:name>`，在 `onCreate` 中用 `CoroutineScope(Dispatchers.IO).launch` 异步触发 `AppDatabase.get(this)` 预热 + `LegacyMigrator.migrateIfFirstRun` 迁移；在 `onTrimMemory(level)` 中按 level 触发 STT recognizer 释放（与 Task 3.6 联动）
  - [ ] SubTask 1.1.1: 新建 `app/src/main/java/com/aftglw/devapi/WispApplication.kt`，`class WispApplication : Application()`，`onCreate` 中 `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { AppDatabase.preInit(this@WispApplication) }`
  - [ ] SubTask 1.1.2: 在 `AppDatabase.kt` 新增 `suspend fun preInit(ctx: Context)`，内部调用 `LegacyMigrator.migrateIfFirstRun`
  - [ ] SubTask 1.1.3: `AppDatabase.build()` 中移除 `LegacyMigrator.migrateIfFirstRun(ctx, db)` 同步调用
  - [ ] SubTask 1.1.4: `AndroidManifest.xml` `<application>` 标签添加 `android:name=".WispApplication"`

- [ ] Task 1.2: `AppDatabase` 移除 `fallbackToDestructiveMigration(dropAllTables = true)`，改为抛出 `IllegalStateException("Migration required but no migration path")`；开启 `exportSchema = true` 并提交首次 schema JSON
  - [ ] SubTask 1.2.1: 编辑 `AppDatabase.kt` 删除 `fallbackToDestructiveMigration` 行
  - [ ] SubTask 1.2.2: `Room.databaseBuilder` 后追加 `.fallbackToDestructiveMigrationFrom(1, 2, 3)`（仅对历史版本允许，新版本必须显式迁移）
  - [Task 1.2 依赖 Task 1.1]

- [x] Task 1.3: `ChatHistory` 全部 API 改 `suspend fun`，删除 `runBlocking`
  - [ ] SubTask 1.3.1: `ChatHistory.kt` 中 `loadEntries` / `saveEntries` 改 `suspend fun`，去掉 `runBlocking { withContext(Dispatchers.IO) { ... } }` 包装，内部直接用 suspend Room 调用
  - [ ] SubTask 1.3.2: `saveEntries` 新增 `appendMessage(ctx, chatKey, message)` 单条插入 API，避免每次全量重写
  - [ ] SubTask 1.3.3: 更新调用方 `ChatScreen.kt:131, 214, 312, 358`：`LaunchedEffect(Unit) { ... }` 内直接 await；`sendUserMessage` 中持久化改为 `scope.launch(Dispatchers.IO) { ChatHistory.appendMessage(...) }`

- [ ] Task 1.4: `WorldbookStore` 全部 API 改 `suspend fun`
  - [ ] SubTask 1.4.1: `WorldbookStore.kt` 中 `load` / `save` / `update` / `delete` / `getForChat` 改 `suspend fun`
  - [ ] SubTask 1.4.2: `update` / `delete` 内部新增直接 SQL 定位方法 `@Query("SELECT * FROM worldbook WHERE chat_name = :cn AND original_id = :oid")`，避免全量加载后内存过滤
  - [ ] SubTask 1.4.3: 更新调用方 `ChatInfoPage.kt:164`、`WorldbookPage.kt` 等：在 `LaunchedEffect` 或 `rememberCoroutineScope().launch` 中 await

- [x] Task 1.5: `GroupChatManager` 全部 API 改 `suspend fun`
  - [ ] SubTask 1.5.1: `GroupChatManager.kt` 中 `loadGroups` / `saveGroups` / `addGroup` / `updateGroup` / `deleteGroup` / `getMemberPersona` / `getMemberAvatarUri` / `getAvailableMembers` 改 `suspend fun`
  - [ ] SubTask 1.5.2: `BuiltInCharacterLoader.listAll(ctx)` 加内存缓存（`@Volatile private var cache: List<...>? = null`），避免每次调用读 assets
  - [ ] SubTask 1.5.3: 更新调用方 `ChatsScreen.kt:69, 71`、`GroupChatScreen.kt`、`GroupInfoPage.kt`：去掉 `remember { mutableStateOf(GroupChatManager.loadGroups(ctx)) }` 同步初始化，改 `LaunchedEffect(Unit) { groups.value = loadGroups(ctx) }` + 初始空 list

- [ ] Task 1.6: `ChatsViewModel` 全部方法改 `suspend` / 异步
  - [ ] SubTask 1.6.1: `ChatsViewModel.kt:16` 移除 `private val _chats = MutableLiveData(loadChatsWithBuiltin())` 同步初始化，改 `MutableLiveData<List<Chat>>(emptyList())`
  - [ ] SubTask 1.6.2: 新增 `init { viewModelScope.launch(Dispatchers.IO) { _chats.postValue(loadChatsWithBuiltin()) } }`
  - [ ] SubTask 1.6.3: `setSearchQuery` / `deleteChat` / `togglePin` / `saveChats` / `loadChats` 改 `suspend fun`，删除 `runBlocking`
  - [ ] SubTask 1.6.4: 更新调用方 `ChatsScreen.kt`：所有 ViewModel 调用包在 `LaunchedEffect` 或 `rememberCoroutineScope().launch` 中

- [x] Task 1.7: `MemoryStore` 全部 API 改 `suspend fun`
  - [ ] SubTask 1.7.1: `MemoryStore.kt` 中 `save` / `search` / `embed` 改 `suspend fun`，`embed` 内部 `HttpClient.client.newCall(request).execute()` 改 `withContext(Dispatchers.IO) { ... }` 或用 OkHttp 协程扩展
  - [ ] SubTask 1.7.2: `embedCache` 的 `synchronized(this)` 改 `ConcurrentHashMap`
  - [ ] SubTask 1.7.3: 更新调用方 `ChatMemoryPage.kt:30, 39, 58`、`ChatDiaryPage.kt:29, 51`、`ChatInfoPage.kt:151`：在 `LaunchedEffect` 或 `rememberCoroutineScope().launch` 中 await

- [x] Task 1.8: `ImageUtil.savePickedImage` 改 `suspend fun`
  - [ ] SubTask 1.8.1: `ImageUtil.kt:30` `savePickedImage` 改 `suspend fun`，内部用 `withContext(Dispatchers.IO) { ... }`
  - [ ] SubTask 1.8.2: 新增 `onError: (String) -> Unit` 回调参数，替换 `catch (_: Exception)`
  - [ ] SubTask 1.8.3: 更新调用方 `ChatScreen.kt:705, 719`、`GroupChatScreen.kt:163, 175`：`rememberLauncherForActivityResult` 回调中 `scope.launch { savePickedImage(...) }`

- [ ] Task 1.9: `ChatScreen.kt:127` `ToolRegistry.init` 切到 IO 线程
  - [ ] SubTask 1.9.1: `ChatScreen.kt:124-130` 改为 `LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ToolRegistry.init(ctx) } ; async { MemoryStore.init(ctx) } ; async { MoodDetector.init(ctx) } ; async { StickerEngine.init(ctx) } ; async { ChatHistory.loadEntries(...) }.await() }`，并行化多个 init

- [x] Task 1.10: 移除 `ChatScreen.kt:905-924` 主线程 Bitmap 解码 + MediaStore 写入
  - [ ] SubTask 1.10.1: 把 `BitmapFactory.decodeStream` + `MediaStore.Images.Media.insertImage` 包装到 `scope.launch(Dispatchers.IO) { ... }`，UI 显示进度 Toast

## Phase 2: 数据库批量查询与 UI 渲染（P0/P1）

- [ ] Task 2.1: `Daos.kt` 新增批量查询 DAO
  - [ ] SubTask 2.1.1: `ChatDao` 新增 `@Query("SELECT * FROM chats ORDER BY pinned DESC, name ASC")` `suspend fun getAllWithLastMessage(): List<ChatWithLastMessage>`
  - [ ] SubTask 2.1.2: `MessageDao` 新增 `@Query("SELECT DISTINCT chat_id FROM messages WHERE text LIKE :q")` `suspend fun searchChatIds(q: String): List<String>`
  - [ ] SubTask 2.1.3: 新增 `data class ChatWithLastMessage(@Embedded val chat: ChatEntity, @ColumnInfo(name = "last_text") val lastText: String?)`
  - [Task 2.1 依赖 Task 1.6]

- [ ] Task 2.2: `ChatsViewModel.loadChats` 用 JOIN 查询替换 N+1
  - [ ] SubTask 2.2.1: `loadChats()` 改用 `chatDao.getAllWithLastMessage()` 一次取所有
  - [ ] SubTask 2.2.2: `setSearchQuery` 改用 `messageDao.searchChatIds("%$q%")` 一次查
  - [Task 2.2 依赖 Task 2.1]

- [ ] Task 2.3: `ChatScreen.kt:808` LazyColumn key 改用业务 id
  - [ ] SubTask 2.3.1: `Bubble` data class 新增 `val id: String = UUID.randomUUID().toString()` 字段
  - [ ] SubTask 2.3.2: `ChatScreen.kt:808` 改 `itemsIndexed(bubbles, key = { _, b -> b.id })`

- [ ] Task 2.4: `GroupChatScreen.kt` LazyColumn key + mutableStateListOf
  - [ ] SubTask 2.4.1: `GroupChatMessage` data class 新增 `val id: String = UUID.randomUUID().toString()` 字段
  - [ ] SubTask 2.4.2: `GroupChatScreen.kt:112` 改 `val messages = remember { mutableStateListOf<GroupChatMessage>() }`
  - [ ] SubTask 2.4.3: `GroupChatScreen.kt:340, 378, 446, 511` 改 `messages.add(...)` 替代 `messages = messages + ...`
  - [ ] SubTask 2.4.4: `GroupChatScreen.kt:596` 改 `key = { it.id }`
  - [ ] SubTask 2.4.5: `GroupChatBubble` 加 `@Immutable`，`GroupChatMessage` 加 `@Immutable`

- [ ] Task 2.5: `BuiltInCharacterLoader` 头像 LruCache
  - [ ] SubTask 2.5.1: `BuiltInCharacterLoader.kt` 新增 `private val avatarCache = LruCache<String, Bitmap>(16 * 1024 * 1024)`（16MB）
  - [ ] SubTask 2.5.2: `loadAvatarBitmap(ctx, name)` 改 `suspend fun`，先查 cache → 命中返回；未命中 `withContext(IO) { decodeStream }` 后 put cache
  - [ ] SubTask 2.5.3: `ChatScreen.kt:823-829, 1168-1176`、`WeChatApp.kt:107-112`、`ChatInfoPage.kt:68`、`ProfilePage.kt:53`、`ManageRolesPage.kt:54`、`MeScreen.kt:116` 改用 `LaunchedEffect(name) { ... }` 异步加载

- [ ] Task 2.6: `ChatScreen.kt:117-122` 聊天背景异步解码 + 下采样
  - [ ] SubTask 2.6.1: 新增 `decodeSampledBitmap(path, reqW, reqH): Bitmap?` 工具函数（`inSampleSize` 计算）
  - [ ] SubTask 2.6.2: `ChatScreen.kt:117-122` 改 `LaunchedEffect(chatBgUri) { var bmp by mutableStateOf<ImageBitmap?>(null); bmp = withContext(IO) { decodeSampledBitmap(it, screenWidth, screenHeight)?.asImageBitmap() } }`
  - [ ] SubTask 2.6.3: `WeChatApp.kt:106-112` 同上处理

- [ ] Task 2.7: `NewspaperBackground` / `WashiBackground` / `sumiBorder` 用 `drawWithCache` 预渲染
  - [ ] SubTask 2.7.1: `NewspaperComponents.kt:16-56` 改 `Modifier.drawWithCache { val cache = ...ImageBitmap...; onDrawBehind { drawImage(cache) } }`
  - [ ] SubTask 2.7.2: `WashiComponents.kt:23-63` 同上
  - [ ] SubTask 2.7.3: `WashiComponents.kt:69-113` `sumiBorder` 改 `Modifier.drawWithCache { val path = buildPath(...); onDrawBehind { drawPath(path, ...); drawPath(path, ...) } }`

- [ ] Task 2.8: `StaggeredEntrance` 仅对新增 item 动画
  - [ ] SubTask 2.8.1: `StaggeredEntrance` 内部用 `remember { mutableStateOf(false) }` + `LaunchedEffect(Unit) { visible = true }`，但加 `rememberSaveable { mutableStateOf(false) }` 记忆"已动画过"
  - [ ] SubTask 2.8.2: 老 item（已动画过）直接传 `visible = true` 跳过动画

## Phase 3: 网络层与模型生命周期（P1）

- [ ] Task 3.1: `OpenAiService` / `ClaudeAiService` 改 `suspend fun`
  - [ ] SubTask 3.1.1: `AiService.kt` 接口 `sendMessage` / `sendMessageStream` 改 `suspend fun`
  - [ ] SubTask 3.1.2: `OpenAiService.kt:39, 142` / `ClaudeAiService.kt:30, 104` 删除 `runBlocking { ... }` 包装
  - [ ] SubTask 3.1.3: `call.execute()` 改用 OkHttp 协程扩展 `suspend Call.execute()` 或 `withContext(Dispatchers.IO) { call.execute() }`
  - [ ] SubTask 3.1.4: `Agent.kt:59, 71` 移除多余的 `withContext(Dispatchers.IO)`（service 内部已 suspend）
  - [Task 3.1 依赖 Phase 1]

- [ ] Task 3.2: SSE 流式响应用 `callbackFlow` 包装
  - [ ] SubTask 3.2.1: `sendMessageStream` 返回 `Flow<String>`，内部 `callbackFlow { ... awaitClose { call.cancel() } }`
  - [ ] SubTask 3.2.2: 调用方 `Agent.kt` 改用 `.collect { onChunk(it) }`
  - [Task 3.2 依赖 Task 3.1]

- [ ] Task 3.3: SSE chunk 解析改用 `kotlinx.serialization` 流式 `JsonReader`
  - [ ] SubTask 3.3.1: `OpenAiService.kt:175` 删除 `JSONObject(data)`，改用 `JsonReader(source.buffer.inputStream().reader()).use { ... }` 复用 parser
  - [ ] SubTask 3.3.2: 静默 catch (`OpenAiService.kt:197`) 改为 `Log.w(TAG, "parse chunk fail: $data", e)`
  - [Task 3.3 依赖 Task 3.2]

- [ ] Task 3.4: `OpenAiService` 缓存 tools JSON + 流式 Base64 + token 计数内存累加
  - [ ] SubTask 3.4.1: `ToolRegistry.kt` 新增 `@Volatile private var toolsJsonCache: JSONArray?`，`getAll()` 不变；新增 `invalidateToolsCache()`；`register` / `unregister` 时调用 invalidate
  - [ ] SubTask 3.4.2: `OpenAiService.kt:282-296` 改 `private val toolsJsonCache = ToolRegistry.getToolsJson()`，调用 `ToolRegistry.invalidateToolsCache()` 时同步刷新
  - [ ] SubTask 3.4.3: `OpenAiService.kt:354-361` `encodeImageBase64` 改用 `Base64OutputStream` 流式包装 `file.outputStream()`
  - [ ] SubTask 3.4.4: `OpenAiService.kt:97-112` token 计数改内存累加 `private var pendingTokenWrites = 0`，每 10 次或 `shutdown()` 时批量 apply

- [ ] Task 3.5: `MCPClient` / `MCPBridge` 改 suspend + 修 stale 工具 bug
  - [ ] SubTask 3.5.1: `MCPClient.kt` `listTools` / `callTool` 改 `suspend fun`，`execute()` 改 `withContext(Dispatchers.IO) { ... }`
  - [ ] SubTask 3.5.2: `ToolRegistry.kt` 新增 `unregister(name: String)` 和 `unregisterByOwner(predicate: (AiTool) -> Boolean)`
  - [ ] SubTask 3.5.3: `MCPBridge.kt:30-37` 刷新时先 `ToolRegistry.unregisterByOwner { it is MCPToolProxy }`，再注册新工具
  - [ ] SubTask 3.5.4: `MCPBridge.kt:41-49` `listTools` 并行化：`clients.map { async { it.listTools() } }.awaitAll()`

- [ ] Task 3.6: STT/TTS native 资源生命周期
  - [ ] SubTask 3.6.1: `LocalWhisperSttProvider` / `LocalSenseVoiceSttProvider` 改 `object` 单例（替代 `class`），或绑定到 `WispApplication` 引用
  - [ ] SubTask 3.6.2: `WispApplication.onTrimMemory(TRIM_MEMORY_MODERATE)` 调用 `LocalWhisperSttProvider.shutdown()` + `LocalSenseVoiceSttProvider.shutdown()`
  - [ ] SubTask 3.6.3: `CloudTtsService.kt:82-84` 改流式写盘 `response.body?.byteStream()?.use { it.copyTo(cacheFile.outputStream(), 64 * 1024) }`
  - [ ] SubTask 3.6.4: `CloudTtsService.kt:59` `cacheDir/tts/` 加 LRU 清理：每次写入后检查总大小，超过 50MB 按 lastModified 删除最旧
  - [ ] SubTask 3.6.5: `VoiceTts.kt` 改单例 `object VoiceTts` 或 `companion object` 持有 `TextToSpeech` 实例，避免每次 `new` 重新初始化
  - [Task 3.6 部分依赖 Task 1.1]

## Phase 4: 其他优化与 bug 修复（P2/P3）

- [ ] Task 4.1: bug 修复
  - [ ] SubTask 4.1.1: `ClaudeAiService.kt:286` 改 `(body.optJSONArray("messages")?.length() ?: 0) * 4`
  - [ ] SubTask 4.1.2: `ToolScanner.kt:19` `scanned` 加 `@Volatile`
  - [ ] SubTask 4.1.3: `HttpRetry.kt:60` 改 `0 until MAX_RETRIES`，注释明确语义
  - [ ] SubTask 4.1.4: `HttpRetry.kt:53-75` 删除同步版 `retry()`，仅保留 `retrySuspend`

- [ ] Task 4.2: `AudioDecoder` 优化
  - [ ] SubTask 4.2.1: `AudioDecoder.kt:80` `ByteArrayOutputStream(1024 * 1024)` 预分配
  - [ ] SubTask 4.2.2: `LocalWhisperSttProvider.kt:121-124` 把 `AudioDecoder.decodeToPcmFloat(audioPath)` 移到 `inferMutex.withLock` 之前

- [ ] Task 4.3: `XfyunSttProvider` WebSocket 协程化
  - [ ] SubTask 4.3.1: `XfyunSttProvider.kt:116` 改 `ByteString.of(pcmBytes, pos, end - pos)` 三参版本，零拷贝
  - [ ] SubTask 4.3.2: `XfyunSttProvider.kt:120` `Thread.sleep(40)` 改 `Handler(Looper.getMainLooper()).postDelayed({ ws.send(...) }, 40)`，或用 `delay(40)` 在协程内
  - [ ] SubTask 4.3.3: `XfyunSttProvider.kt:103-178` `CountDownLatch.await(30s)` 改 `suspendCancellableCoroutine { cont -> ...; cont.invokeOnCancellation { ws.cancel() } }`

- [ ] Task 4.4: `HttpRetry` 流式失败不重试
  - [ ] SubTask 4.4.1: `HttpRetry.kt` 新增 `retrySuspendNoRetry()`（仅 1 次尝试），流式 `sendMessageStream` 用此版本
  - [ ] SubTask 4.4.2: 注释说明"流式失败不重试，避免半句话+重新开始的 UX 问题"

- [ ] Task 4.5: 删除本地 GGUF 推理桩代码
  - [ ] SubTask 4.5.1: 删除 `app/src/main/java/com/aftglw/devapi/core/ai/LlamaEngine.kt`
  - [ ] SubTask 4.5.2: 删除 `app/src/main/java/com/aftglw/devapi/network/LocalAiService.kt`
  - [ ] SubTask 4.5.3: 删除 `app/src/main/cpp/llama_wisp.cpp` 及 `CMakeLists.txt`（如有）
  - [ ] SubTask 4.5.4: `AiServiceFactory.kt:26-36` 移除 `protocol == "local"` 分支，返回 Mock 或抛 `UnsupportedOperationException`
  - [ ] SubTask 4.5.5: `DebugPage.kt:70` "本地模式"开关改为隐藏（`if (false) ToggleRow(...)`）或加注释 `// 已下线：本地 GGUF 推理待 llama.cpp 真实接入`
  - [ ] SubTask 4.5.6: `proguard-rules.pro` 移除 `-keep class com.aftglw.devapi.core.ai.LlamaEngine { *; }`

- [ ] Task 4.6: `ProactiveScheduler` 改 WorkManager
  - [ ] SubTask 4.6.1: 新建 `ProactiveWorker(context, params) : CoroutineWorker(context, params)`，`doWork()` 中调用 `ProactiveScheduler.runOnce(applicationContext)`
  - [ ] SubTask 4.6.2: `ProactiveScheduler.enqueue(ctx)` 改 `WorkManager.getInstance(ctx).enqueueUniqueWork("proactive", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ProactiveWorker>().setInitialDelay(30, TimeUnit.SECONDS).build())`
  - [ ] SubTask 4.6.3: `MainActivity.kt:32` `ProactiveScheduler.enqueue(this)` 保留，但延迟 30s 首次执行
  - [Task 4.6 依赖 Task 1.1（需要 WispApplication）]

## Task Dependencies

- Task 1.2 依赖 Task 1.1
- Task 2.1 依赖 Task 1.6
- Task 2.2 依赖 Task 2.1
- Task 3.1 依赖 Phase 1（所有 runBlocking 清理完成）
- Task 3.2 依赖 Task 3.1
- Task 3.3 依赖 Task 3.2
- Task 3.6 部分依赖 Task 1.1（onTrimMemory 钩子）
- Task 4.6 依赖 Task 1.1

## 平行化建议

- Phase 1 内 Task 1.3 / 1.4 / 1.5 / 1.7 / 1.8 可并行（独立模块）
- Phase 1 内 Task 1.9 / 1.10 可并行
- Phase 2 内 Task 2.3 / 2.4 / 2.5 / 2.6 / 2.7 / 2.8 可并行
- Phase 3 内 Task 3.4 / 3.5 / 3.6 可并行（3.1 / 3.2 / 3.3 需顺序）
- Phase 4 内 Task 4.1 / 4.2 / 4.3 / 4.4 / 4.5 可并行

## 验证策略

每个 Phase 完成后必须通过：
1. `./gradlew.bat test --no-daemon` 单元测试全绿
2. `./gradlew.bat :app:assembleDebug --no-daemon` 构建成功
3. 装机测试核心场景：进入聊天页、发送消息、群聊、搜索、AI 流式响应、STT 录音、TTS 朗读
4. 用 Android Studio Profiler 观察主线程：所有用户操作期间主线程无明显绿色块（< 16ms）

每个 Phase 完成后单独 commit + push，便于回滚。
