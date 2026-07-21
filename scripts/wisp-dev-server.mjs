import http from 'node:http'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { randomUUID, createHash } from 'node:crypto'

const root = process.env.WISP_ROOT || process.cwd()
const host = process.env.WISP_DEBUG_HOST || '127.0.0.1'
const port = Number.parseInt(process.env.WISP_DEBUG_PORT || '17890', 10)
const maxBodyBytes = Number.parseInt(process.env.WISP_MAX_BODY_BYTES || String(16 * 1024 * 1024), 10)
const startedAt = Date.now()
const allowNetwork = process.env.WISP_DEBUG_NO_NETWORK !== '1'
const apiKey = process.env.WISP_API_KEY || ''

// ── WebSocket helpers (zero-dependency raw WS) ──
/** @type {Set<import('node:net').Socket>} */
const wsClients = new Set()

function wsFrame(json) {
  const payload = Buffer.from(JSON.stringify(json), 'utf8')
  const len = payload.length
  let frame
  if (len < 126) {
    frame = Buffer.alloc(2 + len)
    frame[1] = len
    payload.copy(frame, 2)
  } else {
    frame = Buffer.alloc(4 + len)
    frame[1] = 126
    frame.writeUInt16BE(len, 2)
    payload.copy(frame, 4)
  }
  frame[0] = 0x81 // FIN + text opcode
  return frame
}

function wsBroadcast(data) {
  const frame = wsFrame(data)
  for (const socket of wsClients) {
    try { socket.write(frame) } catch { wsClients.delete(socket) }
  }
}

function wsSend(socket, data) {
  try { socket.write(wsFrame(data)) } catch { wsClients.delete(socket) }
}

const gptSovitsUrl = (process.env.WISP_GPT_SOVITS_URL || '').replace(/\/$/, '')
const qwen3TtsUrl = (process.env.WISP_QWEN3_TTS_URL || '').replace(/\/$/, '')
const qwen3Mode = process.env.WISP_QWEN3_MODE || ''
const qwen3RefAudio = process.env.WISP_QWEN3_REF_AUDIO || ''
const qwen3RefText = process.env.WISP_QWEN3_REF_TEXT || ''
const qwen3XVectorOnly = ['1', 'true', 'yes'].includes(String(process.env.WISP_QWEN3_X_VECTOR_ONLY || '').toLowerCase())
const defaultScenario = process.env.WISP_AI_SCENARIO || 'echo'
const interactiveMode = ['1', 'true'].includes(String(process.env.WISP_INTERACTIVE || '').toLowerCase())
const interactiveTimeoutMs = Number.parseInt(process.env.WISP_INTERACTIVE_TIMEOUT_MS || '180000', 10)
const dashboardToken = process.env.WISP_DASHBOARD_TOKEN || ''
const voiceDir = path.resolve(process.env.WISP_VOICE_DIR || path.join(root, 'wisp-voice-inbox'))

const builtInTools = [
  ['time', 'Get the current time.', {}, []],
  ['note', 'Save a note in Wisp memory.', { text: { type: 'string' }, topic: { type: 'string' } }, ['text']],
  ['recall', 'Recall relevant Wisp memory.', { q: { type: 'string' }, limit: { type: 'number' } }, ['q']],
  ['send_message', 'Send a notification or save a message to a Wisp chat.', { text: { type: 'string' }, chat: { type: 'string' } }, ['text']],
  ['web_search', 'Search the web.', { q: { type: 'string' }, count: { type: 'number' } }, ['q']],
  ['access_location', 'Read the device location.', {}, []],
  ['read_notifications', 'Read device notifications.', {}, []],
  ['read_app_usage', 'Read app usage statistics.', {}, []],
  ['battery_info', 'Read battery information.', {}, []],
  ['screen_info', 'Read screen information.', {}, []],
  ['calculator', 'Evaluate a calculation.', { expr: { type: 'string' } }, ['expr']],
]

const state = {
  packages: [],
  tools: new Map(),
  stickers: new Map(),
  interactiveSessions: new Map(),
  // Development-only telemetry. The phone remains the source of truth.
  affectSnapshots: new Map(),
  affectEvents: [],
  affectEventKeys: new Set(),
  // P1.5: 同步数据保留期限 + GC 调度
  // affectTtlMs: 快照/事件保留时长（默认 24h），超期由 maybeGcAffect 清理
  // affectLastGcAt: 上次 GC 时间戳，每 10min 最多执行一次清理（避免每次写入都 GC）
  affectTtlMs: 24 * 60 * 60 * 1000,
  affectLastGcAt: 0,
  affectGcIntervalMs: 10 * 60 * 1000,
  // P2.1: 主动消息审批队列（Desktop 端只做"建议列表 + 决策记录"，不做实际发送）
  // proactiveQueue: id → pending message（含 status: pending/approved/rejected/cancelled）
  // proactiveDecisions: 决策历史数组（Android 增量拉取用）
  // proactiveDecisionKeys: 决策幂等去重集合（与 proactiveDecisions 同步重建，参考 affectEventKeys 模式）
  proactiveQueue: new Map(),
  proactiveDecisions: [],
  proactiveDecisionKeys: new Set(),
  proactiveTtlMs: 24 * 60 * 60 * 1000,
  proactiveLastGcAt: 0,
  voiceInbox: [],
  imageCache: new Map(), // requestId → [{ id, mime }]
}

const schema = (properties, required = []) => ({
  type: 'object',
  properties: Object.fromEntries(Object.entries(properties).map(([name, value]) => [name, { type: 'string', ...value }])),
  required,
})

function descriptorTool(packageName, descriptor, tool) {
  const properties = Object.fromEntries((tool.parameters || []).map((parameter) => [
    parameter.name,
    { type: parameter.type || 'string', description: parameter.description || '' },
  ]))
  return {
    name: tool.name,
    description: tool.description || '',
    inputSchema: schema(properties, (tool.parameters || []).filter((parameter) => parameter.required).map((parameter) => parameter.name)),
    kind: 'wsptool',
    packageName,
    impl: tool.impl || descriptor.impl || null,
  }
}

async function loadTools() {
  state.tools.clear()
  for (const [name, description, properties, required] of builtInTools) {
    state.tools.set(name, { name, description, inputSchema: schema(properties, required), kind: 'builtin' })
  }

  const toolsDir = path.join(root, 'app', 'src', 'main', 'assets', 'tools')
  let files = []
  try {
    files = (await fs.readdir(toolsDir)).filter((file) => file.endsWith('.wsptool'))
  } catch (error) {
    console.warn(`Cannot read Wisp tool assets at ${toolsDir}: ${error.message}`)
  }
  for (const file of files) {
    try {
      const descriptor = JSON.parse(await fs.readFile(path.join(toolsDir, file), 'utf8'))
      state.packages.push(descriptor.name || file.replace(/\.wsptool$/, ''))
      for (const tool of descriptor.tools || []) {
        if (!tool.advice) state.tools.set(tool.name, descriptorTool(descriptor.name, descriptor, tool))
      }
    } catch (error) {
      console.warn(`Cannot load ${file}: ${error.message}`)
    }
  }
}

async function loadStickers() {
  state.stickers.clear()
  const stickersDir = path.join(root, 'app', 'src', 'main', 'assets', 'stickers')
  let packs = []
  try {
    packs = await fs.readdir(stickersDir, { withFileTypes: true })
  } catch (error) {
    console.warn(`Cannot read Wisp sticker assets at ${stickersDir}: ${error.message}`)
  }
  for (const packEntry of packs.filter((entry) => entry.isDirectory())) {
    try {
      const manifestPath = path.join(stickersDir, packEntry.name, 'stickers.json')
      const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
      state.stickers.set(packEntry.name, manifest.stickers || [])
    } catch (error) {
      console.warn(`Cannot load stickers for ${packEntry.name}: ${error.message}`)
    }
  }
}

function jsonResponse(response, statusCode, value) {
  const body = JSON.stringify(value)
  response.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type, authorization, x-wisp-scenario',
    'access-control-allow-methods': 'GET, POST, OPTIONS',
  })
  response.end(body)
}

function rawResponse(response, statusCode, body, contentType = 'application/octet-stream') {
  const data = Buffer.isBuffer(body) ? body : Buffer.from(body)
  response.writeHead(statusCode, {
    'content-type': contentType,
    'content-length': data.length,
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type, authorization, x-wisp-scenario',
    'access-control-allow-methods': 'GET, POST, OPTIONS',
  })
  response.end(data)
}

function authorized(request) {
  if (!apiKey) return true
  if (request.headers.authorization === `Bearer ${apiKey}`) return true
  return Boolean(dashboardToken && request.headers['x-wisp-dashboard-token'] === dashboardToken)
}

function result(requestId, tool, ok, value, error, started, status = ok ? 200 : 400) {
  return {
    status,
    body: {
      ok,
      requestId,
      tool,
      result: ok ? value : null,
      error: ok ? null : error,
      elapsedMs: Date.now() - started,
    },
  }
}

function requestIdOf(request) {
  return typeof request?.requestId === 'string' && request.requestId
    ? request.requestId
    : `wisp_dbg_${randomUUID()}`
}

function numberInRange(value, min, max, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback
}

function shortText(value, max = 300) {
  return String(value ?? '').trim().slice(0, max)
}

function normalizePendingEvent(value) {
  if (!value || typeof value !== 'object') return null
  return {
    id: shortText(value.id, 120),
    summary: shortText(value.summary, 300),
    triggerText: shortText(value.triggerText, 300),
    weight: numberInRange(value.weight, 0, 1, 0.5),
    closureType: shortText(value.closureType, 40),
    attemptCount: Math.max(0, Number.parseInt(value.attemptCount, 10) || 0),
    lastAttemptAt: Number.isFinite(Number(value.lastAttemptAt)) ? Number(value.lastAttemptAt) : null,
    createdAt: Number.isFinite(Number(value.createdAt)) ? Number(value.createdAt) : null,
    resolved: Boolean(value.resolved),
    archived: Boolean(value.archived),
    staleness: numberInRange(value.staleness, 0, 1, 0),
  }
}

