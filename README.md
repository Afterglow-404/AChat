# AChat

> 代码由 AI 辅助生成，功能设计来自个人经历。
> 代码可读性一般，架构仍在迭代中。

一个 Android 端的 AI 陪伴聊天应用。基于 JetBrains Compose Multiplatform 构建，支持情绪感知、好感度系统、RAG 记忆、主动关怀等功能。

## 截图

<p align="center">
  <img src="docs/logo.png" width="128" alt="AChat Logo"/>
</p>
<p align="center"><strong>AChat</strong><br/>一个 Android 端的 AI 陪伴聊天应用</p>

## 功能

- **情绪感知** — 本地 ONNX 7 类情绪模型实时检测对话情绪
- **好感度系统** — 7 级关系，好感度动态影响 AI 语气
- **主动关怀** — AI 闲时主动问候，支持多种触发条件
- **RAG 长期记忆** — DeepSeek embedding + SQLite 向量检索
- **情绪可视化** — 消息旁显示情绪表情
- **时间感知** — NTP 校准 + 时区选择
- **导入 Skill** — 支持 ex-skill 格式导入角色人设

## 快速开始

### 1. 下载

从 Releases 页面下载最新 APK。

### 2. 配置 API

进入设置 → AI 接口，填写以下信息：

| 字段 | 推荐值 |
|---|---|
| API 地址 | `https://api.deepseek.com/v1` |
| API Key | 你的 DeepSeek API 密钥 |
| 模型名 | `deepseek-chat` 或 `deepseek-reasoner` |

DeepSeek API 密钥可在 [platform.deepseek.com](https://platform.deepseek.com) 获取。

### 3. 开始聊天

创建一个对话角色，即可开始聊天。

## 技术栈

- **UI**: JetBrains Compose Multiplatform + Material3 + LiquidGlass
- **情绪模型**: ONNX Runtime (chinese-roberta-wwm-ext, 7 类)
- **RAG**: DeepSeek text-embedding-v2 + SQLite + 余弦检索
- **AI 对话**: DeepSeek V4-Flash API (OpenAI 兼容协议)
- **主动关怀**: AlarmManager + BroadcastReceiver
- **数据存储**: SharedPreferences + SQLite
- **构建**: Gradle 9.4.1, compileSdk 37, minSdk 30

## 隐私说明

- 聊天记录存储在本地 SharedPreferences，不上传任何服务器
- 情绪分析在本地 ONNX 模型完成，数据不出手机
- API 调用直接连接 DeepSeek 服务端，不经过第三方中转
- 应用不收集任何用户数据

## 许可证

Apache-2.0 License

## 致谢

- [EmotionTalk](https://github.com/NKU-HLT/EmotionTalk) — 情绪模型训练数据
- [ex-skill](https://github.com/perkfly/ex-skill) — 角色导入格式参考
- [LingChat](https://github.com/SlimeBoyOwO/LingChat) — 设计思路参考
- [Operit](https://github.com/AAswordman/Operit) — 架构与功能参考
- [SillyTavern](https://github.com/SillyTavern/SillyTavern) — 角色卡/世界书功能参考
