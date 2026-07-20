# 性能优化 Spec

## Why

对项目进行系统性性能调研后发现 30+ 个性能问题，集中表现在：

1. **主线程阻塞**：`ChatHistory` / `GroupChatManager` / `WorldbookStore` / `ChatsViewModel` / `MemoryStore` / `ImageUtil` / `LlamaEngine` 等 7 个核心模块的公共 API 全部用 `runBlocking { withContext(Dispatchers.IO) { ... } }` 包装 Room/网络/IO 操作，被 Composable 直接调用时阻塞主线程，导致进入聊天页、搜索、发送消息、进入群聊、进入世界书页等场景明显卡顿（500ms–3s）。
2. **数据库迁移阻塞启动**：`AppDatabase.kt:52` 在 `build()` 里同步调用 `LegacyMigrator.migrateIfFirstRun`（内部 `runBlocking`），首次启动迁移全量历史数据时主线程阻塞数秒。
3. **N+1 查询**：`ChatsViewModel.loadChats` 对每个会话调一次 `getLastMessageText`；`setSearchQuery` 对每个会话扫全量消息历史。100 个会话 = 100+ 次主线程 Room 查询。
4. **UI 渲染低效**：`ChatScreen` LazyColumn 用 index 当 key，列表变动触发整列 recomposition；`GroupChatScreen` 用 `mutableStateOf(emptyList())` + `messages + new` 每次复制整个 List；`NewspaperBackground`/`WashiBackground` 每帧重绘 800+ 圆 + 300+ 线。
5. **Bitmap 主线程同步解码无下采样**：聊天背景、头像、贴纸在主线程 `BitmapFactory.decodeFile` 无 `inSampleSize`，4K 大图 = 60MB+ 内存，UI 卡数百 ms。
6. **网络层嵌套阻塞**：`OpenAiService` / `ClaudeAiService` 用 `runBlocking` 包装 suspend 函数，长流式响应期间钉死线程，无法响应协程 cancel；每个 SSE chunk 都 `JSONObject(data)` 重新解析，长响应 GC 压力大。
7. **模型 native 内存无生命周期**：`LocalWhisperSttProvider` (~150MB) / `LocalSenseVoiceSttProvider` (~250MB) / `LlamaEngine` (~1–2GB) 的 recognizer 持有 native 资源，Activity 销毁时不释放。
8. **MCP stale 工具 bug**：`MCPBridge.kt:30-37` 刷新 MCP server 后旧工具无法从 `ToolRegistry` 移除，AI 可能调用到已失效的 client。
9. **运算符优先级 bug**：`ClaudeAiService.kt:286` `?: 0 * 4` 等价于 `?: 0`，token 估算永远少 4 倍。
10. **本地推理是桩代码**：`llama_wisp.cpp` 的 `nativeLoad`/`nativeGenerate` 返回硬编码字符串，`LocalAiService` 是死代码。

## What Changes

分 4 个阶段，每阶段独立可验证、可回滚：

### Phase 1: 主线程阻塞清理（P0，收益最大）
- **BREAKING**：`ChatHistory` / `WorldbookStore` / `GroupChatManager` / `ChatsViewModel` / `MemoryStore` / `ImageUtil` / `LlamaEngine` 公共 API 从同步签名改为 `suspend fun`，调用方需在 `viewModelScope.launch(Dispatchers.IO)` 或 `LaunchedEffect` 中调用。
- `ChatsViewModel` 构造函数 `loadChatsWithBuiltin()` 改为异步初始化，首屏显示空列表/骨架屏。
- `AppDatabase.build()` 中的 `LegacyMigrator.migrateIfFirstRun` 移到 `Application.onCreate` 的 IO 协程异步执行，UI 显示"正在升级"占位。
- `AppDatabase` 移除 `fallbackToDestructiveMigration(dropAllTables = true)`，迁移失败抛出明确异常而非丢数据。