function normalizeAffectSnapshot(payload) {
  const field = payload?.affectiveField || payload?.field || {}
  const rhythm = payload?.rhythmProfile || payload?.rhythm || {}
  const hint = payload?.stateHint || payload?.rhythmHint || {}
  const assessment = payload?.responseAssessment || payload?.assessment || null
  const chatName = shortText(payload?.chatName || payload?.characterId || payload?.conversationId || 'default', 120) || 'default'
  const pendingEvents = Array.isArray(payload?.pendingEvents) ? payload.pendingEvents.map(normalizePendingEvent).filter(Boolean).slice(0, 50) : []
  const closureCandidates = Array.isArray(payload?.closureCandidates) ? payload.closureCandidates.map(normalizePendingEvent).filter(Boolean).slice(0, 20) : []

  return {
    chatName,
    characterId: shortText(payload?.characterId, 120),
    conversationId: shortText(payload?.conversationId, 120),
    eventId: shortText(payload?.eventId, 120),
    source: shortText(payload?.source || 'android', 40),
    receivedAt: new Date().toISOString(),
    affectiveField: {
      tension: numberInRange(field.tension, -1, 1),
      warmth: numberInRange(field.warmth, -1, 1),
      anticipation: numberInRange(field.anticipation, -1, 1),
      drift: numberInRange(field.drift, -1, 1),
      lastUpdatedTs: Number.isFinite(Number(field.lastUpdatedTs)) ? Number(field.lastUpdatedTs) : null,
    },
    rhythmProfile: {
      sampleCount: Math.max(0, Number.parseInt(rhythm.sampleCount, 10) || 0),
      latencyPercentile: numberInRange(rhythm.latencyPercentile, 0, 1, 0.5),
      lengthPercentile: numberInRange(rhythm.lengthPercentile, 0, 1, 0.5),
      latencyTrend: numberInRange(rhythm.latencyTrend, -1, 1),
      lengthTrend: numberInRange(rhythm.lengthTrend, -1, 1),
      initiativeRate7d: numberInRange(rhythm.initiativeRate7d, 0, 1),
    },
    stateHint: {
      observation: shortText(hint.observation, 300),
      hypothesis: shortText(hint.hypothesis, 300),
      actionSuggestion: shortText(hint.actionSuggestion, 300),
      // 风险 2 修复：解析结构化 signals，供 Dashboard 调度逻辑使用
      signals: Array.isArray(hint.signals) ? hint.signals.filter(s => typeof s === 'string').slice(0, 10) : [],
    },
    pendingEvents,
    closureCandidates,
    responseAssessment: assessment && typeof assessment === 'object' ? {
      userDisclosed: Boolean(assessment.userDisclosed),
      userAskedQuestion: Boolean(assessment.userAskedQuestion),
      userSharedPositive: Boolean(assessment.userSharedPositive),
      aiRespondedToEmotion: Boolean(assessment.aiRespondedToEmotion),
      aiAnsweredContent: Boolean(assessment.aiAnsweredContent),
      aiCelebrated: Boolean(assessment.aiCelebrated),
      warmthDelta: numberInRange(assessment.warmthDelta, -1, 1),
      shouldCreatePending: Boolean(assessment.shouldCreatePending),
      pendingSummary: shortText(assessment.pendingSummary, 300),
      pendingClosureType: shortText(assessment.pendingClosureType, 40),
    } : null,
  }
}

function storeAffectSnapshot(payload) {
  const snapshot = normalizeAffectSnapshot(payload)
  const key = snapshot.chatName
  const eventKey = snapshot.eventId ? `${key}:${snapshot.eventId}` : ''
  if (eventKey && state.affectEventKeys.has(eventKey)) {
    return { snapshot: state.affectSnapshots.get(key) || snapshot, duplicate: true }
  }

  state.affectSnapshots.set(key, snapshot)
  if (eventKey) state.affectEventKeys.add(eventKey)
  const event = {
    chatName: snapshot.chatName,
    eventId: snapshot.eventId,
    receivedAt: snapshot.receivedAt,
    affectiveField: snapshot.affectiveField,
    responseAssessment: snapshot.responseAssessment,
    pendingCount: snapshot.pendingEvents.length,
  }
  state.affectEvents.unshift(event)
  state.affectEvents = state.affectEvents.slice(0, 200)
  // slice 后立即同步重建 affectEventKeys：
  // - 消除"被挤掉的 eventId 在下次 GC 前仍残留于 Set 中被误判为重复"的窗口
  // - 替代原先 size > 500 才重建的延迟策略
  // - 开销：最多 200 条 map+filter+Set 构造，微秒级，可接受
  state.affectEventKeys = new Set(
    state.affectEvents
      .map(item => `${item.chatName}:${item.eventId}`)
      .filter(item => !item.endsWith(':'))
  )
  wsBroadcast({ type: 'affect.updated', snapshot, event })
  // P1.5: 触发保留期限 GC（每 10min 一次，避免每次写入都 GC）
  maybeGcAffect()
  return { snapshot, event, duplicate: false }
}

/**
 * P1.5: 同步数据保留期限 GC。
 *
 * 清理规则：
 * - affectSnapshots: 删除 receivedAt 超过 affectTtlMs（默认 24h）的快照
 * - affectEvents: 删除 receivedAt 超过 affectTtlMs 的事件
 * - affectEventKeys: 同步清理已删除事件对应的 key
 *
 * 调度：每 affectGcIntervalMs（默认 10min）最多执行一次，避免高频写入时 GC 开销。
 */
function maybeGcAffect() {
  const now = Date.now()
  if (now - state.affectLastGcAt < state.affectGcIntervalMs) return
  state.affectLastGcAt = now
  const cutoff = now - state.affectTtlMs

  // GC affectSnapshots（按 chatName key，value 含 receivedAt）
  let gcSnapshots = 0
  for (const [chatName, snapshot] of state.affectSnapshots) {
    if (!snapshot.receivedAt || new Date(snapshot.receivedAt).getTime() < cutoff) {
      state.affectSnapshots.delete(chatName)
      gcSnapshots++
    }
  }

  // GC affectEvents
  const beforeEvents = state.affectEvents.length
  state.affectEvents = state.affectEvents.filter(event => {
    const ts = event.receivedAt ? new Date(event.receivedAt).getTime() : 0
    return ts >= cutoff
  })
  const gcEvents = beforeEvents - state.affectEvents.length

  // 无条件重建 affectEventKeys：
  // - 清理 TTL 过期事件对应的 key
  // - 清理被 affectEvents.slice(0, 200) 丢弃但仍残留在 affectEventKeys 中的 key
  //   （storeAffectSnapshot 的 slice 上限 200 与 affectEventKeys 上限 500 不一致，
  //    若不重建，过期 eventId 仍会被误判为重复）
  // 开销：最多 200 条事件重建 Set，微秒级，可接受
  state.affectEventKeys = new Set(
    state.affectEvents
      .map(item => `${item.chatName}:${item.eventId}`)
      .filter(item => !item.endsWith(':'))
  )
  if (gcSnapshots > 0 || gcEvents > 0) {
    console.log(`[affect-gc] cleaned ${gcSnapshots} snapshots, ${gcEvents} events (ttl=${state.affectTtlMs}ms)`)
  }
}

/**
 * P2.1: 主动消息审批队列 GC。
 *
 * 清理规则（与 maybeGcAffect 保持一致的模式）：
 * - proactiveQueue: 删除 createdAt 超过 proactiveTtlMs（默认 24h）的条目
 * - proactiveDecisions: 删除 decidedAt 超过 proactiveTtlMs 的条目
 * - proactiveDecisionKeys: 无条件重建（参考 affectEventKeys 修复模式，避免 slice 后残留）
 *
 * 调度：复用 affectGcIntervalMs（每 10min 最多一次），与 affect GC 共享调度避免频繁 GC。
 */
function maybeGcProactive() {
  const now = Date.now()
  // 共享 affect 的 GC 调度间隔（同一时间窗口内 affect GC 已跑过则跳过）
  if (now - state.proactiveLastGcAt < state.affectGcIntervalMs) return
  state.proactiveLastGcAt = now
  const cutoff = now - state.proactiveTtlMs

  // GC proactiveQueue
  let gcQueue = 0
  for (const [id, msg] of state.proactiveQueue) {
    if (!msg.createdAt || msg.createdAt < cutoff) {
      state.proactiveQueue.delete(id)
      gcQueue++
    }
  }

  // GC proactiveDecisions
  const beforeDecisions = state.proactiveDecisions.length
  state.proactiveDecisions = state.proactiveDecisions.filter(d => {
    const ts = d.decidedAt || 0
    return ts >= cutoff
  })
  const gcDecisions = beforeDecisions - state.proactiveDecisions.length

  // 无条件重建 proactiveDecisionKeys（参考 affectEventKeys 修复模式）
  state.proactiveDecisionKeys = new Set(state.proactiveDecisions.map(d => d.id))

  if (gcQueue > 0 || gcDecisions > 0) {
    console.log(`[proactive-gc] cleaned ${gcQueue} queue entries, ${gcDecisions} decisions (ttl=${state.proactiveTtlMs}ms)`)
  }
}

/**
 * P2.1: 存储 Android 提交的 pending 消息到 Desktop 队列。
 *
 * 幂等：相同 id 重复提交时更新而非报错（Android 重试场景）。
 * 上限：proactiveQueue 最多 200 条，超出丢弃最旧。
 */
function storeProactivePending(payload) {
  const id = shortText(payload.id, 120)
  if (!id) return { ok: false, error: 'missing id' }
  const msg = {
    id,
    chatName: shortText(payload.chatName, 120),
    content: shortText(payload.content, 1000),
    createdAt: Number(payload.createdAt) || Date.now(),
    gatekeeperReason: shortText(payload.gatekeeperReason, 500),
    warmth: numberInRange(payload.warmth, -1, 1, 0),
    tension: numberInRange(payload.tension, -1, 1, 0),
    msSinceLastActive: Number(payload.msSinceLastActive) || -1,
    status: 'pending',
    receivedAt: Date.now(),
  }
  // 队列上限：200 条，超出丢弃最旧
  while (state.proactiveQueue.size >= 200) {
    const oldest = [...state.proactiveQueue.entries()]
      .sort((a, b) => a[1].createdAt - b[1].createdAt)[0]
    if (oldest) state.proactiveQueue.delete(oldest[0])
    else break
  }
  state.proactiveQueue.set(id, msg)
  wsBroadcast({ type: 'proactive.pending', msg })
  maybeGcProactive()
  return { ok: true, msg }
}

/**
 * P2.1: 记录一条决策到 Desktop 队列。
 *
 * 内部行为：
 * 1. 更新 proactiveQueue 中对应 msg 的 status
 * 2. 追加到 proactiveDecisions（Android 增量拉取用）
 * 3. 同步更新 proactiveDecisionKeys（幂等去重）
 *
 * 幂等：相同 id 重复决策时覆盖（保留最新决策）。
 */
