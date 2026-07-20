import readline from 'node:readline'
import process from 'node:process'
import { writeFile } from 'node:fs/promises'

const baseUrl = (process.env.WISP_DEBUG_URL || 'http://127.0.0.1:17890').replace(/\/$/, '')
const apiKey = process.env.WISP_API_KEY || ''
const scenario = process.env.WISP_CLI_SCENARIO || ''
const stream = process.env.WISP_CLI_STREAM === '1' || process.env.WISP_CLI_STREAM === 'true'
const ttsEnabled = process.env.WISP_CLI_TTS === '1' || process.env.WISP_CLI_TTS === 'true'
const ttsEngine = (process.env.WISP_CLI_TTS_ENGINE || 'gpt_sovits').toLowerCase()
const ttsVoice = process.env.WISP_CLI_TTS_VOICE || 'default'
const ttsLanguage = process.env.WISP_CLI_TTS_LANGUAGE || 'Chinese'
const ttsInstruction = process.env.WISP_CLI_TTS_INSTRUCT || ''
const interactive = process.env.WISP_CLI_INTERACTIVE === '1' || process.env.WISP_CLI_INTERACTIVE === 'true'

function headers(extra = {}) {
  return {
    'content-type': 'application/json',
    ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {}),
    ...(scenario ? { 'x-wisp-scenario': scenario } : {}),
    ...extra,
  }
}

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, { headers: headers(options.headers), ...options })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.error?.message || JSON.stringify(body))
  return body
}

async function listStickers() {
  const body = await request('/api/v1/stickers')
  const packs = Array.isArray(body.packs) ? body.packs : []
  if (!packs.length) {
    console.log('[sticker] no sticker packs loaded')
    return
  }

  console.log(`[sticker] ${packs.length} pack(s) loaded`)
  for (const pack of packs) {
    const tags = Array.isArray(pack.tags) ? pack.tags : []
    console.log(`- ${pack.pack || '(unnamed)'}: ${tags.length ? tags.join(', ') : '(no tags)'}`)
  }
}

function voiceLabel(voice) {
  const name = voice.filename || voice.id || 'voice'
  const size = Number.isFinite(voice.size) ? ` (${voice.size} bytes)` : ''
  const text = voice.text ? `: ${voice.text}` : ''
  const stored = voice.storedPath ? ` -> ${voice.storedPath}` : ''
  return `${name}${size}${text}${stored}`
}

async function listVoices() {
  const body = await request('/api/v1/debug/voices')
  const voices = Array.isArray(body.voices) ? body.voices : []
  if (!voices.length) {
    console.log('[voice] no received voice messages')
    return
  }

  console.log(`[voice] ${voices.length} received voice message(s)`)
  for (const voice of voices) console.log(`- ${voiceLabel(voice)}`)
}

function toolCallsFrom(body) {
  return body?.choices?.[0]?.message?.tool_calls || []
}

function assistantText(body) {
  return body?.choices?.[0]?.message?.content || ''
}

async function requestStream(text) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ model: 'wisp-debug', messages: [{ role: 'user', content: text }], stream: true }),
  })
  if (!response.ok) throw new Error(await response.text())
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  const toolCalls = []
  let textOutput = ''

  while (true) {
    const { done, value } = await reader.read()
    pending += decoder.decode(value || new Uint8Array(), { stream: !done })
    const events = pending.split('\n\n')
    pending = events.pop() || ''
    for (const event of events) {
      const line = event.split('\n').find((item) => item.startsWith('data: '))
      if (!line) continue
      const data = line.slice(6)
      if (data === '[DONE]') continue
      const chunk = JSON.parse(data)
      const delta = chunk.choices?.[0]?.delta || {}
      if (delta.content) {
        textOutput += delta.content
        process.stdout.write(delta.content)
      }
      if (delta.tool_calls) toolCalls.push(...delta.tool_calls)
    }
    if (done) break
  }
  process.stdout.write('\n')
  return { text: textOutput, toolCalls }
}