### Phase 2: 数据库与 UI 渲染（P0/P1）
- `Daos.kt` 新增 JOIN 查询：`getLastMessagesByChatIds` 一次取所有会话最新消息；`searchChatsByText` 一次查所有匹配会话。
- `ChatScreen.kt:808` LazyColumn key 改用 `Bubble.id`（新增字段）。
- `GroupChatScreen.kt:596` LazyColumn key 改用 `GroupChatMessage.id`（新增字段）。
- `GroupChatScreen.kt:112` `messages` 改用 `mutableStateListOf` + `add` 替代 `mutableStateOf(emptyList())` + `+`。
- `GroupChatBubble` 加 `@Immutable` / `@Stable` 注解。
- `NewspaperBackground` / `WashiBackground` / `sumiBorder` 用 `Modifier.drawWithCache` 预渲染为 `ImageBitmap`/`Path`。
- `StaggeredEntrance` 仅对新增 item 做动画，老 item 直接渲染。

### Phase 3: 网络层与模型生命周期（P1）
- **BREAKING**：`OpenAiService` / `ClaudeAiService` / `MCPClient` / `MemoryStore.embed` 全部改 `suspend fun`，删除 `runBlocking`。
- `OpenAiService` 流式响应用 `callbackFlow` 包装，支持背压和取消。
- SSE chunk 解析改用 `kotlinx.serialization` 流式 `JsonReader`，复用 parser。
- `OpenAiService.buildToolsArray` 缓存到字段，`ToolRegistry` 增加 `invalidateCache()`。
- `OpenAiService.encodeImageBase64` 改用 `Base64OutputStream` 流式编码。
- `MCPBridge` 并行 `listTools`（`async { ... }.awaitAll()`）。
- `ToolRegistry` 增加 `unregister(name)` / `unregisterByOwner(predicate)`，`MCPBridge` 刷新时彻底清理旧工具。
- `LocalWhisperSttProvider` / `LocalSenseVoiceSttProvider` 绑定到 `Application.onTrimMemory(TRIM_MEMORY_MODERATE)` 释放 recognizer；`LlamaEngine` 实现 `AutoCloseable`。

### Phase 4: 其他优化与 bug 修复（P2/P3）
- `ClaudeAiService.kt:286` 修运算符优先级 bug。
- `AudioDecoder.kt:80` `ByteArrayOutputStream(1024 * 1024)` 预分配；解码移出 `inferMutex`。
- `XfyunSttProvider.kt:116` `copyOfRange` 改 `ByteString.of(pcmBytes, offset, length)` 三参版本；`Thread.sleep(40)` 改 `Handler.postDelayed`；`CountDownLatch.await(30s)` 改 `suspendCancellableCoroutine`。
- `CloudTtsService.kt:82` 流式写盘；`cacheDir/tts/` 加 50MB LRU；`VoiceTts` 单例化。
- `HttpRetry` 流式失败不重试（避免半句话+重新开始）；同步版 `retry()` 删除。
- `ToolScanner.scanned` 加 `@Volatile`。
- `OpenAiService` token 计数内存累加，每 N 次 batch apply。
- `ProactiveScheduler` 改 `WorkManager`，避免冷启动同步触发。
- `llama_wisp.cpp` 桩代码：要么补全 JNI，要么彻底删除 `LocalAiService`/`LlamaEngine`（推荐删除，减少维护负担）。

## Impact

- **Affected specs**: 无现有 spec（首次创建）
- **Affected code**:
  - `app/src/main/java/com/aftglw/devapi/core/storage/room/Daos.kt`、`AppDatabase.kt`、`Entities.kt`、`LegacyMigrator.kt`
  - `app/src/main/java/com/aftglw/devapi/core/storage/ChatHistory.kt`、`ChatDataManager.kt`
  - `app/src/main/java/com/aftglw/devapi/core/worldbook/WorldbookStore.kt`
  - `app/src/main/java/com/aftglw/devapi/core/memory/MemoryStore.kt`
  - `app/src/main/java/com/aftglw/devapi/feature/group/GroupChatManager.kt`、`GroupChatScreen.kt`
  - `app/src/main/java/com/aftglw/devapi/feature/chat/ChatScreen.kt`、`ChatInfoPage.kt`
  - `app/src/main/java/com/aftglw/devapi/viewmodel/ChatsViewModel.kt`
  - `app/src/main/java/com/aftglw/devapi/network/OpenAiService.kt`、`ClaudeAiService.kt`、`HttpClient.kt`、`HttpRetry.kt`、`AiService.kt`、`AiServiceFactory.kt`
  - `app/src/main/java/com/aftglw/devapi/tools/MCPClient.kt`、`MCPBridge.kt`、`ToolRegistry.kt`、`ToolScanner.kt`
  - `app/src/main/java/com/aftglw/devapi/core/voice/AudioDecoder.kt`、`LocalWhisperSttProvider.kt`、`LocalSenseVoiceSttProvider.kt`、`XfyunSttProvider.kt`、`CloudTtsService.kt`、`VoiceTts.kt`、`VoicePlayer.kt`
  - `app/src/main/java/com/aftglw/devapi/core/ai/LlamaEngine.kt`、`LocalAiService.kt`、`llama_wisp.cpp`
  - `app/src/main/java/com/aftglw/devapi/core/security/SecureKeyStore.kt`
  - `app/src/main/java/com/aftglw/devapi/core/character/BuiltInCharacterLoader.kt`
  - `app/src/main/java/com/aftglw/devapi/ui/theme/AchatTheme.kt`、`NewspaperComponents.kt`、`WashiComponents.kt`
  - `app/src/main/java/com/aftglw/devapi/ui/WeChatApp.kt`、`MainActivity.kt`
  - `app/src/main/java/com/aftglw/devapi/feature/discover/DiscoverScreen.kt`
  - `app/src/main/java/com/aftglw/devapi/util/ImageUtil.kt`
  - `app/src/main/java/com/aftglw/devapi/util/ProactiveScheduler.kt`
  - 测试：`app/src/test/java/com/aftglw/devapi/` 下相关测试需同步更新