function recordProactiveDecision(id, action, reason = '') {
  const msg = state.proactiveQueue.get(id)
  if (msg) {
    msg.status = action.toLowerCase()  // approve → 'approve'（与 Android 端 'approved' 略不同，仅 Desktop 内部用）
    msg.decidedAt = Date.now()
    state.proactiveQueue.set(id, msg)
  }
  const decision = {
    id,
    action: action.toUpperCase(),  // APPROVE / REJECT / CANCEL
    decidedAt: Date.now(),
    reason: shortText(reason, 300),
  }
  // 去重：相同 id 移除旧决策，追加新决策
  state.proactiveDecisions = state.proactiveDecisions.filter(d => d.id !== id)
  state.proactiveDecisions.unshift(decision)
  // 上限 500 条
  state.proactiveDecisions = state.proactiveDecisions.slice(0, 500)
  // 无条件重建 keys（与 maybeGcProactive 一致）
  state.proactiveDecisionKeys = new Set(state.proactiveDecisions.map(d => d.id))
  wsBroadcast({ type: 'proactive.decision', decision })
  return decision
}

async function readBuffer(request) {
  const chunks = []
  let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > maxBodyBytes) throw new Error('REQUEST_TOO_LARGE')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks)
}

async function readJson(request) {
  const body = await readBuffer(request)
  if (!body.length) return {}
  try {
    return JSON.parse(body.toString('utf8'))
  } catch {
    throw new Error('INVALID_JSON')
  }
}

function parseMultipart(buffer, contentType) {
  const boundaryMatch = String(contentType || '').match(/boundary=(?:"([^"]+)"|([^;]+))/i)
  if (!boundaryMatch) throw new Error('MULTIPART_BOUNDARY_MISSING')
  const boundary = Buffer.from(`--${boundaryMatch[1] || boundaryMatch[2]}`)
  const parts = []
  let cursor = buffer.indexOf(boundary)

  while (cursor >= 0) {
    cursor += boundary.length
    if (buffer.subarray(cursor, cursor + 2).toString('ascii') === '--') break
    if (buffer.subarray(cursor, cursor + 2).toString('ascii') === '\r\n') cursor += 2
    const next = buffer.indexOf(boundary, cursor)
    if (next < 0) break
    let rawPart = buffer.subarray(cursor, next)
    if (rawPart.subarray(-2).toString('ascii') === '\r\n') rawPart = rawPart.subarray(0, -2)
    const separator = Buffer.from('\r\n\r\n')
    const headerEnd = rawPart.indexOf(separator)
    if (headerEnd >= 0) {
      const headers = {}
      for (const line of rawPart.subarray(0, headerEnd).toString('utf8').split('\r\n')) {
        const colon = line.indexOf(':')
        if (colon > 0) headers[line.slice(0, colon).trim().toLowerCase()] = line.slice(colon + 1).trim()
      }
      const disposition = headers['content-disposition'] || ''
      const name = disposition.match(/(?:^|;)\s*name="([^"]*)"/i)?.[1] || ''
      const filename = disposition.match(/(?:^|;)\s*filename="([^"]*)"/i)?.[1] || ''
      parts.push({ name, filename, contentType: headers['content-type'] || '', body: rawPart.subarray(headerEnd + separator.length) })
    }
    cursor = next
  }
  return parts
}

async function receiveTranscription(request, response) {
  const contentType = String(request.headers['content-type'] || '')
  if (!contentType.toLowerCase().startsWith('multipart/form-data')) {
    return jsonResponse(response, 415, { error: { message: 'Expected multipart/form-data', type: 'invalid_content_type' } })
  }

  const parts = parseMultipart(await readBuffer(request), contentType)
  const filePart = parts.find((part) => part.name === 'file' && part.body.length > 0)
  if (!filePart) {
    return jsonResponse(response, 400, { error: { message: 'A multipart file field is required', type: 'missing_file' } })
  }

  const fields = Object.fromEntries(parts.filter((part) => part.name && !part.filename).map((part) => [part.name, part.body.toString('utf8')]))
  const originalName = filePart.filename || 'voice.bin'
  const safeName = path.basename(originalName).replace(/[^a-zA-Z0-9._-]/g, '_') || 'voice.bin'
  const id = `wisp_voice_${randomUUID()}`
  const storedName = `${Date.now()}_${id}_${safeName}`
  await fs.mkdir(voiceDir, { recursive: true })
  const storedPath = path.join(voiceDir, storedName)
  await fs.writeFile(storedPath, filePart.body)

  const text = String(fields.text || process.env.WISP_STT_TEXT || '这是来自手机端的测试语音').trim()
  const voice = {
    id,
    receivedAt: new Date().toISOString(),
    filename: originalName,
    storedPath,
    contentType: filePart.contentType || 'application/octet-stream',
    size: filePart.body.length,
    model: fields.model || 'whisper-1',
    language: fields.language || '',
    text,
  }
  state.voiceInbox.unshift(voice)
  state.voiceInbox = state.voiceInbox.slice(0, 100)
  // Notify Dashboard of new voice
  wsBroadcast({ type: 'voice.new', voice })
  console.log(JSON.stringify({ event: 'voice.received', id, filename: originalName, size: voice.size, text }))
  return jsonResponse(response, 200, { text, id, filename: originalName, size: voice.size })
}

function replaceVars(value, args, env = {}) {
  if (typeof value !== 'string') return value || ''
  return value.replace(/\$?\{(\w+)}/g, (match, key) => {
    if (Object.prototype.hasOwnProperty.call(args, key)) return String(args[key])
    if (Object.prototype.hasOwnProperty.call(env, key)) return String(env[key])
    return match
  })
}

function extractJsonPath(value, expression) {
  const parts = expression.replace(/^\$\.?/, '').split('.').filter(Boolean)
  let current = value
  for (const part of parts) {
    const match = part.match(/^(\w+)\[(\d+)]$/)
    if (match) current = current?.[match[1]]?.[Number(match[2])]
    else current = current?.[part]
  }
  return current
}

function processHttpResponse(raw, responseSpec, args) {
  if (!responseSpec) return raw.slice(0, 2000)
  if (responseSpec.format !== 'json') return raw.slice(0, 2000)
  try {
    const json = JSON.parse(raw)
    const extracted = responseSpec.extract
      ? extractJsonPath(json, replaceVars(responseSpec.extract, args))
      : json
    const value = extracted === undefined || extracted === null ? '' : String(extracted)
    return responseSpec.template ? responseSpec.template.replace('{result}', value) : value
  } catch {
    return raw.slice(0, 2000)
  }
}

