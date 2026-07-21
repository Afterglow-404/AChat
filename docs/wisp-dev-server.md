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

## Qwen3-TTS 官方模式与音色克隆

Wisp 的 Qwen3-TTS 适配器对应官方 `qwen-tts` 的三种调用：

- `CustomVoice`：使用官方 Speaker，例如 `Vivian`，调用 `generate_custom_voice`。
- `Base voice clone`：使用参考音频和参考文本，调用 `generate_voice_clone`。
- `VoiceDesign`：使用自然语言描述设计音色，调用 `generate_voice_design`。

Base 模型可使用官方的 `0.6B` 或 `1.7B` 权重，例如：

```powershell
$env:QWEN3_TTS_MODEL = 'Qwen/Qwen3-TTS-12Hz-0.6B-Base'
$env:QWEN3_TTS_MODE = 'base'
$env:QWEN3_TTS_REF_AUDIO = 'H:\voices\reference.wav'
$env:QWEN3_TTS_REF_TEXT = '这是参考音频中的逐字稿。'
$env:QWEN3_TTS_X_VECTOR_ONLY = '0'
python scripts\qwen3_tts_server.py
```

JSON TTS 请求可以直接传递 `mode`、`ref_audio`、`ref_text` 和 `x_vector_only_mode`；Wisp 也提供 `POST /qwen3/clone`，接收 multipart 字段 `file`、`text`、`language`、`ref_text` 和 `x_vector_only_mode`。桌面端的 Voice Lab 会保存这些字段，并在保存配置时重启 Wisp 子服务。

### 工具实时预览与语音克隆工作台

桌面端的“工具”页现在是实时工具监控页，不再复用人工回复界面。它会展示 Wisp 当前加载的全部工具、参数、最近一次返回值和耗时；点击“刷新全部”会调用只读工具，写入类工具（例如 `note`、`send_message`）不会被自动触发。

语音引擎页新增“语音克隆工作台”：

- GPT-SoVITS 角色可保存参考音频路径、参考文本、Prompt 语言、输出语言和音色。
- Qwen3-TTS 角色可保存 Speaker、语言和 Instruct。
- 角色预设保存在桌面端本地，点击“应用到默认配置”后会写入语音配置并重启 Wisp 语音桥。
- 当前 Qwen3-TTS 适配器使用 `CustomVoice`，因此面板不会把 Speaker/Instruct 冒充成 Base 模型的真正音频克隆；真正的参考音频克隆需要后续接入 Qwen3-TTS Base 模型接口。

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
$env:QWEN3_TTS_MODEL = 'H:\\qwen3-tts-models\\Qwen3-TTS-12Hz-0.6B-CustomVoice'
$env:QWEN3_TTS_DEVICE = 'cuda:0'
$env:QWEN3_TTS_DTYPE = 'float32'
$env:QWEN3_TTS_DO_SAMPLE = '1'
$env:QWEN3_TTS_SUBTALKER_DOSAMPLE = '1'
$env:QWEN3_TTS_MAX_NEW_TOKENS = '512'
python scripts\qwen3_tts_server.py
```

本机 RTX 2070 实测使用 0.6B 模型、CUDA、`float32`、采样开启和 512 个最大音频 token，短句约 2 到 4 秒返回，显存约 4.8GB。模型默认在第一次 `/tts` 请求时加载；启动脚本会设置 `$env:QWEN3_TTS_LOAD_ON_START = '1'` 预加载模型。模型下载、显存占用和 CUDA/PyTorch 版本由电脑端环境负责，Wisp Android 端不需要安装这些依赖。

本项目还提供了 `D:\deepseek-reasonix-SandBox\qwen3-tts-start.ps1`，它会固定使用 `H:\qwen3-tts-models` 中的模型和 CUDA 配置。启动成功后，Wisp 代理配置为 `$env:WISP_QWEN3_TTS_URL = 'http://127.0.0.1:8000'`，手机端则填写 Wisp 根地址 `http://<computer-ip>:17890`。

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

## Electron 桌面端

桌面端位于 `desktop` 目录。它会自动启动 Wisp 开发服务、打开交互式回复工作台，并提供消息回复、贴纸、工具、语音收件箱和 TTS 试听功能。

```powershell
cd WeChatClone\desktop
npm.cmd start
```

如果外接显卡导致 Electron 的 GPU 进程启动失败，使用兼容模式：

```powershell
npm.cmd run start:compat
```

切换到核显 780M 后，可以尝试完整沙箱模式：

```powershell
npm.cmd run start:secure
```

### 手机连接桌面端

桌面端默认只监听 `127.0.0.1`，仅供电脑本机使用。手机联调时，需要在启动前指定电脑的局域网 IPv4 地址：