## ADDED Requirements

### Requirement: 异步 IO 边界
所有 Room 数据库访问、网络调用、文件 IO、模型加载**必须**在 `Dispatchers.IO` 上执行，**禁止**在主线程或 Composable composition 期间同步阻塞。

#### Scenario: 进入聊天页不卡顿
- **WHEN** 用户从会话列表点击进入聊天页
- **THEN** 首帧在 100ms 内渲染（即使历史消息尚未加载完成）
- **AND** 历史消息加载完成后通过 ` mutableStateListOf` 增量追加，不重建整个列表

#### Scenario: 发送消息不卡顿
- **WHEN** 用户点击发送按钮
- **THEN** 用户消息气泡立即显示（同步写入 `mutableStateListOf`）
- **AND** 数据库持久化在 `viewModelScope.launch(Dispatchers.IO)` 中异步完成
- **AND** 持久化失败时显示 Toast 但不丢失 UI 消息

#### Scenario: 数据库迁移不阻塞启动
- **WHEN** 首次启动且检测到旧数据需要迁移
- **THEN** 启动期间显示"正在升级数据库"占位 UI
- **AND** 迁移在 `Application.onCreate` 的 IO 协程中执行
- **AND** 迁移期间用户可看到骨架屏，不出现 ANR

### Requirement: 数据库批量查询
`ChatsViewModel` 加载会话列表时**必须**用 JOIN 一次取所有会话的最新消息，**禁止**对每个会话单独查询。

#### Scenario: 会话列表加载
- **WHEN** `ChatsViewModel.loadChats()` 被调用
- **THEN** 仅执行 1 次 SQL 查询（`SELECT chats.*, last_msg.text FROM chats LEFT JOIN ... `）
- **AND** 100 个会话加载耗时 < 50ms

#### Scenario: 搜索会话
- **WHEN** 用户在搜索框输入
- **THEN** debounce 300ms 后执行 1 次 `SELECT DISTINCT chat_id FROM messages WHERE text LIKE ?` 查询
- **AND** 搜索期间不阻塞输入框

### Requirement: Compose 列表稳定性
`LazyColumn` 的 `key` 参数**必须**使用稳定且唯一的业务 id，**禁止**使用列表 index。

#### Scenario: 列表头部插入
- **WHEN** 在列表头部插入新消息（如系统提示）
- **THEN** 已存在的 item 不触发 recomposition
- **AND** `animateFloatAsState` 不重新启动

#### Scenario: 群聊接话链
- **WHEN** 群聊 AI 接话链连续追加 6 条消息
- **THEN** 仅新增的 6 个 item 被组合，已有 item 不重组
- **AND** `messages` 用 `mutableStateListOf` 而非 `mutableStateOf(emptyList())` + `+`

### Requirement: Bitmap 异步解码
所有 Bitmap 解码**必须**在 `Dispatchers.IO` 上执行，**且**根据目标显示尺寸计算 `inSampleSize` 下采样。