async function executeHttp(tool, args) {
  if (!allowNetwork) throw new Error('NETWORK_DISABLED: set WISP_DEBUG_NO_NETWORK=0 to enable HTTP tools')
  const impl = tool.impl || {}
  const url = replaceVars(impl.url, args)
  if (!url || !/^https?:\/\//i.test(url)) throw new Error('INVALID_HTTP_URL')
  const headers = Object.fromEntries(Object.entries(impl.headers || {}).map(([key, value]) => [key, replaceVars(value, args)]))
  const method = String(impl.method || 'GET').toUpperCase()
  const options = { method, headers, signal: AbortSignal.timeout(10000) }
  if (!['GET', 'HEAD'].includes(method)) {
    options.body = replaceVars(impl.body || '', args)
    if (!options.body) options.body = JSON.stringify(args)
    if (!Object.keys(headers).some((key) => key.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json'
  }
  const response = await fetch(url, options)
  const raw = await response.text()
  if (!response.ok) throw new Error(`HTTP_${response.status}: ${raw.slice(0, 300)}`)
  return processHttpResponse(raw, impl.response, args)
}

function findSticker(packName, tag) {
  const entries = state.stickers.get(packName) || []
  const key = String(tag || '').trim().toLowerCase()
  const tagged = entries.flatMap((entry) => (entry.tags || []).map((entryTag) => ({ entry, tag: entryTag })))
  const exact = tagged.find((item) => item.tag.trim().toLowerCase() === key)
  const fuzzy = tagged.filter((item) => item.tag.toLowerCase().includes(key) || key.includes(item.tag.toLowerCase()))
    .sort((left, right) => left.tag.length - right.tag.length)[0]
  const match = exact || fuzzy
  if (!match) return null
  return {
    pack: packName,
    tag: match.tag,
    file: match.entry.file,
    asset: `stickers/${packName}/${match.entry.file}`,
    marker: `【sticker:${packName}:${match.tag}】`,
  }
}

function lastUserText(messages) {
  const message = [...(Array.isArray(messages) ? messages : [])].reverse().find((item) => item?.role === 'user')
  if (!message) return ''
  if (typeof message.content === 'string') return message.content
  if (Array.isArray(message.content)) return message.content.filter((part) => part?.type === 'text').map((part) => part.text || '').join(' ')
  return ''
}

/** Extract base64 data URIs from the last user message's image_url parts */
function lastUserImages(messages) {
  const message = [...(Array.isArray(messages) ? messages : [])].reverse().find((item) => item?.role === 'user')
  if (!message || !Array.isArray(message.content)) return []
  return message.content
    .filter(part => part?.type === 'image_url' && part?.image_url?.url)
    .map(part => part.image_url.url)
}

function chooseSticker() {
  const pack = process.env.WISP_STICKER_PACK || [...state.stickers.keys()][0]
  const tag = process.env.WISP_STICKER_TAG || '开心'
  return findSticker(pack, tag)
}

/** Parse 【sticker:pack:tag】 marker from text, returns { pack, tag, file } or null */
function parseStickerMarker(text) {
  const m = String(text || '').match(/【sticker:([^:】]+):([^】]+)】/)
  if (!m) return null
  const found = findSticker(m[1], m[2])
  return found ? { pack: found.pack, tag: found.tag, file: found.file } : null
}

function parseScenario(request, url) {
  const header = request.headers['x-wisp-scenario']
  return String(header || url.searchParams.get('scenario') || defaultScenario).trim().toLowerCase()
}

function pickToolName(requestBody) {
  const configured = process.env.WISP_AI_TOOL || ''
  if (configured) return configured
  const requested = Array.isArray(requestBody.tools) ? requestBody.tools[0] : null
  return requested?.function?.name || requested?.name || 'send_message'
}

function buildToolArguments(name, text) {
  if (name === 'send_message') return { text: process.env.WISP_TOOL_TEXT || text || '来自电脑端 Wisp 调试服务的测试消息' }
  if (name === 'note') return { text: process.env.WISP_TOOL_TEXT || text || 'Wisp 工具调用测试', topic: 'debug' }
  if (name === 'recall') return { q: process.env.WISP_TOOL_QUERY || text || '测试', limit: 3 }
  if (name === 'calculator') return { expr: process.env.WISP_TOOL_EXPR || '1 + 2 * 3' }
  if (name === 'web_search') return { q: process.env.WISP_TOOL_QUERY || text || 'Wisp debug' }
  if (name === 'sticker.send' || name === 'sticker') {
    const sticker = chooseSticker()
    return { pack: sticker?.pack || process.env.WISP_STICKER_PACK || '', tag: sticker?.tag || process.env.WISP_STICKER_TAG || '开心' }
  }
  return {}
}

function chatCompletion(requestBody, request, url) {
  const scenario = parseScenario(request, url)
  const text = lastUserText(requestBody.messages)
  const id = `chatcmpl_wisp_${randomUUID()}`
  const toolMode = scenario === 'tool' || scenario.startsWith('tool:')
  const toolName = scenario.startsWith('tool:') ? scenario.slice('tool:'.length) : pickToolName(requestBody)
  if (toolMode) {
    return {
      id,
      model: requestBody.model || 'wisp-debug',
      choices: [{
        index: 0,
        message: {
          role: 'assistant',
          content: '',
          tool_calls: [{ id: `call_${randomUUID()}`, type: 'function', function: { name: toolName, arguments: JSON.stringify(buildToolArguments(toolName, text)) } }],
        },
        finish_reason: 'tool_calls',
      }],
      usage: { prompt_tokens: Math.max(1, text.length), completion_tokens: 1, total_tokens: Math.max(2, text.length + 1) },
    }
  }

  let content
  if (scenario === 'sticker' || scenario === 'sticker:test') {
    const sticker = chooseSticker()
    const marker = sticker?.marker || '【sticker:呆猫八条:开心】'
    // Keep a short text segment before the marker. Wisp renders pure sticker-only
    // replies, but a text prefix also exercises the normal message-finalization path.
    content = `Wisp 贴纸测试：${marker}`
  } else if (scenario === 'empty') {
    content = ''
  } else if (scenario === 'echo' || scenario === 'chat') {
    content = process.env.WISP_AI_REPLY || `Wisp 电脑端调试回复：${text || '你好'}`
  } else {
    content = process.env.WISP_AI_REPLY || scenario
  }
  return {
    id,
    model: requestBody.model || 'wisp-debug',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage: { prompt_tokens: Math.max(1, text.length), completion_tokens: Math.max(1, content.length), total_tokens: Math.max(2, text.length + content.length) },
  }
}

function sseEvent(response, value) {
  response.write(`data: ${JSON.stringify(value)}\n\n`)
}

function textCompletion(requestBody, content) {
  const text = typeof content === 'string' ? content : String(content || '')
  return {
    id: `chatcmpl_wisp_${randomUUID()}`,
    model: requestBody.model || 'wisp-debug',
    choices: [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: 'stop' }],
    usage: {
      prompt_tokens: Math.max(1, text.length),
      completion_tokens: Math.max(1, text.length),
      total_tokens: Math.max(2, text.length * 2),
    },
  }
}

function writeCompletion(response, completion, stream) {
  if (!stream) return jsonResponse(response, 200, completion)
  response.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
    'access-control-allow-origin': '*',
  })
  const choice = completion.choices[0]
  if (choice.message.tool_calls) {
    const call = choice.message.tool_calls[0]
    sseEvent(response, { id: completion.id, object: 'chat.completion.chunk', model: completion.model, choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, id: call.id, type: 'function', function: { name: call.function.name, arguments: call.function.arguments } }] }, finish_reason: null }] })
    sseEvent(response, { id: completion.id, object: 'chat.completion.chunk', model: completion.model, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] })
  } else {
    const content = choice.message.content || ''
    const chunks = content.match(/.{1,24}/gu) || ['']
    for (const chunk of chunks) {
      sseEvent(response, { id: completion.id, object: 'chat.completion.chunk', model: completion.model, choices: [{ index: 0, delta: { role: 'assistant', content: chunk }, finish_reason: null }] })
    }
    sseEvent(response, { id: completion.id, object: 'chat.completion.chunk', model: completion.model, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })
  }
  response.write('data: [DONE]\n\n')
  response.end()
}

async function sendChatCompletion(response, requestBody, request, url) {
  return writeCompletion(response, chatCompletion(requestBody, request, url), requestBody.stream === true)
}

function createInteractiveSession(requestBody) {
  let resolveReply
  const reply = new Promise((resolve) => { resolveReply = resolve })
  const images = lastUserImages(requestBody.messages)
  const session = {
    requestId: `wisp_interactive_${randomUUID()}`,
    userText: lastUserText(requestBody.messages),
    userImages: images,
    model: requestBody.model || 'wisp-debug',
    stream: requestBody.stream === true,
    createdAt: Date.now(),
    status: 'pending',
    requestBody,
    reply,
    resolveReply,
  }
  // Cache images for Dashboard preview
  if (images.length > 0) {
    const cached = images.map((url, i) => {
      const imgId = `img_${session.requestId}_${i}`
      state.imageCache.set(imgId, url)
      return imgId
    })
    session.userImages = cached
  }
  // Detect sticker marker
  const sticker = parseStickerMarker(session.userText)
  if (sticker) session.sticker = sticker
  state.interactiveSessions.set(session.requestId, session)
  wsBroadcast({ type: 'session.new', session: interactiveSessionInfo(session) })
  return session
}

async function waitForInteractiveReply(response, requestBody) {
  const session = createInteractiveSession(requestBody)
  let timer
  try {
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => resolve({ timeout: true }), interactiveTimeoutMs)
    })
    const result = await Promise.race([session.reply, timeout])
    if (result?.timeout) {
      session.status = 'timeout'
      return jsonResponse(response, 504, {
        error: { message: `Interactive reply timed out after ${interactiveTimeoutMs}ms`, type: 'interactive_timeout' },
        requestId: session.requestId,
      })
    }
    if (result?.cancelled) {
      session.status = 'cancelled'
      return jsonResponse(response, 499, {
        error: { message: 'Interactive reply was cancelled', type: 'interactive_cancelled' },
        requestId: session.requestId,
      })
    }
    return writeCompletion(response, textCompletion(requestBody, result.content), session.stream)
  } finally {
    clearTimeout(timer)
    // Clean up cached images
    for (const key of state.imageCache.keys()) {
      if (key.startsWith(`img_${session.requestId}_`)) state.imageCache.delete(key)
    }
    state.interactiveSessions.delete(session.requestId)
    wsBroadcast({ type: 'session.completed', requestId: session.requestId, status: session.status })
  }
}

function interactiveSessionInfo(session) {
  return {
    requestId: session.requestId,
    userText: session.userText,
    userImages: session.userImages || [],
    sticker: session.sticker || null,
    model: session.model,
    stream: session.stream,
    status: session.status,
    createdAt: session.createdAt,
    ageMs: Date.now() - session.createdAt,
  }
}

function findInteractiveSession(requestId) {
  const session = state.interactiveSessions.get(requestId)
  if (!session) return { session: null, error: { code: 'INTERACTIVE_NOT_FOUND', message: 'Interactive request not found or already completed' } }
  return { session, error: null }
}

async function interactiveAction(payload) {
  const { session, error } = findInteractiveSession(payload.requestId)
  if (error) return { status: 404, body: { ok: false, error } }
  if (session.status !== 'replying') {
    return { status: 409, body: { ok: false, error: { code: 'REPLY_NOT_STARTED', message: 'Call reply_start before using reply actions' } } }
  }

  if (payload.action === 'sticker') {
    return executeTool({
      requestId: `${session.requestId}:sticker`,
      tool: 'sticker.send',
      arguments: { pack: payload.pack, tag: payload.tag },
    })
  }
  if (payload.action === 'tool') {
    return executeTool({
      requestId: `${session.requestId}:tool`,
      tool: payload.tool,
      arguments: payload.arguments || {},
    })
  }
  return { status: 400, body: { ok: false, error: { code: 'UNKNOWN_REPLY_ACTION', message: 'Supported actions: sticker, tool' } } }
}

// ── WebSocket message handler (Dashboard → Server) ──
async function handleWsMessage(socket, msg) {
  try {
    if (msg.type === 'ping') return wsSend(socket, { type: 'pong' })

    if (msg.type === 'reply.start') {
      const { session, error } = findInteractiveSession(msg.requestId)
      if (error) return wsSend(socket, { type: 'error', requestId: msg.requestId, message: error.message })
      if (session.status !== 'pending') return wsSend(socket, { type: 'error', requestId: msg.requestId, message: 'Session already handled' })
      session.status = 'replying'
      session.startedAt = Date.now()
      wsBroadcast({ type: 'session.updated', session: interactiveSessionInfo(session) })
      return
    }

    if (msg.type === 'reply.send') {
      const { session, error } = findInteractiveSession(msg.requestId)
      if (error) return wsSend(socket, { type: 'error', requestId: msg.requestId, message: error.message })
      session.status = 'completed'
      session.resolveReply({ content: msg.content || '' })
      wsBroadcast({ type: 'session.completed', requestId: msg.requestId, status: 'completed' })
      return
    }

    if (msg.type === 'reply.cancel') {
      const { session, error } = findInteractiveSession(msg.requestId)
      if (error) return wsSend(socket, { type: 'error', requestId: msg.requestId, message: error.message })
      session.status = 'cancelled'
      session.resolveReply({ cancelled: true })
      wsBroadcast({ type: 'session.completed', requestId: msg.requestId, status: 'cancelled' })
      return
    }

    if (msg.type === 'reply.cancelAll') {
      let count = 0
      for (const [, s] of state.interactiveSessions) {
        if (s.status === 'pending') { s.status = 'cancelled'; s.resolveReply({ cancelled: true }); count++ }
      }
      wsSend(socket, { type: 'cancelledAll', count })
      return
    }

    if (msg.type === 'reply.action') {
      const { session, error } = findInteractiveSession(msg.requestId)
      if (error) return wsSend(socket, { type: 'error', requestId: msg.requestId, message: error.message })
      if (session.status !== 'replying') return wsSend(socket, { type: 'error', requestId: msg.requestId, message: 'Call reply.start first' })
      if (msg.action === 'sticker') {
        const match = findSticker(msg.pack, msg.tag)
        if (match) session.resolveReply({ content: match.marker })
        else return wsSend(socket, { type: 'error', requestId: msg.requestId, message: 'Sticker not found' })
        return
      }
      return wsSend(socket, { type: 'error', requestId: msg.requestId, message: 'Unknown action: ' + (msg.action || '') })
    }

    // TTS now uses HTTP proxy (GET /api/v1/tts/proxy).
    // WebSocket tts.test route removed — Dashboard plays via <audio src="/api/v1/tts/proxy?...">.
  } catch (e) {
    try { wsSend(socket, { type: 'error', message: e.message }) } catch { /* ignore */ }
  }
}

