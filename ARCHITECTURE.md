# Wisp 架构

## 三层结构

```
┌─────────────────────────────────────┐
│         展示层（UI）                   │
│  ChatScreen / ChatsScreen / WeChatApp│
│  Compose + Material3                 │
└─────────────────┬───────────────────┘
                  │ 用户输入 / 回复渲染
┌─────────────────▼───────────────────┐
│         逻辑层（Engine）              │
│                                      │
│  MoodDetector → 情绪检测 + 好感度更新  │
│       ↓                             │
│  MemoryStore → RAG 检索 + 时间衰减    │
│       ↓                             │
│  Prompt 组装 → enhancedPersona       │
│       ↓                             │
│  AiServiceFactory → 协议分发          │
│       ↓                             │
│  Post-LLM → 记忆存储 + 对话反思       │
│                                      │
│  独立模块:                            │
│    ProactiveScheduler → 主动关怀      │
│    ChatHistory → 持久化              │
│    TimeService → NTP + 时区          │
└─────────────────┬───────────────────┘
                  │ SQLite / SharedPrefs
┌─────────────────▼───────────────────┐
│         数据层（Storage）             │
│                                      │
│  MemoryStore (SQLite)                │
│    ├─ turn:xxx  — 当前对话上下文      │
│    ├─ chat:xxx  — 长期聊天记忆       │
│    ├─ diary:xxx — 日记归档           │
│    ├─ insight:xxx — 对话反思         │
│    ├─ starred:xxx — 收藏             │
│    └─ fact:xxx  — 关键事实           │
│                                      │
│  ChatHistory (SharedPreferences)     │
│    └─ 最近 ~40 条对话记录             │
│                                      │
│  wechat_settings (SharedPreferences) │
│    └─ 配置、好感度、人设等            │
└─────────────────────────────────────┘
```

## 一轮对话的完整流程

```
① 用户输入 "今天好累"
    ↓
② MoodDetector.feed()
    ├─ ONNX 本地模型 → 置信度 ≥60% → 直接出结果
    └─ 置信度 <60% → API 兜底 → 出结果
    ↓
③ MemoryStore.search() → RAG 检索相关记忆
    ├─ 时间衰减权重（今天=1.0, 3天=0.7, 一周=0.4...）
    └─ 情绪加权（悲伤时优先检索安慰相关记忆）
    ↓
④ enhancedPersona 组装
    人设 + 好感度 + 时间 + 日记 + 记忆 + 格式指令 + 情绪提示
    ↓
⑤ AiServiceFactory.getService().sendMessage()
    ├─ URL 含 claude/anthropic → ClaudeAiService
    └─ 其他 → OpenAiService（包括 DeepSeek/OpenAI/中转）
    ↓
⑥ 返回回复 → UI 显示
    ↓
⑦ Post-LLM（异步）
    ├─ MemoryStore.save(text, "turn:$name") — 存用户输入
    ├─ 对话反思（如果开启）→ 分析对话核心 → 存 insight
    └─ ChatHistory.save() — 存完整对话
```

## 独立系统

### 主动关怀（ProactiveScheduler）
```
AlarmManager 定时触发 → AI 生成问候 → 通知栏提醒
撤回机制：用户回复"别发了" → 自动降频
```

### 自动归档
```
bubbles > 60 条 → 压缩最旧 20 条 → 调用 AI 生成摘要 → 存 diary
```

### 记忆提取（每 10 轮一次）
```
提取事实 → chat 记忆
提取偏好 → 人设浓缩
```

### 对话优化（每 20 轮一次，需开启）
```
分析用户说话特点 → 存为用户画像
```

## 关键设计决策

| 决策 | 原因 |
|---|---|
| 情绪模型用 ONNX 而非 API | 本地 50ms，不依赖网络，隐私 |
| RAG 用余弦相似度而非 LLM 重排序 | 手机端够用，省 token |
| 好感度用 SharedPreferences 而非 SQLite | 高频读写，SharedPrefs 更快 |
| 对话反思默认关闭 | 每次回复多一次 API 调用，默认省 |
| 日记用文本存而非结构化 | 直接注入 prompt，AI 更易理解 |