```powershell
$env:WISP_DESKTOP_HOST = '192.168.31.12'
npm.cmd run start:compat
```

手机端的 fake AI API、STT 或 TTS 地址填写：

```text
http://192.168.31.12:<日志中显示的端口>
```

桌面端会优先尝试 `17890`；如果端口已占用，会自动选择后续空闲端口。重启服务时会等待旧进程退出，尽量保持原端口不变。手机和电脑必须连接同一局域网，并允许 Electron 通过 Windows 防火墙访问专用网络。

桌面端使用独立的 `preload.cjs`，启用 `contextIsolation`、关闭 `nodeIntegration`，并通过 IPC 暴露服务状态和重启方法。Electron 用户数据保存在 `%TEMP%\WispDesktop-v2`，不依赖浏览器密码或 Cookie。

### 桌面端语音引擎配置

进入桌面端顶部的“语音引擎”页面，在“语音引擎配置”区域填写上游服务。保存时会校验地址和超时，并自动重启 Wisp 子服务；不需要手动关闭桌面端。

- `GPT-SoVITS URL`：例如 `http://127.0.0.1:9880`，对应上游的 `/healthz`、`/speakers`、`/tts`。
- `Qwen3-TTS URL`：例如 `http://127.0.0.1:8000`，对应上游的 `/healthz`、`/speakers`、`/tts`。
- GPT-SoVITS 默认音色、参考音频、Prompt 文本、Prompt 语言、输出语言和音频格式会注入每次 `/tts` 请求。
- Qwen3-TTS 默认 Speaker、语言和 Instruct 会注入每次 `/tts` 请求。
- 两个引擎可以只配置一个；将地址留空即可禁用对应引擎。点击“恢复默认”会恢复启动环境变量中的值。
- 配置文件位置为 `%TEMP%\WispDesktop-v2\voice-config.json`。用户在桌面端保存的值优先于 `WISP_*` 环境变量，环境变量优先于内置默认值。

独立启动 `wisp-dev-server.mjs` 时仍使用环境变量配置，例如：

```powershell
$env:WISP_GPT_SOVITS_URL = 'http://127.0.0.1:9880'
$env:WISP_GSV_VOICE = 'default'
$env:WISP_GSV_REF_AUDIO_PATH = 'H:\voices\reference.wav'
$env:WISP_GSV_PROMPT_TEXT = '你好，这是参考音频。'
$env:WISP_GSV_PROMPT_LANG = 'zh'
$env:WISP_GSV_TEXT_LANG = 'zh'
$env:WISP_QWEN3_TTS_URL = 'http://127.0.0.1:8000'
$env:WISP_QWEN3_SPEAKER = 'Vivian'
$env:WISP_QWEN3_LANGUAGE = 'Chinese'
$env:WISP_TTS_TIMEOUT_MS = '120000'
$env:WISP_QWEN3_TTS_TIMEOUT_MS = '120000'
node scripts/wisp-dev-server.mjs
```
## AffectiveField Desktop telemetry

The Desktop relationship page reads development snapshots from the local Wisp server. The endpoint uses the same debug authorization as other `/api/v1/debug/*` routes and keeps data in memory only.

```text
GET  /api/v1/debug/affect
POST /api/v1/debug/affect/snapshot
POST /api/v1/debug/affect/clear
```

The snapshot payload may contain `chatName`, `eventId`, `affectiveField`, `rhythmProfile`, `stateHint`, `pendingEvents`, `closureCandidates`, and `responseAssessment`. Reusing an `eventId` for the same chat is idempotent. The server broadcasts `affect.updated` and `affect.cleared` through `/dashboard/ws`.

The Android DebugPage now provides an explicit `AffectiveField Desktop sync` switch. The user must enter a separate Desktop URL; it is never inferred from the AI API URL. Android accepts only `localhost`, private IPv4, or private IPv6 addresses on ports `17890-17909`. An optional bearer token can be supplied when the Desktop server is protected by `WISP_API_KEY`.

## Next development stages

1. P0 verification: replay 100 conversation samples and compare field updates, pending closure, and eventId deduplication.
2. P1 scheduler: read Desktop snapshots and implement a dry-run ProactiveScheduler with cooldown, quiet hours, and an approval log before sending any proactive message.
3. P1 response loop: record the latest ResponseAssessment and expose a per-turn timeline so missed questions and emotional replies can be inspected.
4. Group isolation: keep one relationship field per user-character pair and add a separate group atmosphere view.
5. Privacy controls: add export, delete, retention, and sync diagnostics without sending relationship data to cloud providers by default.