async function proxyGptSovits(payload, request) {
  if (!allowNetwork) throw new Error('NETWORK_DISABLED')
  if (!gptSovitsUrl) throw new Error('WISP_GPT_SOVITS_URL is not configured')
  const text = payload.text || payload.input
  if (typeof text !== 'string' || !text.trim()) throw new Error('TTS text/input is required')
  const body = {
    ...payload,
    text,
    voice: payload.voice || process.env.WISP_GSV_VOICE || 'default',
    ref_audio_path: payload.ref_audio_path || process.env.WISP_GSV_REF_AUDIO_PATH,
    prompt_text: payload.prompt_text || process.env.WISP_GSV_PROMPT_TEXT,
    prompt_lang: payload.prompt_lang || process.env.WISP_GSV_PROMPT_LANG || 'zh',
    text_lang: payload.text_lang || process.env.WISP_GSV_TEXT_LANG || 'zh',
    media_type: payload.media_type || payload.response_format || process.env.WISP_GSV_MEDIA_TYPE || 'wav',
  }
  for (const key of ['ref_audio_path', 'prompt_text']) if (!body[key]) delete body[key]
  const upstream = await fetch(`${gptSovitsUrl}/tts`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(request.headers.authorization ? { authorization: request.headers.authorization } : {}) },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(Number.parseInt(process.env.WISP_TTS_TIMEOUT_MS || '120000', 10)),
  })
  const audio = Buffer.from(await upstream.arrayBuffer())
  if (!upstream.ok) throw new Error(`GPT-SoVITS HTTP ${upstream.status}: ${audio.toString('utf8').slice(0, 300)}`)
  return { audio, contentType: upstream.headers.get('content-type') || `audio/${body.media_type}` }
}

async function gptSovitsHealth() {
  if (!gptSovitsUrl) return { configured: false, healthy: false }
  try {
    const response = await fetch(`${gptSovitsUrl}/healthz`, { signal: AbortSignal.timeout(3000) })
    return { configured: true, healthy: response.ok, status: response.status, url: gptSovitsUrl }
  } catch (error) {
    return { configured: true, healthy: false, url: gptSovitsUrl, error: error.message }
  }
}

async function gptSovitsSpeakers() {
  if (!gptSovitsUrl) return []
  try {
    const response = await fetch(`${gptSovitsUrl}/speakers`, { signal: AbortSignal.timeout(3000) })
    if (!response.ok) return []
    const body = await response.json().catch(() => [])
    if (Array.isArray(body)) return body
    return Array.isArray(body.voices) ? body.voices : []
  } catch {
    return []
  }
}

async function proxyQwen3Tts(payload, request) {
  if (!allowNetwork) throw new Error('NETWORK_DISABLED')
  if (!qwen3TtsUrl) throw new Error('WISP_QWEN3_TTS_URL is not configured')
  const text = payload.text || payload.input
  if (typeof text !== 'string' || !text.trim()) throw new Error('TTS text/input is required')
  const body = {
    ...payload,
    text,
    mode: payload.mode || qwen3Mode,
    language: payload.language || process.env.WISP_QWEN3_LANGUAGE || 'Chinese',
    speaker: payload.speaker || process.env.WISP_QWEN3_SPEAKER || 'Vivian',
    instruct: payload.instruct || payload.instruction || process.env.WISP_QWEN3_INSTRUCT || '',
    ref_audio: payload.ref_audio || payload.refAudio || qwen3RefAudio,
    ref_text: payload.ref_text || payload.refText || qwen3RefText,
    x_vector_only_mode: payload.x_vector_only_mode ?? payload.xVectorOnlyMode ?? qwen3XVectorOnly,
    response_format: payload.response_format || 'wav',
  }
  const upstream = await fetch(`${qwen3TtsUrl}/tts`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(request.headers.authorization ? { authorization: request.headers.authorization } : {}) },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(Number.parseInt(process.env.WISP_QWEN3_TIMEOUT_MS || '120000', 10)),
  })
  const audio = Buffer.from(await upstream.arrayBuffer())
  if (!upstream.ok) throw new Error(`Qwen3-TTS HTTP ${upstream.status}: ${audio.toString('utf8').slice(0, 300)}`)
  return { audio, contentType: upstream.headers.get('content-type') || `audio/${body.response_format}` }
}

async function qwen3Health() {
  if (!qwen3TtsUrl) return { configured: false, healthy: false }
  try {
    const response = await fetch(`${qwen3TtsUrl}/healthz`, { signal: AbortSignal.timeout(3000) })
    const body = await response.json().catch(() => ({}))
    return { configured: true, healthy: response.ok, status: response.status, url: qwen3TtsUrl, ...body }
  } catch (error) {
    return { configured: true, healthy: false, url: qwen3TtsUrl, error: error.message }
  }
}

async function qwen3Capabilities() {
  if (!qwen3TtsUrl) return { configured: false, supports_voice_clone: false }
  try {
    const response = await fetch(`${qwen3TtsUrl}/capabilities`, { signal: AbortSignal.timeout(3000) })
    const body = await response.json().catch(() => ({}))
    return { configured: true, healthy: response.ok, status: response.status, url: qwen3TtsUrl, ...body }
  } catch (error) {
    return { configured: true, healthy: false, url: qwen3TtsUrl, error: error.message }
  }
}

async function qwen3Speakers() {
  if (!qwen3TtsUrl) return []
  try {
    const response = await fetch(`${qwen3TtsUrl}/speakers`, { signal: AbortSignal.timeout(3000) })
    if (!response.ok) return []
    const body = await response.json().catch(() => [])
    if (Array.isArray(body)) return body
    return Array.isArray(body.speakers) ? body.speakers : []
  } catch {
    return []
  }
}

async function proxyQwen3Clone(request) {
  if (!allowNetwork) throw new Error('NETWORK_DISABLED')
  if (!qwen3TtsUrl) throw new Error('WISP_QWEN3_TTS_URL is not configured')
  const contentType = String(request.headers['content-type'] || '')
  if (!contentType.toLowerCase().startsWith('multipart/form-data')) throw new Error('Expected multipart/form-data')
  const parts = parseMultipart(await readBuffer(request), contentType)
  const filePart = parts.find((part) => (part.name === 'file' || part.name === 'ref_audio') && part.body.length > 0)
  if (!filePart) throw new Error('A reference audio file field is required')
  const fields = Object.fromEntries(parts.filter((part) => part.name && !part.filename).map((part) => [part.name, part.body.toString('utf8')]))
  const form = new FormData()
  form.append('file', new Blob([filePart.body], { type: filePart.contentType || 'application/octet-stream' }), filePart.filename || 'reference.wav')
  form.append('text', fields.text || '')
  form.append('language', fields.language || process.env.WISP_QWEN3_LANGUAGE || 'Chinese')
  form.append('ref_text', fields.ref_text || fields.refText || qwen3RefText)
  form.append('x_vector_only_mode', fields.x_vector_only_mode || fields.xVectorOnlyMode || String(qwen3XVectorOnly))
  const upstream = await fetch(`${qwen3TtsUrl}/clone`, {
    method: 'POST',
    headers: request.headers.authorization ? { authorization: request.headers.authorization } : undefined,
    body: form,
    signal: AbortSignal.timeout(Number.parseInt(process.env.WISP_QWEN3_TIMEOUT_MS || '120000', 10)),
  })
  const audio = Buffer.from(await upstream.arrayBuffer())
  if (!upstream.ok) throw new Error(`Qwen3-TTS clone HTTP ${upstream.status}: ${audio.toString('utf8').slice(0, 300)}`)
  return { audio, contentType: upstream.headers.get('content-type') || 'audio/wav' }
}

async function callMcp(url, name, args, requestId) {
  if (!url) throw new Error('MCP_URL_REQUIRED')
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: requestId, method: 'tools/call', params: { name, arguments: args } }),
    signal: AbortSignal.timeout(10000),
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(`MCP_HTTP_${response.status}`)
  if (body.error) throw new Error(`MCP_${body.error.code || 'ERROR'}: ${body.error.message || 'unknown error'}`)
  return body.result || body
}

