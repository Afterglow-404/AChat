# Checklist

## Phase 1: 主线程阻塞清理

- [ ] `WispApplication` 类已创建并注册到 AndroidManifest
- [ ] `AppDatabase.build()` 不再同步调用 `LegacyMigrator.migrateIfFirstRun`
- [ ] `AppDatabase.preInit(ctx)` 是 suspend 函数，在 `WispApplication.onCreate` 的 IO 协程中调用
- [ ] `AppDatabase` 不再使用 `fallbackToDestructiveMigration(dropAllTables = true)`
- [ ] `ChatHistory.loadEntries` / `saveEntries` 是 `suspend fun`，无 `runBlocking`
- [ ] `ChatHistory.appendMessage` 单条插入 API 已实现，`saveEntries` 不再每次全量重写
- [ ] `WorldbookStore` 全部公共 API 是 `suspend fun`
- [ ] `WorldbookStore.update` / `delete` 用直接 SQL 定位，不再全量加载后内存过滤
- [ ] `GroupChatManager` 全部公共 API 是 `suspend fun`
- [ ] `BuiltInCharacterLoader.listAll` 有内存缓存，不再每次读 assets
- [ ] `ChatsViewModel` 构造函数不再 `runBlocking`，用 `init { viewModelScope.launch ... }` 异步加载
- [ ] `ChatsViewModel.setSearchQuery` / `deleteChat` / `togglePin` / `saveChats` 是 `suspend fun`
- [ ] `MemoryStore.save` / `search` / `embed` 是 `suspend fun`
- [ ] `MemoryStore.embedCache` 用 `ConcurrentHashMap`，无全局 `synchronized`
- [ ] `ImageUtil.savePickedImage` 是 `suspend fun`，有 `onError` 回调
- [ ] `ChatScreen.kt:127` `ToolRegistry.init` 在 `withContext(Dispatchers.IO)` 中执行
- [ ] `ChatScreen.kt:124-130` 多个 init 用 `async` 并行
- [ ] `ChatScreen.kt:905-924` Bitmap 解码 + MediaStore 写入在 `Dispatchers.IO` 中
- [ ] 全项目搜索 `runBlocking` 仅在测试代码中出现（main 代码 0 处）
- [ ] 进入聊天页首帧渲染 < 100ms（用 Profiler 验证）
- [ ] 发送消息时用户消息气泡立即显示，无卡顿

## Phase 2: 数据库批量查询与 UI 渲染

- [ ] `ChatDao.getAllWithLastMessage` JOIN 查询已实现
- [ ] `MessageDao.searchChatIds` 一次查所有匹配会话
- [ ] `ChatWithLastMessage` data class 已定义
- [ ] `ChatsViewModel.loadChats` 用 JOIN 一次取所有会话 + 最新消息
- [ ] `ChatsViewModel.setSearchQuery` 用 `searchChatIds` 一次查
- [ ] 100 个会话 `loadChats` 耗时 < 50ms
- [ ] `Bubble` data class 有 `id: String` 字段
- [ ] `ChatScreen.kt:808` LazyColumn `key = { _, b -> b.id }`
- [ ] `GroupChatMessage` data class 有 `id: String` 字段
- [ ] `GroupChatScreen.kt:112` `messages` 是 `mutableStateListOf`，不是 `mutableStateOf(emptyList())`
- [ ] `GroupChatScreen` 接话链用 `messages.add(...)`，不再 `messages = messages + ...`
- [ ] `GroupChatScreen.kt:596` LazyColumn `key = { it.id }`
- [ ] `GroupChatBubble` 加 `@Immutable`，`GroupChatMessage` 加 `@Immutable`
- [ ] `BuiltInCharacterLoader` 有 `LruCache<String, Bitmap>` 头像缓存
- [ ] `BuiltInCharacterLoader.loadAvatarBitmap` 是 `suspend fun`
- [ ] `ChatScreen.kt:823-829, 1168-1176` 头像加载在 `LaunchedEffect` 中异步
- [ ] `decodeSampledBitmap(path, reqW, reqH)` 工具函数已实现，正确计算 `inSampleSize`
- [ ] `ChatScreen.kt:117-122` 聊天背景在 `LaunchedEffect` 中异步解码 + 下采样
- [ ] `WeChatApp.kt:106-112` 同上
- [ ] `NewspaperComponents.newspaperBackground` 用 `drawWithCache` 缓存为 `ImageBitmap`
- [ ] `WashiComponents.washiBackground` 用 `drawWithCache` 缓存为 `ImageBitmap`
- [ ] `WashiComponents.sumiBorder` 用 `drawWithCache` 缓存 `Path`
- [ ] `StaggeredEntrance` 仅对新增 item 跑动画，老 item 直接渲染
- [ ] 列表快速滚动帧率 ≥ 50fps（用 Profiler GPU 渲染验证）
- [ ] 群聊接话链连续追加 6 条消息，已有 item 不重组（用 Layout Inspector 验证）

## Phase 3: 网络层与模型生命周期

