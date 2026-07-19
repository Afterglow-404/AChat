# Wisp Desktop Debug Server

This is a standalone Node.js debugger and fake AI API for Wisp. It does not start the Android app and does not modify its database or UI.

## Start

Open PowerShell in the `WeChatClone` directory:

```powershell
node scripts/wisp-dev-server.mjs
```

Open a second terminal:

```powershell
node scripts/wisp-dev-cli.mjs
```

The server binds to `127.0.0.1:17890`. Override it with `WISP_DEBUG_HOST` and `WISP_DEBUG_PORT`.
The CLI is a direct-input chat terminal: type text and press Enter. It does not use slash commands. Press `Ctrl+C` or send EOF to exit.

Optional CLI modes:

```powershell
$env:WISP_CLI_SCENARIO = 'sticker' # echo, sticker, tool, tool:<name>, empty
$env:WISP_CLI_STREAM = '1'         # print SSE output as it arrives
$env:WISP_CLI_TTS = '1'            # save each assistant reply as a WAV file
$env:WISP_CLI_TTS_VOICE = 'default'
node scripts/wisp-dev-cli.mjs
```

## Phone AI API

Point Wisp's OpenAI-compatible AI endpoint at:

```text
http://<computer-ip>:17890/v1
```

The server implements `POST /v1/chat/completions` with both JSON and SSE streaming responses. Select a deterministic test response with `X-Wisp-Scenario`:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:17890/v1/chat/completions `
  -Headers @{ 'X-Wisp-Scenario' = 'sticker' } `
  -ContentType 'application/json' `
  -Body '{"model":"wisp-debug","messages":[{"role":"user","content":"test"}]}'
```

Supported scenarios:

- `echo`: returns a deterministic text reply.
- `sticker`: returns a real Wisp sticker marker such as `【sticker:呆猫八条:开心】`.
- `tool` or `tool:<name>`: returns an OpenAI-compatible `tool_calls` response.
- `empty`: returns an empty assistant message.

The default scenario is controlled by `WISP_AI_SCENARIO`. A fixed tool can be selected with `WISP_AI_TOOL=send_message`. Set `WISP_API_KEY` to require `Authorization: Bearer <key>` on API routes.

## Phone TTS API

Configure the real GPT-SoVITS API endpoint and start the server:

```powershell
$env:WISP_DEBUG_HOST = '0.0.0.0'
$env:WISP_GPT_SOVITS_URL = 'http://127.0.0.1:9880'
node scripts/wisp-dev-server.mjs
```

Point Wisp's cloud/OpenAI TTS endpoint at:

```text
http://<computer-ip>:17890/v1/audio/speech
```

The proxy accepts `{ "model": "gpt-sovits", "input": "你好", "voice": "default", "response_format": "wav" }` and forwards a GPT-SoVITS-compatible `POST /tts` request. Optional forwarding settings include `WISP_GSV_REF_AUDIO_PATH`, `WISP_GSV_PROMPT_TEXT`, `WISP_GSV_PROMPT_LANG`, `WISP_GSV_TEXT_LANG`, and `WISP_GSV_MEDIA_TYPE`.

`send_message`, built-in device tools, and sticker calls are simulated. Sticker calls still resolve the real Wisp asset and produce the exact `【sticker:pack:tag】` marker. HTTP-backed `.wsptool` entries execute their configured request unless `WISP_DEBUG_NO_NETWORK=1` is set. Shell, script, and Kotlin implementations are reported as unsupported by the Node debugger.

The server also exposes `POST /mcp`, so the Wisp app can use `http://<computer-ip>:17890/mcp` as an MCP server after the computer is reachable from the phone. The Android MCP client accepts the standard `result.tools` response.