async function executeTool(request) {
  const requestId = requestIdOf(request)
  const toolName = request.tool || request.name
  const args = request.arguments && typeof request.arguments === 'object' ? request.arguments : {}
  const started = Date.now()
  if (!toolName) return result(requestId, '', false, null, { code: 'INVALID_ARGUMENTS', message: 'tool is required' }, started)
  if (request.dryRun) return result(requestId, toolName, true, { dryRun: true, arguments: args }, null, started)

  if (toolName === 'sticker' || toolName === 'sticker.send') {
    const pack = args.pack || args.packName || [...state.stickers.keys()][0]
    const match = findSticker(pack, args.tag || args.label)
    if (!match) return result(requestId, toolName, false, null, { code: 'STICKER_NOT_FOUND', message: `No sticker matched ${pack}:${args.tag || args.label || ''}` }, started)
    return result(requestId, toolName, true, { simulated: true, ...match }, null, started)
  }

  if (toolName === 'send_message') {
    if (typeof args.text !== 'string' || !args.text.trim()) return result(requestId, toolName, false, null, { code: 'INVALID_ARGUMENTS', message: 'send_message requires text' }, started)
    return result(requestId, toolName, true, { simulated: true, accepted: true, chat: args.chat || '系统通知', text: args.text }, null, started)
  }

  if (toolName === 'time') {
    const now = new Date()
    return result(requestId, toolName, true, {
      simulated: false,
      iso: now.toISOString(),
      local: now.toLocaleString('zh-CN'),
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    }, null, started)
  }

  if (toolName === 'calculator') {
    const expression = String(args.expr || '').trim()
    if (!expression || !/^[0-9+\-*/%().\s^]+$/.test(expression)) {
      return result(requestId, toolName, false, null, { code: 'INVALID_ARGUMENTS', message: 'calculator only accepts arithmetic expressions' }, started)
    }
    try {
      const value = Function(`"use strict"; return (${expression.replace(/\^/g, '**')})`)()
      if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('result is not finite')
      return result(requestId, toolName, true, { simulated: false, expression, value }, null, started)
    } catch (error) {
      return result(requestId, toolName, false, null, { code: 'CALCULATION_FAILED', message: error.message }, started)
    }
  }

  if (toolName === 'mcp.call') {
    try {
      const value = await callMcp(args.url || args.server || process.env.WISP_MCP_URL, args.tool, args.arguments || {}, requestId)
      return result(requestId, toolName, true, { simulated: false, response: value }, null, started)
    } catch (error) {
      return result(requestId, toolName, false, null, { code: 'MCP_CALL_FAILED', message: error.message }, started, 502)
    }
  }

  const tool = state.tools.get(toolName)
  if (!tool) return result(requestId, toolName, false, null, { code: 'UNKNOWN_TOOL', message: 'Use GET /api/v1/tools to list Wisp tools' }, started)
  if (tool.kind === 'builtin') {
    return result(requestId, toolName, true, { simulated: true, tool: toolName, arguments: args }, null, started)
  }
  if (tool.impl?.type === 'http') {
    try {
      const value = await executeHttp(tool, args)
      return result(requestId, toolName, true, { simulated: false, output: value }, null, started)
    } catch (error) {
      return result(requestId, toolName, false, null, { code: 'HTTP_TOOL_FAILED', message: error.message }, started, 502)
    }
  }
  return result(requestId, toolName, false, null, { code: 'NOT_SUPPORTED_ON_DESKTOP', message: `Wisp implementation type ${tool.impl?.type || 'none'} cannot run in the Node debugger` }, started)
}

function toolList() {
  return [...state.tools.values()].map(({ name, description, inputSchema, kind, packageName }) => ({ name, description, inputSchema, kind, ...(packageName ? { package: packageName } : {}) }))
}

function shortcutArguments(payload) {
  if (payload.arguments && typeof payload.arguments === 'object' && !Array.isArray(payload.arguments)) {
    return payload.arguments
  }
  const { tool: _tool, requestId: _requestId, dryRun: _dryRun, ...argumentsPayload } = payload
  return argumentsPayload
}

function mcpShortcutArguments(payload) {
  const nested = payload.arguments
  if (nested && typeof nested === 'object' && !Array.isArray(nested) &&
      (nested.url || nested.server || nested.tool || nested.arguments)) {
    return nested
  }
  return payload
}

