# PRIVACY.md

## 数据收集

Wisp **不会收集任何用户数据。**

## 数据存储

所有用户数据存储在本地设备上：

| 数据类型 | 存储方式 | 存储位置 |
|---|---|---|
| 聊天记录 | SharedPreferences | 本地设备 |
| 长期记忆向量 | SQLite | 本地设备 |
| 角色人设 | SharedPreferences | 本地设备 |
| 好感度 | SharedPreferences | 本地设备 |
| 情绪模型 | APK Assets | 本地设备 |

## 网络请求

Wisp 仅在与 AI 模型通信时发送网络请求：

| 请求目标 | 用途 | 发送的数据 |
|---|---|---|
| DeepSeek API | AI 对话回复、RAG 嵌入、记忆提取 | 聊天内容（无身份标识） |
| pool.ntp.org | NTP 时间同步 | 无 |

## 第三方服务

Wisp 使用以下第三方服务，均通过用户自行配置的 API Key 直连：

- **DeepSeek API** — AI 对话与嵌入向量生成
- **NTP (pool.ntp.org)** — 时间校准

## 数据删除

卸载应用或清除应用数据会删除所有本地存储的数据。Wisp 没有云端服务器，不存在远程删除的需求。

---

最后更新: 2026 年 6 月
