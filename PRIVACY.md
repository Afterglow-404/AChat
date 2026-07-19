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

Wisp 仅在与 AI 模型通信时发送网络请求。**请求目标由用户自行配置决定**，支持的协议包括：

- **OpenAI 兼容 API**（DeepSeek / OpenAI / OpenRouter / 自定义端点）
- **Anthropic Claude API**
- **本地模型**（llama.cpp / GGUF，完全离线）

| 请求目标 | 用途 | 发送的数据 |
|---|---|---|
| 用户配置的 API 端点 | AI 对话回复、RAG 嵌入、记忆提取 | 聊天内容（无身份标识） |
| pool.ntp.org | NTP 时间同步 | 无 |

## 第三方服务

Wisp 使用以下第三方服务，均通过用户自行配置的 API Key 直连：

- **用户指定的 AI API 提供商** — AI 对话与嵌入向量生成
- **NTP (pool.ntp.org)** — 时间校准

## 权限说明

| 权限 | 用途 |
|---|---|
| `ACCESS_FINE_LOCATION` | 地理位置查询工具（用户主动调用时申请） |
| `PACKAGE_USAGE_STATS` | 应用使用统计查询工具 |
| `SYSTEM_ALERT_WINDOW` | Debug 悬浮窗 |

上述权限均为**可选功能**调用时按需使用，不会在后台静默收集。

## 数据删除

卸载应用或清除应用数据会删除所有本地存储的数据。Wisp 没有云端服务器，不存在远程删除的需求。

---

最后更新: 2026 年 7 月
