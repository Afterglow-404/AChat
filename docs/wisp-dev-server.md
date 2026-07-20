# Wisp 桌面调试服务器

这是一个为 Wisp 提供的独立 Node.js 调试器和伪 AI API 服务。它不会启动 Android 应用，也不会修改应用的数据库或界面。

## 启动

在 `WeChatClone` 目录中打开 PowerShell：

```powershell
node scripts/wisp-dev-server.mjs
```

然后打开第二个终端：

```powershell
node scripts/wisp-dev-cli.mjs
```

服务默认监听 `127.0.0.1:17890`。可以通过 `WISP_DEBUG_HOST` 和 `WISP_DEBUG_PORT` 覆盖默认地址和端口。
CLI 是一个直接输入内容的聊天终端：输入文字后按 Enter 发送，不使用斜杠命令。按 `Ctrl+C` 或发送 EOF 即可退出。

CLI 可选模式：

```powershell
$env:WISP_CLI_SCENARIO = 'sticker' # echo, sticker, tool, tool:<name>, empty
$env:WISP_CLI_STREAM = '1'         # print SSE output as it arrives
$env:WISP_CLI_TTS = '1'            # save each assistant reply as a WAV file
$env:WISP_CLI_TTS_VOICE = 'default'
node scripts/wisp-dev-cli.mjs
```

## 手机端 AI API

将 Wisp 的 OpenAI 兼容 AI 接口地址设置为：

```text
http://<computer-ip>:17890/v1
```

服务实现了 `POST /v1/chat/completions`，同时支持 JSON 响应和 SSE 流式响应。可以通过 `X-Wisp-Scenario` 选择确定性的测试响应：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:17890/v1/chat/completions `
  -Headers @{ 'X-Wisp-Scenario' = 'sticker' } `
  -ContentType 'application/json' `
  -Body '{"model":"wisp-debug","messages":[{"role":"user","content":"test"}]}'
```

支持的场景：

- `echo`：返回确定性的文本回复。
- `sticker`：返回真实的 Wisp 表情标记，例如 `【sticker:呆猫八条:开心】`。
- `tool` 或 `tool:<name>`：返回 OpenAI 兼容的 `tool_calls` 响应。
- `empty`：返回空的 assistant 消息。

默认场景由 `WISP_AI_SCENARIO` 控制。可以通过 `WISP_AI_TOOL=send_message` 选择固定工具。设置 `WISP_API_KEY` 后，API 路由会要求携带 `Authorization: Bearer <key>`。

## 手机端 TTS API

配置真实的 GPT-SoVITS API 地址，然后启动服务：

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_GPT_SOVITS_URL = 'http://127.0.0.1:9880'
node scripts/wisp-dev-server.mjs
```

手机端若使用 GPT-SoVITS 引擎，应将 `tts_gptsovits_url` 设置为 fake server 的根地址：

```text
http://<computer-ip>:17890
```

fake server 会兼容手机端需要的 `GET /healthz`、`GET /speakers` 和 `POST /tts`，并将 `/tts` 请求转发到 `WISP_GPT_SOVITS_URL`。因此 `WISP_GPT_SOVITS_URL` 应填写电脑上 GPT-SoVITS 适配服务的地址，例如 `http://127.0.0.1:9880`。如果手机直接连接 GPT-SoVITS 适配服务，也必须确保服务监听局域网地址，并提供这些兼容接口。

将 Wisp 的云端/OpenAI TTS 接口地址设置为：

```text
http://<computer-ip>:17890/v1/audio/speech
```

代理接受 `{ "model": "gpt-sovits", "input": "你好", "voice": "default", "response_format": "wav" }`，并转发为兼容 GPT-SoVITS 的 `POST /tts` 请求。默认 `prompt_lang` 和 `text_lang` 都是 `zh`，可通过请求字段或环境变量 `WISP_GSV_PROMPT_LANG`、`WISP_GSV_TEXT_LANG` 覆盖。其他可选的转发设置包括 `WISP_GSV_REF_AUDIO_PATH`、`WISP_GSV_PROMPT_TEXT` 和 `WISP_GSV_MEDIA_TYPE`。

手机端 GPT-SoVITS 支持按角色选择 `text_lang`：全局默认语言可在 TTS 设置中配置，角色详情页可以选择“继承全局默认”、中文 `zh`、英语 `en`、日语 `ja`、韩语 `ko` 或粤语 `yue`。角色设置优先于全局设置；系统 TTS 和云端 OpenAI TTS 不使用此语言参数。

`send_message`、内置设备工具和表情调用均为模拟执行。表情调用仍会解析真实的 Wisp 资源，并生成准确的 `【sticker:pack:tag】` 标记。基于 HTTP 的 `.wsptool` 条目会执行其配置的请求，除非设置了 `WISP_DEBUG_NO_NETWORK=1`。Shell、脚本和 Kotlin 实现会被 Node 调试器报告为不支持。

服务还提供 `POST /mcp` 接口。电脑能够被手机访问后，Wisp 应用即可使用 `http://<computer-ip>:17890/mcp` 作为 MCP 服务地址。Android MCP 客户端接受标准的 `result.tools` 响应。

## 手机端 STT API

服务实现了 OpenAI 兼容的 `POST /v1/audio/transcriptions`，可以接收手机端云端 STT 发来的 `multipart/form-data` 音频。音频默认保存到项目目录下的 `wisp-voice-inbox`，也可以通过 `WISP_VOICE_DIR` 指定目录。请求体默认最多 16 MiB，可通过 `WISP_MAX_BODY_BYTES` 调整。

为了调试链路，可以指定固定的转写结果：

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_STT_TEXT = '电脑端收到了一条测试语音'
node scripts/wisp-dev-server.mjs
```

在交互式 CLI 中，新的语音请求会自动打印文件名、大小、转写文本和保存路径；也可以输入：

```text
voice list
```

查看已经接收的语音记录。手机端云端 STT 配置为 fake API 的 `/v1` 地址后，录音会先到达此接口并返回 `{ "text": "..." }`，随后手机再把转写文本发送给聊天 API。

## 交互式回复模式

当手机向电脑发送普通聊天请求，而电脑端操作员需要手动组织 assistant 回复时，使用此模式：

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_INTERACTIVE = '1'
$env:WISP_INTERACTIVE_TIMEOUT_MS = '180000'
node scripts/wisp-dev-server.mjs
```

在第二个终端中：

```powershell
$env:WISP_CLI_INTERACTIVE = '1'
node scripts/wisp-dev-cli.mjs
```

手机请求到达后，按以下顺序在 CLI 中输入命令：

```text
reply start
你好，这里是人工客服。
sticker list
sticker 开心
tool time {}
reply end
```

`sticker list` 会列出服务端已加载的表情包及其标签，不需要先执行 `reply start`。文本行和表情标记会合并为一条 assistant 消息。`reply end` 会释放等待中的 OpenAI 兼容请求，让手机收到最终的普通聊天响应，并将其渲染为 assistant 消息气泡。`reply_cancel` 会向手机返回取消错误。交互式模式需要主动启用，不会改变默认的 echo 行为。
