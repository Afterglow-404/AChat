# AChat 工具系统

## 概述

AChat 工具系统允许 AI 在对话中调用预定义的函数，执行本地操作后返回结果。
工具调用以 `【tool:工具名 参数】` 格式嵌入 AI 回复中，由前端解析执行。

## 架构

```
AI 回复 → 检测 【tool:xxx】 → 解析工具名 + 参数
  → ToolRegistry 查找工具
  → 执行 AiTool.execute()
  → 结果以系统消息气泡展示
```

## 接口定义

```kotlin
interface AiTool {
    // 工具名称，用于 AI 调用时识别
    val name: String
    
    // 工具描述，注入 prompt 让 AI 知道什么场景用
    val description: String
    
    // 执行逻辑，返回结果字符串
    suspend fun execute(ctx: Context, args: Map<String, String>): String
}
```

## 编写一个新工具

### 步骤

**① 新建文件：**`tools/YourTool.kt`

**② 实现接口：**

```kotlin
package com.aftglw.devapi.tools

import android.content.Context

class YourTool : AiTool {
    override val name = "your_tool"
    override val description = "你的工具能做什么，参数是什么"

    override suspend fun execute(ctx: Context, args: Map<String, String>): String {
        // args 包含用户/AI 传来的参数
        // 执行你的逻辑
        return "执行结果"
    }
}
```

**③ 注册工具：**在 `ToolRegistry.init()` 中添加一行：

```kotlin
register(YourTool())
```

**④ 工具自动生效：** AI 会在需要时调用，不需要改其他代码。

## 调用格式

AI 在回复中嵌入：

```
【tool:工具名 参数1=值1 参数2=值2】
```

示例：

```
让我查查【tool:time】
已记住【tool:note text=明天开会】
搜索一下【tool:recall q=上周的笔记】
```

## 参数解析规则

- 参数格式：`key=value`，空格分隔
- 多个参数：`【tool:xxx key1=val1 key2=val2】`
- 无参数：`【tool:xxx】`
- value 中不能包含空格（简单解析器限制）

## 内置工具参考

### time

获取当前精确日期时间。

```kotlin
// 调用： 【tool:time】
// 返回： "现在是2026年7月5日 14:32:21 星期二"
```

### note

记一条笔记，存入 MemoryStore(topic="note")。

```kotlin
// 调用： 【tool:note text=明天交报告】
// 返回： "已记住：明天交报告"
```

### recall

搜索已有的笔记和记忆。

```kotlin
// 调用： 【tool:recall q=报告】
// 返回： "明天交报告"
```

### send_message

主动给用户发一条通知栏消息。

```kotlin
// 调用： 【tool:send_message text=该睡觉了 chat=洛茜】
// 返回： "消息已发送：该睡觉了"
```

## 注意事项

```
① 工具名使用英文小写 + 下划线，不要用中文
② description 要写清楚工具在什么场景下使用（AI 据此判断是否调用）
③ execute 是 suspend 函数，可以调 API、读写数据库
④ 工具调用结果不要超过 100 字
⑤ 不需要的工具不要在 Registry 中注册
⑥ 工具只在 test-field 分支开发，不合并到 main
```