async function synthesize(text) {
  const qwen3 = ttsEngine === 'qwen3' || ttsEngine === 'qwen3_tts'
  const response = await fetch(`${baseUrl}${qwen3 ? '/qwen3/tts' : '/v1/audio/speech'}`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(qwen3
      ? { text, language: ttsLanguage, speaker: ttsVoice === 'default' ? 'Vivian' : ttsVoice, instruct: ttsInstruction, response_format: 'wav' }
      : { model: 'gpt-sovits', input: text, voice: ttsVoice, response_format: 'wav' }),
  })
  const bytes = Buffer.from(await response.arrayBuffer())
  if (!response.ok) throw new Error(bytes.toString('utf8'))
  const output = `wisp-tts-${Date.now()}.wav`
  await writeFile(output, bytes)
  console.log(`[tts:${ttsEngine}] ${output} (${bytes.length} bytes)`)
}

async function ask(text) {
  if (stream) {
    const streamed = await requestStream(text)
    if (streamed.toolCalls.length) console.log(`[tool_calls] ${JSON.stringify(streamed.toolCalls)}`)
    if (ttsEnabled && streamed.text.trim()) await synthesize(streamed.text)
    return
  }

  const body = await request('/v1/chat/completions', {
    method: 'POST',
    body: JSON.stringify({ model: 'wisp-debug', messages: [{ role: 'user', content: text }], stream: false }),
  })
  const content = assistantText(body)
  const toolCalls = toolCallsFrom(body)
  if (content) console.log(content)
  if (toolCalls.length) console.log(`[tool_calls] ${JSON.stringify(toolCalls)}`)
  if (ttsEnabled && content.trim()) await synthesize(content)
}

