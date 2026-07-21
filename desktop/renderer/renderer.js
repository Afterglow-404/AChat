const query = new URLSearchParams(location.search)
const serverBase = (query.get('server') || 'http://127.0.0.1:17890').replace(/\/$/, '')
const dashboardToken = query.get('token') || ''
const wsBase = serverBase.replace(/^http/, 'ws')

const state = {
  ws: null,
  reconnectTimer: null,
  sessions: new Map(),
  voices: [],
  tools: [],
  stickers: [],
  selectedId: null,
  currentView: 'inbox',
  sending: false,
  audioUrl: null,
  engines: {
    gptsovits: { health: null, speakers: [] },
    qwen3: { health: null, speakers: [] },
  },
}

const $ = (selector) => document.querySelector(selector)

function escapeHtml(value) {
  return String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function apiUrl(pathname) { return new URL(pathname, serverBase).toString() }

function apiFetch(pathname, options = {}) {
  const headers = new Headers(options.headers || {})
  if (dashboardToken) headers.set('x-wisp-dashboard-token', dashboardToken)
  return fetch(apiUrl(pathname), { ...options, headers })
}

function setConnection(stateName, label) {
  const element = $('.topbar-status')
  element.classList.toggle('connected', stateName === 'connected')
  element.classList.toggle('offline', stateName === 'offline')
  $('#connectionText').textContent = label
}

function sendWs(message) {
  if (state.ws?.readyState !== WebSocket.OPEN) {
    showToast('服务尚未连接')
    return false
  }
  state.ws.send(JSON.stringify(message))
  return true
}

function connect() {
  if (state.ws && state.ws.readyState <= WebSocket.OPEN) return
  const suffix = dashboardToken ? `?token=${encodeURIComponent(dashboardToken)}` : ''
  setConnection('connecting', '连接中')
  state.ws = new WebSocket(`${wsBase}/dashboard/ws${suffix}`)
  state.ws.onopen = () => {
    setConnection('connected', '已连接')
    if (state.reconnectTimer) clearTimeout(state.reconnectTimer)
    state.reconnectTimer = null
  }
  state.ws.onclose = () => {
    setConnection('offline', '服务断开')
    if (!state.reconnectTimer) state.reconnectTimer = setTimeout(() => { state.reconnectTimer = null; connect() }, 3000)
  }
  state.ws.onerror = () => state.ws.close()
  state.ws.onmessage = (event) => {
    try { handleMessage(JSON.parse(event.data)) } catch { showToast('收到无法识别的服务消息') }
  }
}

function handleMessage(message) {
  if (message.type === 'init') {
    state.sessions = new Map((message.sessions || []).map(session => [session.requestId, session]))
    state.voices = message.voices || []
    state.tools = message.tools || []
    state.stickers = message.stickers || []
    renderAll()
    refreshVoiceBridge()
    return
  }
  if (message.type === 'session.new' || message.type === 'session.updated') {
    state.sessions.set(message.session.requestId, message.session)
    renderAll()
    return
  }
  if (message.type === 'session.completed') {
    state.sessions.delete(message.requestId)
    if (state.selectedId === message.requestId) clearSelection()
    renderAll()
    if (message.status === 'cancelled') showToast('请求已取消')
    if (message.status === 'timeout') showToast('请求已超时')
    return
  }
  if (message.type === 'voice.new') {
    state.voices = [message.voice, ...state.voices.filter(voice => voice.id !== message.voice.id)].slice(0, 100)
    renderAll()
    return
  }
  if (message.type === 'cancelledAll') {
    showToast(`已忽略 ${message.count} 个请求`)
    return
  }
  if (message.type === 'error') showToast(message.message || '服务发生错误')
}

function renderAll() {
  $('#pendingBadge').textContent = state.sessions.size
  $('#pendingCountLabel').textContent = state.sessions.size
  $('#voiceBadge').textContent = state.voices.length
  $('#voiceMetric').textContent = state.voices.length
  $('#requestCount').textContent = state.currentView === 'inbox' ? state.sessions.size : state.currentView === 'voices' ? state.voices.length : state.tools.length
  renderTools()
  renderStickers()
  if (state.currentView === 'inbox') renderInbox()
  if (state.currentView === 'voices') renderVoiceView()
  if (state.currentView === 'tools') renderToolView()
  if (state.currentView === 'settings') renderSettingsView()
}

function renderInbox() {
  const list = $('#requestList')
  if (!state.sessions.size) {
    list.innerHTML = '<div class="empty-state"><strong>没有待处理请求</strong><small>从手机端发送一条消息开始</small></div>'
    renderReplySurface()
    return
  }
  const sorted = [...state.sessions.values()].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
  list.innerHTML = sorted.map(session => `<button class="request-card ${session.requestId === state.selectedId ? 'selected' : ''}" data-request-id="${escapeHtml(session.requestId)}"><div class="request-text">${escapeHtml(session.userText || (session.sticker ? '收到一条贴纸消息' : '(空消息)'))}</div><div class="request-meta"><span>${formatAge(session.createdAt)}</span><span class="request-status">${session.status === 'replying' ? '回复中' : '待处理'}</span></div></button>`).join('')
  list.querySelectorAll('[data-request-id]').forEach(card => card.addEventListener('click', () => selectSession(card.dataset.requestId)))
  renderReplySurface()
}

function renderReplySurface() {
  const session = state.selectedId ? state.sessions.get(state.selectedId) : null
  if (!session) {
    $('#selectedTitle').textContent = '选择一个请求'
    $('#selectedMeta').textContent = '回复内容会发送到手机端'
    $('#selectedStatus').textContent = '未选择'
    $('#incomingBox').innerHTML = '<div class="message-kicker">PHONE MESSAGE</div><div class="message-content">从左侧选择一条请求</div>'
    setComposerEnabled(false)
    return
  }
  $('#selectedTitle').textContent = '回复手机消息'
  $('#selectedMeta').textContent = session.requestId
  $('#selectedStatus').textContent = session.status === 'replying' ? '回复中' : '待处理'
  $('#incomingBox').innerHTML = `<div class="message-kicker">PHONE MESSAGE</div><div class="message-content">${escapeHtml(session.userText || (session.sticker ? '收到一条贴纸消息' : '(空消息)'))}</div>`
  setComposerEnabled(true)
}

function setComposerEnabled(enabled) {
  $('#replyInput').disabled = !enabled
  $('#sendButton').disabled = !enabled
  $('#stickerButton').disabled = !enabled
  $('#cancelButton').disabled = !enabled
}

function renderVoiceView() {
  clearSelection()
  $('#selectedTitle').textContent = '语音收件箱'
  $('#selectedMeta').textContent = '点击条目播放录音'
  $('#selectedStatus').textContent = `${state.voices.length} 条`
  $('#incomingBox').innerHTML = '<div class="message-kicker">VOICE INBOX</div><div class="message-content">手机上传的录音和 STT 结果会集中显示在这里。</div>'
  setComposerEnabled(false)
  $('#requestList').innerHTML = state.voices.length ? `<div class="voice-list">${state.voices.slice(0, 50).map(voice => `<button class="voice-card" data-voice-id="${escapeHtml(voice.id)}"><strong>${escapeHtml(voice.text || voice.filename || '语音消息')}</strong><small>${formatTime(voice.receivedAt)} · ${formatBytes(voice.size)}</small></button>`).join('')}</div>` : '<div class="empty-state"><strong>还没有语音消息</strong><small>从手机端发送一条语音开始测试</small></div>'
  $('#requestList').querySelectorAll('[data-voice-id]').forEach(card => card.addEventListener('click', () => playVoice(card.dataset.voiceId)))
}

function renderToolView() {
  clearSelection()
  $('#selectedTitle').textContent = '工具中心'
  $('#selectedMeta').textContent = '选择手机请求后可调用工具'
  $('#selectedStatus').textContent = `${state.tools.length} 个工具`
  $('#incomingBox').innerHTML = '<div class="message-kicker">TOOL CENTER</div><div class="message-content">工具调用结果会返回当前回复会话。</div>'
  setComposerEnabled(false)
  $('#requestList').innerHTML = state.tools.length ? `<div class="voice-list">${state.tools.map(tool => `<button class="voice-card" data-tool-name="${escapeHtml(tool.name)}"><strong>${escapeHtml(tool.name)}</strong><small>${escapeHtml(tool.description || '无描述')}</small></button>`).join('')}</div>` : '<div class="empty-state"><strong>没有可用工具</strong><small>服务尚未同步工具列表</small></div>'
  $('#requestList').querySelectorAll('[data-tool-name]').forEach(card => card.addEventListener('click', () => callTool(card.dataset.toolName)))
}

function renderSettingsView() {
  clearSelection()
  $('#selectedTitle').textContent = '服务设置'
  $('#selectedMeta').textContent = '桌面端自动管理本机 Wisp 服务'
  $('#selectedStatus').textContent = 'LOCAL'
  $('#incomingBox').innerHTML = `<div class="message-kicker">SERVICE</div><div class="message-content">服务地址：${escapeHtml(serverBase)}\n交互模式：已开启\nToken：仅保存在当前桌面会话内</div>`
  setComposerEnabled(false)
  $('#requestList').innerHTML = '<div class="empty-state"><strong>服务由桌面端自动启动</strong><small>点击右上角按钮重启 Wisp 服务</small></div>'
}

function renderTools() {
  const list = $('#toolMiniList')
  const active = Boolean(state.selectedId && state.sessions.get(state.selectedId)?.status === 'replying')
  list.innerHTML = state.tools.length ? state.tools.slice(0, 6).map(tool => `<button class="tool-button" data-mini-tool="${escapeHtml(tool.name)}" ${active ? '' : 'disabled'}>${escapeHtml(tool.name)}</button>`).join('') : '<span class="muted">等待服务同步工具…</span>'
  list.querySelectorAll('[data-mini-tool]').forEach(button => button.addEventListener('click', () => callTool(button.dataset.miniTool)))
}

function renderStickers() {
  const list = $('#stickerMiniList')
  const active = Boolean(state.selectedId && state.sessions.get(state.selectedId)?.status === 'replying')
  const entries = state.stickers.flatMap(pack => (pack.tags || []).map(tag => ({ pack: pack.pack, tag })))
  list.innerHTML = entries.length ? entries.slice(0, 8).map(item => `<button class="sticker-chip" data-sticker-pack="${escapeHtml(item.pack)}" data-sticker-tag="${escapeHtml(item.tag)}" ${active ? '' : 'disabled'}>${escapeHtml(item.tag)}</button>`).join('') : '<span class="muted">服务尚未同步贴纸</span>'
  list.querySelectorAll('[data-sticker-tag]').forEach(button => button.addEventListener('click', () => sendStickerChoice(button.dataset.stickerPack, button.dataset.stickerTag)))
}

function selectSession(id) {
  state.currentView = 'inbox'
  state.selectedId = id
  updateNav()
  const session = state.sessions.get(id)
  if (!session) return
  if (session.status === 'pending' && sendWs({ type: 'reply.start', requestId: id })) session.status = 'replying'
  state.sessions.set(id, session)
  renderAll()
  $('#replyInput').value = ''
  $('#replyInput').focus()
}

function clearSelection() {
  state.selectedId = null
  state.sending = false
  if (state.currentView === 'inbox') $('#replyInput').value = ''
}

function sendReply() {
  if (!state.selectedId || state.sending) return
  const content = $('#replyInput').value.trim()
  if (!content) return showToast('请输入回复内容')
  state.sending = true
  $('#sendButton').disabled = true
  if (!sendWs({ type: 'reply.send', requestId: state.selectedId, content })) {
    state.sending = false
    $('#sendButton').disabled = false
  }
}

function cancelReply() {
  if (!state.selectedId) return
  sendWs({ type: 'reply.cancel', requestId: state.selectedId })
  clearSelection()
  renderAll()
}

function sendStickerChoice(pack, tag) {
  if (!state.selectedId || state.sending) return
  const session = state.sessions.get(state.selectedId)
  if (!session || session.status !== 'replying') return showToast('请先选择正在回复的请求')
  state.sending = true
  $('#sendButton').disabled = true
  if (!sendWs({ type: 'reply.send', requestId: state.selectedId, content: `【sticker:${pack}:${tag}】` })) {
    state.sending = false
    $('#sendButton').disabled = false
  }
}

async function callTool(name) {
  if (!state.selectedId) return showToast('先选择一条手机请求')
  const session = state.sessions.get(state.selectedId)
  if (!session || session.status !== 'replying') return showToast('请先开始回复')
  const descriptor = state.tools.find(tool => tool.name === name)
  const required = descriptor?.inputSchema?.required || []
  let args = {}
  if (required.length) {
    const raw = window.prompt(`${name} 参数 JSON`, '{}')
    if (raw === null) return
    try { args = JSON.parse(raw) } catch { return showToast('参数不是有效 JSON') }
  }
  try {
    const response = await apiFetch('/api/v1/debug/reply/action', { method: 'POST', body: JSON.stringify({ requestId: state.selectedId, action: 'tool', tool: name, arguments: args }), headers: { 'content-type': 'application/json' } })
    const body = await response.json()
    if (!response.ok) throw new Error(body.error?.message || `HTTP ${response.status}`)
    showToast(`${name} 执行完成`)
  } catch (error) { showToast(`工具失败: ${error.message}`) }
}

async function playVoice(id) {
  const voice = state.voices.find(item => item.id === id)
  try {
    const response = await apiFetch(`/api/v1/debug/voices/${encodeURIComponent(id)}/download`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    if (state.audioUrl) URL.revokeObjectURL(state.audioUrl)
    state.audioUrl = URL.createObjectURL(await response.blob())
    $('#audioPlayer').src = state.audioUrl
    await $('#audioPlayer').play()
  } catch (error) { showToast(`语音播放失败: ${error.message}`) }
  if (voice) showToast(voice.text || '正在播放语音')
}

function languageCode(language) {
  return { Chinese: 'zh', English: 'en', Japanese: 'ja', Korean: 'ko', Auto: 'auto' }[language] || 'zh'
}

async function testTts() {
  const text = $('#ttsText').value.trim()
  if (!text) return showToast('请输入试听文本')
  const engine = $('#ttsEngine').value
  const params = new URLSearchParams({ engine, text, voice: $('#ttsVoice').value, language: $('#ttsLanguage').value, instruct: $('#ttsInstruct').value.trim() })
  if (engine === 'gptsovits') params.set('text_lang', languageCode($('#ttsLanguage').value))
  const button = $('#ttsButton')
  button.disabled = true
  $('#ttsStatus').textContent = '生成中…'
  try {
    const response = await apiFetch(`/api/v1/tts/proxy?${params.toString()}`)
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.error?.message || `HTTP ${response.status}`) }
    if (state.audioUrl) URL.revokeObjectURL(state.audioUrl)
    state.audioUrl = URL.createObjectURL(await response.blob())
    $('#audioPlayer').src = state.audioUrl
    await $('#audioPlayer').play()
    $('#ttsStatus').textContent = '试听成功'
  } catch (error) {
    $('#ttsStatus').textContent = '试听失败'
    showToast(`TTS 失败: ${error.message}`)
  } finally { button.disabled = false }
}