- [ ] `AiService` 接口 `sendMessage` / `sendMessageStream` 是 `suspend fun`
- [ ] `OpenAiService` / `ClaudeAiService` 公共 API 是 `suspend fun`，无 `runBlocking`
- [ ] `OpenAiService.sendMessageStream` 返回 `Flow<String>`，用 `callbackFlow` 包装
- [ ] `callbackFlow` 的 `awaitClose { call.cancel() }` 正确实现，离开页面协程 cancel 立即停止 SSE
- [ ] SSE chunk 解析用 `ksonx.serialization.JsonReader`，不再每 chunk 一次 `JSONObject`
- [ ] 静默 `catch (_: Exception)` 改为 `Log.w` 有日志
- [ ] `ToolRegistry` 有 `@Volatile toolsJsonCache` 字段 + `invalidateToolsCache()` 方法
- [ ] `ToolRegistry.register` / `unregister` 时调用 `invalidateToolsCache()`
- [ ] `OpenAiService.buildToolsArray` 用缓存而非每次重建
- [ ] `OpenAiService.encodeImageBase64` 用 `Base64OutputStream` 流式编码
- [ ] `OpenAiService` token 计数内存累加，每 10 次或 `shutdown()` 时批量 apply
- [ ] `MCPClient.listTools` / `callTool` 是 `suspend fun`
- [ ] `ToolRegistry.unregister(name)` / `unregisterByOwner(predicate)` 已实现
- [ ] `MCPBridge` 刷新时先 `unregisterByOwner { it is MCPToolProxy }` 清理旧工具
- [ ] `MCPBridge.listTools` 并行化（`async { ... }.awaitAll()`）
- [ ] `LocalWhisperSttProvider` / `LocalSenseVoiceSttProvider` 是单例或绑定 Application
- [ ] `WispApplication.onTrimMemory(TRIM_MEMORY_MODERATE)` 调用 STT `shutdown()`
- [ ] `CloudTtsService` 用 `byteStream` 流式写盘，不再 `bytes()` 一次性入内存
- [ ] `cacheDir/tts/` 有 50MB LRU 清理
- [ ] `VoiceTts` 单例化，`TextToSpeech` 实例复用
- [ ] AI 流式响应期间离开页面，协程立即 cancel（用 logcat 验证 `call.cancel()` 被调用）
- [ ] 长 SSE 流（10000+ chunks）内存峰值增长 < 20MB

## Phase 4: 其他优化与 bug 修复

- [ ] `ClaudeAiService.kt:286` 运算符优先级 bug 已修复（`(length ?: 0) * 4`）
- [ ] `ToolScanner.scanned` 加 `@Volatile`
- [ ] `HttpRetry` 循环改 `0 until MAX_RETRIES`，语义清晰
- [ ] `HttpRetry` 同步版 `retry()` 已删除
- [ ] `HttpRetry` 流式失败不重试（用 `retrySuspendNoRetry` 或参数控制）
- [ ] `AudioDecoder.kt:80` `ByteArrayOutputStream(1024 * 1024)` 预分配
- [ ] `LocalWhisperSttProvider` 音频解码移出 `inferMutex.withLock`
- [ ] `XfyunSttProvider.kt:116` 用 `ByteString.of(pcmBytes, offset, length)` 三参版本
- [ ] `XfyunSttProvider.kt:120` `Thread.sleep(40)` 改非阻塞调度
- [ ] `XfyunSttProvider.kt:103-178` 用 `suspendCancellableCoroutine` 替代 `CountDownLatch.await`
- [ ] `LlamaEngine.kt` / `LocalAiService.kt` / `llama_wisp.cpp` 已删除
- [ ] `AiServiceFactory` 移除 `protocol == "local"` 分支
- [ ] `DebugPage` "本地模式"开关已隐藏
- [ ] `proguard-rules.pro` 移除 `LlamaEngine` keep 规则
- [ ] `ProactiveWorker : CoroutineWorker` 已实现
- [ ] `ProactiveScheduler.enqueue` 用 WorkManager，延迟 30s 首次执行
- [ ] `MainActivity.kt:32` 不再同步触发 `ProactiveScheduler.runOnce`

## 整体验证

- [ ] `./gradlew.bat test --no-daemon` 全部通过
- [ ] `./gradlew.bat :app:assembleDebug --no-daemon` 构建成功
- [ ] 装机测试：进入聊天页、发送消息、群聊、搜索、AI 流式响应、STT 录音、TTS 朗读均无卡顿
- [ ] Android Studio Profiler：所有用户操作期间主线程无明显绿色块（< 16ms）
- [ ] 全项目搜索 `runBlocking`：main 代码 0 处，test 代码允许
- [ ] 全项目搜索 `BitmapFactory.decodeFile` / `BitmapFactory.decodeStream`：所有调用均在 `Dispatchers.IO` 中或有 `inSampleSize`
- [ ] 全项目搜索 `fallbackToDestructiveMigration`：已删除或限定到具体旧版本