async function runInteractiveCli() {
  console.log(`Wisp interactive reply console -> ${baseUrl}`)
  console.log('Waiting for phone chat requests.')
  console.log('Commands: reply start | sticker list | voice list | sticker <tag> | tool <name> <json> | reply end | reply_cancel')

  let active = null
  let draft = []
  const announced = new Set()
  const announcedVoices = new Set()
  let pollBusy = false

  const poll = setInterval(async () => {
    if (pollBusy || active) return
    pollBusy = true
    try {
      try {
        const voiceBody = await request('/api/v1/debug/voices')
        const voices = Array.isArray(voiceBody.voices) ? voiceBody.voices : []
        for (const voice of [...voices].reverse()) {
          if (announcedVoices.has(voice.id)) continue
          announcedVoices.add(voice.id)
          console.log(`\n[voice] ${voiceLabel(voice)}`)
          if (process.stdin.isTTY) process.stdout.write('wisp> ')
        }
      } catch {
        // Voice inbox is optional so an older server does not break request polling.
      }
      const body = await request('/api/v1/debug/inbox')
      if (!body.interactive) return
      const pending = body.sessions?.find((session) => session.status === 'pending')
      if (pending && !announced.has(pending.requestId)) {
        announced.add(pending.requestId)
        console.log(`\n[incoming ${pending.requestId}] ${pending.userText || '(empty user message)'}`)
        if (process.stdin.isTTY) process.stdout.write('wisp> ')
      }
    } catch (error) {
      if (!announced.has('__poll_error__')) {
        announced.add('__poll_error__')
        console.error(`[error] interactive inbox: ${error instanceof Error ? error.message : error}`)
      }
    } finally {
      pollBusy = false
    }
  }, 500)

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: Boolean(process.stdin.isTTY) })
  if (process.stdin.isTTY) process.stdout.write('wisp> ')

  try {
    for await (const line of rl) {
      const command = line.trim()
      if (!command) {
        if (process.stdin.isTTY) process.stdout.write('wisp> ')
        continue
      }

      try {
        if (command === 'voice list') {
          await listVoices()
        } else if (command === 'reply start') {
          if (active) throw new Error('A reply is already active; use reply end or reply_cancel')
          const inbox = await request('/api/v1/debug/inbox')
          const pending = inbox.sessions?.find((session) => session.status === 'pending')
          if (!pending) throw new Error('No pending phone request')
          const started = await request('/api/v1/debug/reply/start', {
            method: 'POST',
            body: JSON.stringify({ requestId: pending.requestId }),
          })
          active = started.session
          draft = []
          console.log(`[reply start] ${active.requestId} <- ${active.userText || '(empty user message)'}`)
        } else if (command === 'reply end') {
          if (!active) throw new Error('No active reply; use reply start first')
          const content = draft.join('\n')
          await request('/api/v1/debug/reply/end', {
            method: 'POST',
            body: JSON.stringify({ requestId: active.requestId, content }),
          })
          console.log(`[reply end] delivered ${content.length} characters to phone`)
          active = null
          draft = []
        } else if (command === 'reply_cancel') {
          if (!active) throw new Error('No active reply')
          await request('/api/v1/debug/reply/cancel', {
            method: 'POST',
            body: JSON.stringify({ requestId: active.requestId }),
          })
          console.log(`[reply_cancel] ${active.requestId}`)
          active = null
          draft = []
        } else if (command === 'sticker list') {
          await listStickers()
        } else if (command.startsWith('sticker ')) {
          if (!active) throw new Error('Use reply start before sending a sticker')
          const parts = command.split(/\s+/)
          const pack = parts.length > 2 ? parts[1] : undefined
          const tag = parts.length > 2 ? parts.slice(2).join(' ') : parts[1]
          const body = await request('/api/v1/debug/reply/action', {
            method: 'POST',
            body: JSON.stringify({ requestId: active.requestId, action: 'sticker', pack, tag }),
          })
          const marker = body.result?.marker
          if (!marker) throw new Error('Sticker action returned no marker')
          draft.push(marker)
          console.log(`[sticker] ${marker}`)
        } else if (command.startsWith('tool ')) {
          if (!active) throw new Error('Use reply start before calling a tool')
          const match = command.match(/^tool\s+(\S+)(?:\s+(.+))?$/)
          const tool = match?.[1]
          const rawArguments = match?.[2] || '{}'
          if (!tool) throw new Error('Usage: tool <name> <json>')
          let argumentsValue
          try { argumentsValue = JSON.parse(rawArguments) } catch { throw new Error('Tool arguments must be valid JSON') }
          const body = await request('/api/v1/debug/reply/action', {
            method: 'POST',
            body: JSON.stringify({ requestId: active.requestId, action: 'tool', tool, arguments: argumentsValue }),
          })
          console.log(`[tool] ${JSON.stringify(body.result ?? body)}`)
        } else if (active) {
          draft.push(line)
          console.log(`[draft] ${line}`)
        } else {
          console.log('[waiting] Use reply start after a phone request appears.')
        }
      } catch (error) {
        console.error(`[error] ${error instanceof Error ? error.message : error}`)
      }
      if (process.stdin.isTTY) process.stdout.write('wisp> ')
    }
  } finally {
    clearInterval(poll)
    rl.close()
  }
}

if (interactive) {
  await runInteractiveCli()
  process.exit(0)
}

console.log(`Wisp AI CLI -> ${baseUrl}/v1`)
console.log(`Input mode: direct text${stream ? ' | SSE streaming' : ''}${scenario ? ` | scenario=${scenario}` : ''}${ttsEnabled ? ` | TTS=${ttsEngine}/${ttsVoice}` : ''}`)
console.log('直接输入文本发送；按 Ctrl+C 或结束输入退出。')

const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: Boolean(process.stdin.isTTY) })
if (process.stdin.isTTY) process.stdout.write('wisp> ')

try {
  for await (const line of rl) {
    if (line.trim()) {
      try {
        await ask(line.trim())
      } catch (error) {
        console.error(`[error] ${error instanceof Error ? error.message : error}`)
      }
    }
    if (process.stdin.isTTY) process.stdout.write('wisp> ')
  }
} finally {
  rl.close()
}