async function readSpeakers(pathname) {
  const response = await apiFetch(pathname)
  if (!response.ok) return []
  const body = await response.json().catch(() => [])
  const values = Array.isArray(body) ? body : body.speakers || body.voices || []
  return values.map(item => typeof item === 'string' ? item : item.name || item.id || item.voice).filter(Boolean)
}

async function refreshVoiceBridge() {
  $('#voiceBridgeStatus').textContent = '正在检测引擎…'
  try {
    const response = await apiFetch('/api/v1/health')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const health = await response.json()
    state.engines.gptsovits.health = health.tts || { configured: false, healthy: false }
    state.engines.qwen3.health = health.qwen3Tts || { configured: false, healthy: false }
    const [gptSpeakers, qwenSpeakers] = await Promise.all([readSpeakers('/speakers'), readSpeakers('/qwen3/speakers')])
    state.engines.gptsovits.speakers = gptSpeakers
    state.engines.qwen3.speakers = qwenSpeakers
    renderEnginePanel()
  } catch (error) {
    $('#voiceBridgeStatus').textContent = `检测失败：${error.message}`
    renderEnginePanel()
  }
}

function renderEnginePanel() {
  const entries = [['gptsovits', 'GPT-SoVITS'], ['qwen3', 'Qwen3-TTS']]
  const ready = entries.filter(([key]) => state.engines[key].health?.healthy).length
  $('#voiceBridgeStatus').textContent = ready ? `${ready}/2 个引擎在线，可直接试听` : '没有检测到可用的语音引擎'
  $('#engineStatusList').innerHTML = entries.map(([key, label]) => {
    const health = state.engines[key].health
    const status = health?.healthy ? 'ready' : health?.configured ? 'error' : ''
    const text = health?.healthy ? '在线' : health?.configured ? '不可用' : '未配置'
    return `<div class="engine-row ${status}"><i></i><b>${label}</b><small>${text}</small></div>`
  }).join('')
  const engine = $('#ttsEngine').value
  const select = $('#ttsVoice')
  const current = select.value
  const speakers = state.engines[engine]?.speakers || []
  const values = speakers.length ? speakers : [engine === 'qwen3' ? 'Vivian' : 'default']
  select.innerHTML = values.map(voice => `<option value="${escapeHtml(voice)}">${escapeHtml(voice)}</option>`).join('')
  if (values.includes(current)) select.value = current
  $('#bridgeAddress').textContent = serverBase
}