async function handle(request, response) {
  if (request.method === 'OPTIONS') return jsonResponse(response, 204, {})
  const url = new URL(request.url || '/', `http://${request.headers.host || `${host}:${port}`}`)

  // Dashboard HTML — serve single-page app
  if (request.method === 'GET' && (url.pathname === '/dashboard' || url.pathname === '/dashboard/')) {
    try {
      const html = await fs.readFile(path.join(root, 'scripts', 'wisp-dashboard.html'), 'utf8')
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'content-length': Buffer.byteLength(html) })
      return response.end(html)
    } catch {
      return jsonResponse(response, 404, { error: { message: 'Dashboard page not found. Create scripts/wisp-dashboard.html' } })
    }
  }

  // Compatibility routes used by the Android Remote GPT-SoVITS provider.
  if (request.method === 'GET' && url.pathname === '/healthz') {
    const health = await gptSovitsHealth()
    return jsonResponse(response, health.healthy ? 200 : 503, {
      ok: health.healthy,
      service: 'wisp-dev-server',
      ttsProxy: health.healthy,
      upstream: health,
    })
  }
  if (request.method === 'GET' && url.pathname === '/speakers') {
    return jsonResponse(response, 200, await gptSovitsSpeakers())
  }
  if (request.method === 'GET' && url.pathname === '/qwen3/healthz') {
    const health = await qwen3Health()
    return jsonResponse(response, health.healthy ? 200 : 503, { ok: health.healthy, service: 'wisp-dev-server', ttsProxy: health.healthy, upstream: health })
  }
  if (request.method === 'GET' && url.pathname === '/qwen3/speakers') {
    return jsonResponse(response, 200, await qwen3Speakers())
  }
  if (request.method === 'GET' && url.pathname === '/qwen3/capabilities') {
    const capabilities = await qwen3Capabilities()
    return jsonResponse(response, capabilities.healthy === false ? 503 : 200, capabilities)
  }

  if (request.method === 'GET' && url.pathname === '/api/v1/health') {
    const [tts, qwen3Tts] = await Promise.all([gptSovitsHealth(), qwen3Health()])
    return jsonResponse(response, 200, {
      ok: true,
      service: 'wisp-dev-server',
      standalone: true,
      bind: host,
      port,
      uptimeMs: Date.now() - startedAt,
      network: allowNetwork,
      aiApi: true,
      sttApi: true,
      ttsProxy: (tts.configured && tts.healthy) || (qwen3Tts.configured && qwen3Tts.healthy),
      scenario: defaultScenario,
      interactive: interactiveMode,
      interactiveTimeoutMs,
      tts,
      qwen3Tts,
    })
  }
  if (!authorized(request)) return jsonResponse(response, 401, { ok: false, error: { code: 'UNAUTHORIZED', message: 'Use Authorization: Bearer <WISP_API_KEY>' } })
  if (request.method === 'GET' && url.pathname === '/api/v1/debug/inbox') {
    return jsonResponse(response, 200, {
      interactive: interactiveMode,
      sessions: [...state.interactiveSessions.values()].map(interactiveSessionInfo),
    })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/debug/affect') {
    const requestedChat = shortText(url.searchParams.get('chatName'), 120)
    // P2.4c: 支持时间范围查询（since/until），用于事件回放
    const sinceParam = url.searchParams.get('since')
    const untilParam = url.searchParams.get('until')
    const sinceTs = sinceParam ? new Date(sinceParam).getTime() : 0
    const untilTs = untilParam ? new Date(untilParam).getTime() : Number.MAX_SAFE_INTEGER
    const events = state.affectEvents.filter(event => {
      if (requestedChat && event.chatName !== requestedChat) return false
      const eventTs = new Date(event.receivedAt).getTime()
      return eventTs >= sinceTs && eventTs <= untilTs
    })
    const snapshots = [...state.affectSnapshots.values()].filter(snapshot => {
      if (requestedChat && snapshot.chatName !== requestedChat) return false
      const snapTs = new Date(snapshot.receivedAt).getTime()
      return snapTs >= sinceTs && snapTs <= untilTs
    })
    // P1.5: 返回保留期限信息，便于 Dashboard / 调用方观察
    return jsonResponse(response, 200, {
      snapshots,
      events,
      updatedAt: new Date().toISOString(),
      retention: {
        ttlMs: state.affectTtlMs,
        ttlHours: state.affectTtlMs / (60 * 60 * 1000),
        lastGcAt: state.affectLastGcAt ? new Date(state.affectLastGcAt).toISOString() : null,
        gcIntervalMs: state.affectGcIntervalMs,
        totalSnapshots: state.affectSnapshots.size,
        totalEvents: state.affectEvents.length,
      },
    })
  }
  // P1.5: 手动触发保留期限 GC（DebugPage/Dashboard 用）
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/affect/gc') {
    state.affectLastGcAt = 0  // 强制下次 maybeGcAffect 执行
    const beforeSnapshots = state.affectSnapshots.size
    const beforeEvents = state.affectEvents.length
    maybeGcAffect()
    return jsonResponse(response, 200, {
      ok: true,
      cleaned: {
        snapshots: beforeSnapshots - state.affectSnapshots.size,
        events: beforeEvents - state.affectEvents.length,
      },
      remaining: {
        snapshots: state.affectSnapshots.size,
        events: state.affectEvents.length,
      },
    })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/debug/voices') {
    return jsonResponse(response, 200, { voices: state.voiceInbox })
  }
  // Voice file download: GET /api/v1/debug/voices/:id/download
  if (request.method === 'GET' && url.pathname.startsWith('/api/v1/debug/voices/') && url.pathname.endsWith('/download')) {
    const voiceId = url.pathname.split('/')[5]
    const voice = state.voiceInbox.find(v => v.id === voiceId)
    if (!voice) return jsonResponse(response, 404, { error: { message: 'Voice not found' } })
    try {
      const data = await fs.readFile(voice.storedPath)
      return rawResponse(response, 200, data, voice.contentType || 'application/octet-stream')
    } catch {
      return jsonResponse(response, 404, { error: { message: 'Voice file missing' } })
    }
  }
  // TTS audio proxy for Dashboard: GET /api/v1/tts/proxy?engine=...&text=...
  if (request.method === 'GET' && url.pathname === '/api/v1/tts/proxy') {
    const engine = url.searchParams.get('engine') || 'gptsovits'
    const text = url.searchParams.get('text') || '你好'
    try {
      let resp
      if (engine === 'qwen3') {
        if (!qwen3TtsUrl) return jsonResponse(response, 503, { error: { message: 'Qwen3-TTS upstream is not configured' } })
        const qwenPayload = {
          text,
          mode: url.searchParams.get('mode') || qwen3Mode,
          language: url.searchParams.get('language') || process.env.WISP_QWEN3_LANGUAGE || 'Chinese',
          speaker: url.searchParams.get('voice') || process.env.WISP_QWEN3_SPEAKER || 'Vivian',
          instruct: url.searchParams.get('instruct') || process.env.WISP_QWEN3_INSTRUCT || '',
          ref_audio: url.searchParams.get('ref_audio') || qwen3RefAudio,
          ref_text: url.searchParams.get('ref_text') || qwen3RefText,
          x_vector_only_mode: url.searchParams.get('x_vector_only_mode') === 'true' || qwen3XVectorOnly,
          response_format: url.searchParams.get('response_format') || 'wav',
        }
        resp = await fetch(`${qwen3TtsUrl}/tts`, {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify(qwenPayload),
          signal: AbortSignal.timeout(Number.parseInt(process.env.WISP_QWEN3_TTS_TIMEOUT_MS || '120000', 10)),
        })
      } else if (engine === 'gptsovits') {
        if (!gptSovitsUrl) return jsonResponse(response, 503, { error: { message: 'GPT-SoVITS upstream is not configured' } })
        const gptPayload = {
          text,
          voice: url.searchParams.get('voice') || process.env.WISP_GSV_VOICE || 'default',
          prompt_lang: url.searchParams.get('prompt_lang') || process.env.WISP_GSV_PROMPT_LANG || 'zh',
          text_lang: url.searchParams.get('text_lang') || url.searchParams.get('language') || process.env.WISP_GSV_TEXT_LANG || 'zh',
          media_type: url.searchParams.get('media_type') || url.searchParams.get('response_format') || process.env.WISP_GSV_MEDIA_TYPE || 'wav',
          ref_audio_path: url.searchParams.get('ref_audio_path') || process.env.WISP_GSV_REF_AUDIO_PATH || '',
          prompt_text: url.searchParams.get('prompt_text') || process.env.WISP_GSV_PROMPT_TEXT || '',
        }
        if (!gptPayload.ref_audio_path) delete gptPayload.ref_audio_path
        if (!gptPayload.prompt_text) delete gptPayload.prompt_text
        resp = await fetch(`${gptSovitsUrl}/tts`, {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify(gptPayload),
          signal: AbortSignal.timeout(Number.parseInt(process.env.WISP_TTS_TIMEOUT_MS || '120000', 10)),
        })
      } else {
        return jsonResponse(response, 400, { error: { message: `Unsupported TTS engine: ${engine}` } })
      }
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const buf = Buffer.from(await resp.arrayBuffer())
      const ct = resp.headers.get('content-type') || 'audio/wav'
      return rawResponse(response, 200, buf, ct)
    } catch (e) {
      return jsonResponse(response, 502, { error: { message: 'TTS proxy failed: ' + e.message } })
    }
  }
  if (request.method === 'GET' && (url.pathname === '/v1/models' || url.pathname === '/models')) {
    return jsonResponse(response, 200, {
      object: 'list',
      data: [{ id: 'wisp-debug', object: 'model', created: Math.floor(startedAt / 1000), owned_by: 'wisp-dev-server' }],
    })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/tools') return jsonResponse(response, 200, { tools: toolList() })
  if (request.method === 'GET' && url.pathname === '/api/v1/stickers') {
    return jsonResponse(response, 200, { packs: [...state.stickers.entries()].map(([pack, entries]) => ({ pack, tags: [...new Set(entries.flatMap((entry) => entry.tags || []))] })) })
  }
  // Sticker image file: GET /api/v1/stickers/:pack/:file
  if (request.method === 'GET' && url.pathname.startsWith('/api/v1/stickers/') && url.pathname.split('/').length === 6) {
    const [, , , , pack, file] = url.pathname.split('/')
    const safeFile = path.basename(file)
    const imgPath = path.join(root, 'app', 'src', 'main', 'assets', 'stickers', pack, safeFile)
    try {
      const data = await fs.readFile(imgPath)
      const ext = path.extname(safeFile).toLowerCase()
      const mime = ext === '.png' ? 'image/png' : ext === '.gif' ? 'image/gif' : ext === '.webp' ? 'image/webp' : 'image/png'
      return rawResponse(response, 200, data, mime)
    } catch {
      return jsonResponse(response, 404, { error: { message: 'Sticker not found' } })
    }
  }
  // Image proxy for Dashboard: GET /api/v1/debug/images/:id
  if (request.method === 'GET' && url.pathname.startsWith('/api/v1/debug/images/')) {
    const imgId = url.pathname.split('/')[5]
    const dataUrl = state.imageCache.get(imgId)
    if (!dataUrl) return jsonResponse(response, 404, { error: { message: 'Image not found or expired' } })
    const match = dataUrl.match(/^data:(image\/\w+);base64,(.+)$/)
    if (!match) return rawResponse(response, 200, Buffer.from(dataUrl, 'utf8'), 'text/plain')
    const buf = Buffer.from(match[2], 'base64')
    return rawResponse(response, 200, buf, match[1])
  }

  let payload = {}
  if (request.method === 'POST') {
    if (url.pathname === '/v1/audio/transcriptions' || url.pathname === '/audio/transcriptions') {
      try {
        return await receiveTranscription(request, response)
      } catch (error) {
        const tooLarge = error.message === 'REQUEST_TOO_LARGE'
        const status = tooLarge ? 413 : error.message === 'MULTIPART_BOUNDARY_MISSING' ? 400 : 500
        return jsonResponse(response, status, { error: { message: tooLarge ? 'Request exceeds the configured body limit' : error.message, type: 'stt_proxy_error' } })
      }
    }
    if (url.pathname === '/qwen3/clone') {
      try {
        const audio = await proxyQwen3Clone(request)
        return rawResponse(response, 200, audio.audio, audio.contentType)
      } catch (error) {
        return jsonResponse(response, 502, { error: { message: error.message, type: 'qwen3_clone_proxy_error' } })
      }
    }
    try { payload = await readJson(request) } catch (error) {
      const tooLarge = error.message === 'REQUEST_TOO_LARGE'
      return jsonResponse(response, tooLarge ? 413 : 400, { ok: false, error: { code: tooLarge ? 'REQUEST_TOO_LARGE' : 'INVALID_JSON', message: tooLarge ? 'Request exceeds 1 MiB' : 'Request must be valid JSON' } })
    }
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/tools/call') {
    const call = await executeTool(payload)
    console.log(JSON.stringify({ event: 'tool.call', requestId: call.body.requestId, tool: call.body.tool, ok: call.body.ok, elapsedMs: call.body.elapsedMs }))
    return jsonResponse(response, call.status, call.body)
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/reply/start') {
    const found = findInteractiveSession(payload.requestId)
    if (found.error) return jsonResponse(response, 404, { ok: false, error: found.error })
    if (found.session.status !== 'pending') {
      return jsonResponse(response, 409, { ok: false, error: { code: 'REPLY_ALREADY_STARTED', message: 'This request is already being handled' } })
    }
    found.session.status = 'replying'
    found.session.startedAt = Date.now()
    return jsonResponse(response, 200, { ok: true, session: interactiveSessionInfo(found.session) })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/affect/snapshot') {
    const result = storeAffectSnapshot(payload)
    return jsonResponse(response, 200, { ok: true, duplicate: result.duplicate, snapshot: result.snapshot })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/affect/clear') {
    const chatName = shortText(payload.chatName, 120)
    if (chatName) {
      state.affectSnapshots.delete(chatName)
      state.affectEvents = state.affectEvents.filter(event => event.chatName !== chatName)
      for (const key of state.affectEventKeys) if (key.startsWith(`${chatName}:`)) state.affectEventKeys.delete(key)
    } else {
      state.affectSnapshots.clear()
      state.affectEvents = []
      state.affectEventKeys.clear()
    }
    wsBroadcast({ type: 'affect.cleared', chatName: chatName || null })
    return jsonResponse(response, 200, { ok: true, chatName: chatName || null })
  }
  // P2.4d: 按时间范围 + 角色删除事件（比 /clear 更细粒度）
  // body: { chatName?, since?, until? }
  // - chatName 为空时删除所有角色
  // - since/until 为 ISO 字符串或时间戳，省略则不限制
  // - 非法时间参数返回 400（避免 NaN 导致全量删除）
  // - 返回删除的 events 数量 + 重建后的 affectEventKeys
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/affect/delete') {
    const chatName = shortText(payload.chatName, 120)
    const sinceParam = payload.since
    const untilParam = payload.until
    // 修复 P1: NaN 校验 — 非法时间参数直接拒绝，避免比较失效导致全量删除
    const sinceTs = sinceParam ? new Date(sinceParam).getTime() : 0
    const untilTs = untilParam ? new Date(untilParam).getTime() : Number.MAX_SAFE_INTEGER
    if (sinceParam && !Number.isFinite(sinceTs)) {
      return jsonResponse(response, 400, { ok: false, error: 'invalid since parameter' })
    }
    if (untilParam && !Number.isFinite(untilTs)) {
      return jsonResponse(response, 400, { ok: false, error: 'invalid until parameter' })
    }
    if (sinceTs > untilTs) {
      return jsonResponse(response, 400, { ok: false, error: 'since > until' })
    }
    const beforeEvents = state.affectEvents.length
    const removedChats = new Set()
    state.affectEvents = state.affectEvents.filter(event => {
      if (chatName && event.chatName !== chatName) return true  // 不匹配角色，保留
      const eventTs = new Date(event.receivedAt).getTime()
      // 修复 P1: eventTs 为 NaN 时保留（不应删除无法解析时间的事件）
      if (!Number.isFinite(eventTs)) return true
      if (eventTs < sinceTs || eventTs > untilTs) return true  // 不在时间范围，保留
      removedChats.add(event.chatName)
      return false  // 匹配删除条件
    })
    // 无条件重建 affectEventKeys（参考 slice 后重建模式）
    state.affectEventKeys = new Set(
      state.affectEvents
        .map(item => `${item.chatName}:${item.eventId}`)
        .filter(item => !item.endsWith(':'))
    )
    const deletedCount = beforeEvents - state.affectEvents.length
    // 修复 P1: 清理 snapshot — chatName 非空时清理指定角色；chatName 为空时（全量删除）清理所有已无事件的角色
    const chatsToCheck = chatName ? [chatName] : [...removedChats]
    for (const chat of chatsToCheck) {
      if (!state.affectEvents.some(e => e.chatName === chat)) {
        state.affectSnapshots.delete(chat)
      }
    }
    // 修复 P1: 广播含 since/until，便于前端精准更新
    wsBroadcast({
      type: 'affect.deleted',
      chatName: chatName || null,
      deletedCount,
      since: sinceParam || null,
      until: untilParam || null,
    })
    return jsonResponse(response, 200, {
      ok: true,
      deletedEvents: deletedCount,
      remainingEvents: state.affectEvents.length,
      affectEventKeysSize: state.affectEventKeys.size,
    })
  }
  // ── P2.1: 主动消息审批队列端点 ──
  // 设计原则：Desktop 端只做"建议列表 + 决策记录"，不做实际发送。
  // 实际发送永远在 Android 端（避免远程操控风险）。
  if (request.method === 'GET' && url.pathname === '/api/v1/proactive/pending') {
    const requestedChat = shortText(url.searchParams.get('chatName'), 120)
    const list = [...state.proactiveQueue.values()]
      .filter(msg => !requestedChat || msg.chatName === requestedChat)
      .sort((a, b) => b.createdAt - a.createdAt)
    return jsonResponse(response, 200, {
      pending: list,
      total: state.proactiveQueue.size,
      retention: {
        ttlMs: state.proactiveTtlMs,
        ttlHours: state.proactiveTtlMs / (60 * 60 * 1000),
        lastGcAt: state.proactiveLastGcAt ? new Date(state.proactiveLastGcAt).toISOString() : null,
      },
    })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/proactive/decisions') {
    // Android 增量拉取：since 参数为时间戳（毫秒）
    const since = Number(url.searchParams.get('since')) || 0
    const decisions = state.proactiveDecisions.filter(d => d.decidedAt > since)
    return jsonResponse(response, 200, {
      decisions,
      total: state.proactiveDecisions.length,
      since,
    })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/pending') {
    // Android 提交一条 pending
    const result = storeProactivePending(payload)
    return jsonResponse(response, result.ok ? 200 : 400, result.ok ? result : { ok: false, error: result.error })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/approve') {
    const id = shortText(payload.id, 120)
    if (!id) return jsonResponse(response, 400, { ok: false, error: 'missing id' })
    const reason = shortText(payload.reason, 300)
    const decision = recordProactiveDecision(id, 'APPROVE', reason)
    return jsonResponse(response, 200, { ok: true, decision })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/reject') {
    const id = shortText(payload.id, 120)
    if (!id) return jsonResponse(response, 400, { ok: false, error: 'missing id' })
    const reason = shortText(payload.reason, 300)
    const decision = recordProactiveDecision(id, 'REJECT', reason)
    return jsonResponse(response, 200, { ok: true, decision })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/cancel') {
    const id = shortText(payload.id, 120)
    if (!id) return jsonResponse(response, 400, { ok: false, error: 'missing id' })
    const reason = shortText(payload.reason, 300)
    const decision = recordProactiveDecision(id, 'CANCEL', reason)
    return jsonResponse(response, 200, { ok: true, decision })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/clear') {
    // 清空 Desktop 端队列（不影响 Android 端队列）
    const beforeQueue = state.proactiveQueue.size
    const beforeDecisions = state.proactiveDecisions.length
    state.proactiveQueue.clear()
    state.proactiveDecisions = []
    state.proactiveDecisionKeys.clear()
    wsBroadcast({ type: 'proactive.cleared' })
    return jsonResponse(response, 200, {
      ok: true,
      cleaned: { queue: beforeQueue, decisions: beforeDecisions },
    })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/proactive/gc') {
    state.proactiveLastGcAt = 0  // 强制下次 maybeGcProactive 执行
    const beforeQueue = state.proactiveQueue.size
    const beforeDecisions = state.proactiveDecisions.length
    maybeGcProactive()
    return jsonResponse(response, 200, {
      ok: true,
      cleaned: {
        queue: beforeQueue - state.proactiveQueue.size,
        decisions: beforeDecisions - state.proactiveDecisions.length,
      },
      remaining: {
        queue: state.proactiveQueue.size,
        decisions: state.proactiveDecisions.length,
      },
    })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/reply/action') {
    const action = await interactiveAction(payload)
    return jsonResponse(response, action.status, action.body)
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/reply/end') {
    const found = findInteractiveSession(payload.requestId)
    if (found.error) return jsonResponse(response, 404, { ok: false, error: found.error })
    if (found.session.status !== 'replying') {
      return jsonResponse(response, 409, { ok: false, error: { code: 'REPLY_NOT_STARTED', message: 'Call reply_start before reply_end' } })
    }
    if (typeof payload.content !== 'string') {
      return jsonResponse(response, 400, { ok: false, error: { code: 'INVALID_REPLY', message: 'content must be a string' } })
    }
    found.session.status = 'completed'
    found.session.resolveReply({ content: payload.content })
    return jsonResponse(response, 200, { ok: true, requestId: found.session.requestId, delivered: true })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/debug/reply/cancel') {
    const found = findInteractiveSession(payload.requestId)
    if (found.error) return jsonResponse(response, 404, { ok: false, error: found.error })
    found.session.status = 'cancelled'
    found.session.resolveReply({ cancelled: true })
    return jsonResponse(response, 200, { ok: true, requestId: found.session.requestId, cancelled: true })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/stickers/send') {
    const call = await executeTool({
      requestId: payload.requestId,
      dryRun: payload.dryRun,
      tool: 'sticker.send',
      arguments: shortcutArguments(payload),
    })
    return jsonResponse(response, call.status, call.body)
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/messages/send') {
    const call = await executeTool({
      requestId: payload.requestId,
      dryRun: payload.dryRun,
      tool: 'send_message',
      arguments: shortcutArguments(payload),
    })
    return jsonResponse(response, call.status, call.body)
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/mcp/call') {
    const call = await executeTool({
      requestId: payload.requestId,
      dryRun: payload.dryRun,
      tool: 'mcp.call',
      arguments: mcpShortcutArguments(payload),
    })
    return jsonResponse(response, call.status, call.body)
  }

  if (request.method === 'POST' && (url.pathname === '/v1/chat/completions' || url.pathname === '/chat/completions')) {
    if (interactiveMode) return waitForInteractiveReply(response, payload)
    return sendChatCompletion(response, payload, request, url)
  }
  if (request.method === 'POST' && (url.pathname === '/v1/audio/speech' || url.pathname === '/audio/speech' || url.pathname === '/tts')) {
    try {
      const audio = await proxyGptSovits(payload, request)
      return rawResponse(response, 200, audio.audio, audio.contentType)
    } catch (error) {
      return jsonResponse(response, 502, { error: { message: error.message, type: 'tts_proxy_error' } })
    }
  }
  if (request.method === 'POST' && url.pathname === '/qwen3/tts') {
    try {
      const audio = await proxyQwen3Tts(payload, request)
      return rawResponse(response, 200, audio.audio, audio.contentType)
    } catch (error) {
      return jsonResponse(response, 502, { error: { message: error.message, type: 'qwen3_tts_proxy_error' } })
    }
  }
  // Wisp's MCPClient accepts both the legacy array and standard object result shapes.
  if (request.method === 'POST' && url.pathname === '/mcp') {
    const rpc = payload
    if (rpc.method === 'tools/list') return jsonResponse(response, 200, { jsonrpc: '2.0', id: rpc.id ?? null, result: { tools: toolList() } })
    if (rpc.method === 'tools/call') {
      const call = await executeTool({ requestId: String(rpc.id ?? randomUUID()), tool: rpc.params?.name, arguments: rpc.params?.arguments || {} })
      return jsonResponse(response, 200, { jsonrpc: '2.0', id: rpc.id ?? null, ...(call.body.ok ? { result: { content: [{ type: 'text', text: JSON.stringify(call.body.result) }] } } : { error: { code: -32000, message: call.body.error.message } }) })
    }
    return jsonResponse(response, 200, { jsonrpc: '2.0', id: rpc.id ?? null, error: { code: -32601, message: 'Method not found' } })
  }
  return jsonResponse(response, 404, { ok: false, error: { code: 'NOT_FOUND', message: 'Route not found' } })
}