#### Scenario: 加载聊天背景
- **WHEN** 用户进入聊天页，背景图 URI 变化
- **THEN** 背景图在 `LaunchedEffect(uri) { withContext(IO) { ... } }` 中异步解码
- **AND** 解码前根据屏幕尺寸计算 `inSampleSize`，4K 图不下于原尺寸解码
- **AND** 解码完成前显示纯色背景占位

#### Scenario: 加载头像
- **WHEN** 列表渲染 60 条 AI 消息，每条都需要同一角色的头像
- **THEN** 头像仅解码 1 次（缓存命中 59 次）
- **AND** 缓存使用 `LruCache<String, Bitmap>` 或 Coil `AsyncImage`

### Requirement: 网络层 suspend 化
`OpenAiService` / `ClaudeAiService` / `MCPClient` / `MemoryStore.embed` 公共 API **必须**为 `suspend fun`，**禁止**用 `runBlocking` 包装。

#### Scenario: 流式响应取消
- **WHEN** 用户在 AI 流式响应期间离开页面
- **THEN** 协程 cancel 立即停止 SSE 读取
- **AND** 不再钉死 IO 线程

#### Scenario: 长 SSE 流 GC 压力
- **WHEN** DeepSeek thinking 模式输出 10000+ chunks
- **THEN** 每个 chunk 不重新构造 `JSONObject`，复用 `JsonReader`
- **AND** 内存峰值增长 < 20MB

### Requirement: native 模型生命周期
`OfflineRecognizer` / `LlamaEngine` 等 native 资源**必须**绑定到明确的生命周期，**禁止**依赖 `finalize()` 或 Activity 销毁后泄漏。

#### Scenario: 内存压力释放
- **WHEN** 系统发出 `onTrimMemory(TRIM_MEMORY_MODERATE)`
- **THEN** STT recognizer 的 native 资源被释放
- **AND** 下次使用时重新加载（带签名缓存判断）

#### Scenario: 应用退出
- **WHEN** 用户退出应用
- **THEN** `LlamaEngine.close()` 被显式调用释放 native 模型
- **AND** 不依赖 GC `finalize()`

### Requirement: 主题背景预渲染
`NewspaperBackground` / `WashiBackground` / `sumiBorder` 等 `drawBehind` 中的复杂绘制**必须**用 `Modifier.drawWithCache` 缓存为 `ImageBitmap` 或 `Path`，**禁止**每帧重绘。

#### Scenario: 列表滚动
- **WHEN** 用户在聊天列表中快速滚动
- **THEN** 每个 item 的背景从缓存读取，不重新绘制 800+ 圆
- **AND** 滚动帧率 ≥ 50fps

## MODIFIED Requirements

### Requirement: ToolRegistry 工具管理
`ToolRegistry` **增加** `unregister(name)` / `unregisterByOwner(predicate)` / `invalidateToolsCache()` 方法。MCP server 刷新时**必须**先 `unregisterByOwner { it is MCPToolProxy }` 清理旧工具，再注册新工具。

#### Scenario: MCP server 配置变更
- **WHEN** 用户在设置页修改 MCP server 配置并刷新
- **THEN** 旧 MCP 工具从 `ToolRegistry` 中移除
- **AND** AI 下次调用工具时不会看到 stale 工具
- **AND** 不会调用到已失效的 `MCPClient`

## REMOVED Requirements

### Requirement: 本地 GGUF 推理
**Reason**: `llama_wisp.cpp` 的 `nativeLoad`/`nativeGenerate` 是桩代码，从未真正接入 llama.cpp；`AiServiceFactory.kt:26-36` 已强制回退到 Mock/Cloud，`LocalAiService` 是死代码，维护成本高且误导用户（设置页有"本地模式"开关但实际不可用）。
**Migration**: 
- 删除 `LlamaEngine.kt`、`LocalAiService.kt`、`llama_wisp.cpp`、`CMakeLists.txt`（如有）
- `AiServiceFactory` 移除 `protocol == "local"` 分支
- DebugPage 的"本地模式"开关改为隐藏（已有项目硬约束：本地 AI 模式必须隐藏）
- 用户已有的本地 AI 设置项 gracefully 降级到云端（提示"本地模式已下线，已切换到云端"）

注：保留 `LlamaEngine` 的接口设计作为未来重新接入的参考，但不保留桩代码。
