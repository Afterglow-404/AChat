<p align="center">
  <img src="docs/logo.png" width="128" alt="Wisp Logo"/>
</p>
<p align="center"><strong>Wisp</strong><br/>一个 AI 聊天应用</p>

> 代码由 AI 生成，功能设计参考了类似项目。

> 代码可读性一般，欢迎指指点点。

一个 Android 端的 AI 聊天应用。支持情绪感知、好感度系统、RAG 记忆、主动关怀等功能。

## 截图

<p align="center">
  <img src="screenshots/ChatsScreen.jpg" width="200" alt="对话列表"/>
  <img src="screenshots/DiscoverScreen.jpg" width="200" alt="发现页面"/>
  <img src="screenshots/MeScreen.jpg" width="200" alt="我的页面"/>
</p>

## 功能

- **情绪感知** — 本地 7 类情绪模型检测用户对话情绪
- **好感度系统** — 7 级关系，好感度影响 AI 回复
- **主动关怀** — AI 闲时主动问候，支持多种触发条件
- **情绪可视化** — 用户消息旁显示情绪表情
- **时间感知** — 联网校准
- **导入 Skill** — 支持 ex-skill 格式导入角色人设，未来支持酒馆角色卡

## 注意

Wisp 使用了 kyant0 的 AndroidLiquidGlass 库，建议使用 **Android 12 以上**。

## 我要玩！

### 1. 下载

从 Releases 页面下载最新 APK。

### 2. 配置 API

进入设置 → AI 接口，填写以下信息：

| 字段 |  |
|---|---|
| API 地址 | `https://api.cat.com/v1` |
| API Key | 你的 API 密钥 |
| 模型名 | 名字 |

DeepSeek API 可在 [这里](https://platform.deepseek.com) 获取。

ChatGPT API 在 [那里](https://platform.openai.com/api-keys)。

Claude API 在 [哪里](https://console.anthropic.com/)。



### 3. 开始聊天

创建一个对话角色，即可开始聊天。

## 许可证

基于 Apache-2.0 开源：
- ✅ 你可以自由使用、修改、分发
- ✅ 可以用于个人或商业项目
- ✅ 修改后可以闭源发布
- ❗ 需保留原始版权声明和免责声明

## 致谢

- [Reasonix](https://github.com/esengine/deepseek-reasonix) — 给我省了很多钱 :)
- [EmotionTalk](https://github.com/NKU-HLT/EmotionTalk) — 情绪模型训练数据
- [ex-skill](https://github.com/perkfly/ex-skill) — 角色导入格式参考
- [LingChat](https://github.com/SlimeBoyOwO/LingChat) — 设计思路参考
- [Operit](https://github.com/AAswordman/Operit) — 架构与功能参考
- [SillyTavern](https://github.com/SillyTavern/SillyTavern) — 角色卡/世界书功能参考
- [LiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃效果