function setView(view) {
  state.currentView = view
  if (view !== 'inbox') clearSelection()
  updateNav()
  renderAll()
}

function updateNav() { document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === state.currentView)) }
function formatAge(timestamp) { const seconds = Math.max(0, Math.floor((Date.now() - (timestamp || Date.now())) / 1000)); return seconds < 60 ? `${seconds}s 前` : `${Math.floor(seconds / 60)}m 前` }
function formatTime(timestamp) { return timestamp ? new Date(timestamp).toLocaleTimeString() : '' }
function formatBytes(size = 0) { return `${(size / 1024).toFixed(1)}KB` }

let toastTimer = null
function showToast(message) { const toast = $('#toast'); toast.textContent = message; toast.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.classList.remove('show'), 2600) }

document.querySelectorAll('.nav-item').forEach(item => item.addEventListener('click', () => setView(item.dataset.view)))
document.querySelectorAll('.link-button[data-view]').forEach(item => item.addEventListener('click', () => setView(item.dataset.view)))
document.querySelectorAll('[data-quick]').forEach(item => item.addEventListener('click', () => { $('#replyInput').value = item.dataset.quick; $('#replyInput').dispatchEvent(new Event('input')) }))
$('#sendButton').addEventListener('click', sendReply)
$('#stickerButton').addEventListener('click', () => { const first = state.stickers.flatMap(pack => (pack.tags || []).map(tag => ({ pack: pack.pack, tag })))[0]; if (first) sendStickerChoice(first.pack, first.tag); else showToast('没有可用的贴纸') })
$('#cancelButton').addEventListener('click', cancelReply)
$('#cancelAllButton').addEventListener('click', () => { if (confirm('确定忽略全部待处理请求吗？')) sendWs({ type: 'reply.cancelAll' }) })
$('#ttsButton').addEventListener('click', testTts)
$('#voiceReloadButton').addEventListener('click', refreshVoiceBridge)
$('#ttsEngine').addEventListener('change', renderEnginePanel)
$('#restartButton').addEventListener('click', async () => { showToast('正在重启服务…'); try { await window.wispDesktop.restartServer(); showToast('Wisp 服务已重启') } catch (error) { showToast(`重启失败: ${error.message}`) } })
$('#replyInput').addEventListener('input', () => { $('#draftCount').textContent = `${$('#replyInput').value.length} 字` })
$('#replyInput').addEventListener('keydown', event => { if (event.key === 'Enter' && event.ctrlKey) { event.preventDefault(); sendReply() } })
window.wispDesktop?.onServerExit(() => setConnection('offline', '服务已停止'))
if (window.wispDesktop?.getStatus) window.wispDesktop.getStatus().then(status => { $('#portText').textContent = `127.0.0.1:${status.port}`; $('#bridgeAddress').textContent = status.serverUrl; refreshVoiceBridge() })
renderEnginePanel()
connect()