const server = http.createServer((request, response) => {
  handle(request, response).catch((error) => {
    console.error(error)
    if (!response.headersSent) jsonResponse(response, 500, { ok: false, error: { code: 'INTERNAL_ERROR', message: error.message } })
    else response.destroy()
  })
})

// ── WebSocket upgrade handler (Dashboard connections) ──
server.on('upgrade', (request, socket) => {
  const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`)
  if (url.pathname !== '/dashboard/ws') { socket.destroy(); return }

  const key = request.headers['sec-websocket-key']
  if (!key) { socket.destroy(); return }

  // Reject invalid dashboard tokens before completing the WebSocket handshake.
  if (dashboardToken && url.searchParams.get('token') !== dashboardToken) {
    socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
    socket.destroy()
    return
  }

  const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
  )

  wsClients.add(socket)
  // Send current state snapshot
  wsSend(socket, {
    type: 'init',
    sessions: [...state.interactiveSessions.values()].map(interactiveSessionInfo),
    voices: state.voiceInbox,
    stickers: [...state.stickers.entries()].map(([pack, entries]) => ({ pack, tags: [...new Set(entries.flatMap(e => e.tags || []))] })),
    tools: toolList(),
    affectSnapshots: [...state.affectSnapshots.values()],
    affectEvents: state.affectEvents,
    interactive: interactiveMode,
    ttsProxy: !!(gptSovitsUrl || qwen3TtsUrl),
  })

  let buffer = Buffer.alloc(0)
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    while (buffer.length >= 2) {
      const opcode = buffer[0] & 0x0f
      if (opcode === 0x8) { wsClients.delete(socket); return } // close
      if (opcode === 0x9) { // ping → pong
        const pong = Buffer.alloc(2); pong[0] = 0x8a; pong[1] = 0
        try { socket.write(pong) } catch { wsClients.delete(socket); return }
        buffer = buffer.slice(2); continue
      }
      if (opcode !== 0x1) { buffer = Buffer.alloc(0); break } // non-text, flush
      const masked = (buffer[1] & 0x80) !== 0
      let len = buffer[1] & 0x7f
      let off = 2
      if (len === 126) { if (buffer.length < 4) break; len = buffer.readUInt16BE(2); off = 4 }
      if (len === 127) { if (buffer.length < 10) break; /* skip huge */ buffer = Buffer.alloc(0); break }
      const frameLen = off + (masked ? 4 : 0) + len
      if (buffer.length < frameLen) break
      const mask = masked ? buffer.slice(off, off + 4) : null
      off += masked ? 4 : 0
      const payload = buffer.slice(off, off + len)
      if (masked) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]
      try {
        const msg = JSON.parse(payload.toString('utf8'))
        handleWsMessage(socket, msg)
      } catch { /* ignore malformed */ }
      buffer = buffer.slice(frameLen)
    }
  })

  socket.on('close', () => wsClients.delete(socket))
  socket.on('error', () => wsClients.delete(socket))
})

server.on('error', (error) => {
  console.error(`Wisp dev server failed to listen on ${host}:${port}: ${error.message}`)
  process.exitCode = 1
})

await loadTools()
await loadStickers()
server.listen(port, host, () => {
  console.log(`Wisp dev server listening at http://${host}:${port}`)
  console.log(`Loaded ${state.tools.size} tools, ${state.stickers.size} sticker packs`)
  console.log(`Voice inbox: ${voiceDir}`)
  console.log(`Network-backed .wsptool execution: ${allowNetwork ? 'enabled' : 'disabled'}`)
})
