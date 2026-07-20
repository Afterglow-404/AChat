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

CLI 默认通过 GPT-SoVITS 代理试听。测试 Qwen3-TTS 时可以改用：

```powershell
$env:WISP_CLI_TTS = '1'
$env:WISP_CLI_TTS_ENGINE = 'qwen3'
$env:WISP_CLI_TTS_VOICE = 'Vivian'
$env:WISP_CLI_TTS_LANGUAGE = 'Chinese'
$env:WISP_CLI_TTS_INSTRUCT = '温柔、亲切地说'
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

### Qwen3-TTS

Qwen3-TTS 由电脑端 GPU 服务运行，Wisp 负责把手机请求转发给它。建议让手机连接 Wisp 地址，而不是直接连接模型进程：

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_QWEN3_TTS_URL = 'http://127.0.0.1:8000'
node scripts/wisp-dev-server.mjs
```

`WISP_QWEN3_TTS_URL` 是电脑端 Qwen3-TTS 适配服务地址。上游服务需要提供：

- `GET /healthz`：适配服务存活时返回 2xx，并通过 `ready` 表示模型是否已经加载。
- `GET /speakers`：返回 `["Vivian", "Ryan"]`，也可以返回 `{ "speakers": [...] }`。
- `POST /tts`：接收 JSON 并返回 WAV 或其他音频二进制数据。

请求格式：

```json
{
  "text": "你好，这是 Wisp 的语音测试。",
  "language": "Chinese",
  "speaker": "Vivian",
  "instruct": "温柔、亲切地说",
  "response_format": "wav"
}
```

手机端 TTS 设置选择“PC Qwen3-TTS”，服务地址填写 Wisp 根地址：

```text
http://<computer-ip>:17890
```

角色详情页可以分别设置音色、语言和语气指令；留空时继承全局设置。当前支持中文、英语、日语、韩语、德语、法语、俄语、葡萄牙语、西班牙语、意大利语和自动识别。Qwen3-TTS 不可用时，Wisp 会按 `Qwen3-TTS → GPT-SoVITS → 云端 TTS → 系统 TTS` 自动降级。

Wisp 代理接口为 `GET /qwen3/healthz`、`GET /qwen3/speakers` 和 `POST /qwen3/tts`。可以这样验证：

```powershell
Invoke-WebRequest -Uri http://127.0.0.1:17890/qwen3/healthz
Invoke-RestMethod -Uri http://127.0.0.1:17890/qwen3/speakers
Invoke-WebRequest -Method Post -Uri http://127.0.0.1:17890/qwen3/tts `
  -ContentType 'application/json' `
  -Body '{"text":"你好","language":"Chinese","speaker":"Vivian","response_format":"wav"}' `
  -OutFile .\qwen3-test.wav
```

Wisp 不内置 Qwen3-TTS 模型权重和 Python 推理环境。电脑端适配服务可以使用 Qwen3-TTS 官方仓库的 `CustomVoice` 推理代码，把模型调用封装成上述 HTTP 协议。

本项目提供了一个最小适配服务脚本。先在电脑端创建 Python 环境并安装依赖：

```powershell
python -m venv .venv-qwen3
.\.venv-qwen3\Scripts\Activate.ps1
python -m pip install -r scripts\requirements-qwen3-tts.txt
```

然后启动：

```powershell
$env:QWEN3_TTS_MODEL = 'Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice'
$env:QWEN3_TTS_DEVICE = 'cuda:0'
$env:QWEN3_TTS_SPEAKER = 'Vivian'
python scripts\qwen3_tts_server.py
```

模型默认在第一次 `/tts` 请求时加载；如果希望启动时加载，可设置 `$env:QWEN3_TTS_LOAD_ON_START = '1'`。模型下载、显存占用和 CUDA/PyTorch 版本由电脑端环境负责，Wisp Android 端不需要安装这些依赖。

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

### Dashboard 浏览器控制面板

交互模式也可以通过浏览器 Dashboard 操作，无需 CLI：

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_INTERACTIVE = '1'
node scripts/wisp-dev-server.mjs
```

浏览器打开 `http://<computer-ip>:17890/dashboard`：

- 左侧列表实时显示手机发来的待回复请求
- 点击请求可输入回复文字、选择贴纸，Ctrl+Enter 发送
- 右侧面板提供快捷回复、TTS 试听、语音收件箱
- 多个 Dashboard 客户端可同时连接，会话状态实时同步

Dashboard 通过 WebSocket (`/dashboard/ws`) 与服务器通信，协议：

```
Server → Dashboard:  { type: "session.new", session: {...} }
Server → Dashboard:  { type: "session.completed", requestId }
Server → Dashboard:  { type: "voice.new", voice: {...} }
Server → Dashboard:  { type: "init", sessions, voices, stickers, tools }
Dashboard → Server:  { type: "reply.start", requestId }
Dashboard → Server:  { type: "reply.send", requestId, content }
Dashboard → Server:  { type: "reply.cancel", requestId }
Dashboard → Server:  { type: "tts.test", engine, text, voice }
```
