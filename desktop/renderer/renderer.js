const query = new URLSearchParams(location.search)
const serverBase = (query.get('server') || 'http://127.0.0.1:17890').replace(/\/$/, '')
const dashboardToken = query.get('token') || ''
const wsBase = serverBase.replace(/^http/, 'ws')

let ws = null
let reconnectTimer = null
let sessions = new Map()
let voices = []
let tools = []
let stickers = []
let selectedId = null
let currentView = 'inbox'
let sending = false
let audioUrl = null

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

function sendWs(message) {
  if (ws?.readyState !== WebSocket.OPEN) {
    showToast('服务尚未连接')
    return false
  }
  ws.send(JSON.stringify(message))
  return true
}

function setConnection(state, label) {
  const el = $('.connection-state')
  el.classList.toggle('connected', state === 'connected')
  el.classList.toggle('offline', state === 'offline')
  $('#connectionText').textContent = label
}

function connect() {
  if (ws && ws.readyState <= WebSocket.OPEN) return
  const suffix = dashboardToken ? `?token=${encodeURIComponent(dashboardToken)}` : ''
  setConnection('connecting', '连接中')
  ws = new WebSocket(`${wsBase}/dashboard/ws${suffix}`)
  ws.onopen = () => {
    setConnection('connected', '已连接')
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  ws.onclose = () => {
    setConnection('offline', '服务断开')
    if (!reconnectTimer) reconnectTimer = setTimeout(() => { reconnectTimer = null; connect() }, 3000)
  }
  ws.onerror = () => ws.close()
  ws.onmessage = (event) => {
    try { handleMessage(JSON.parse(event.data)) } catch { showToast('收到无法识别的服务消息') }
  }
}

function handleMessage(message) {
  if (message.type === 'init') {
    sessions = new Map((message.sessions || []).map(session => [session.requestId, session]))
    voices = message.voices || []
    tools = message.tools || []
    stickers = message.stickers || []
    renderEverything()
    $('#ttsStatus').textContent = message.ttsProxy ? '已配置' : '未配置'
    $('#ttsStatus').style.color = message.ttsProxy ? 'var(--teal)' : 'var(--orange)'
    return
  }
  if (message.type === 'session.new' || message.type === 'session.updated') {
    sessions.set(message.session.requestId, message.session)
    renderEverything()
    return
  }
  if (message.type === 'session.completed') {
    sessions.delete(message.requestId)
    if (selectedId === message.requestId) clearSelection()
    renderEverything()
    if (message.status === 'cancelled') showToast('请求已取消')
    if (message.status === 'timeout') showToast('请求已超时')
    return
  }
  if (message.type === 'voice.new') {
    voices = [message.voice, ...voices.filter(voice => voice.id !== message.voice.id)].slice(0, 100)
    renderEverything()
    return
  }
  if (message.type === 'cancelledAll') {
    showToast(`已忽略 ${message.count} 个请求`)
    return
  }
  if (message.type === 'error') showToast(message.message || '服务发生错误')
}

function renderEverything() {
  $('#pendingBadge').textContent = sessions.size
  $('#pendingCountLabel').textContent = sessions.size
  $('#voiceBadge').textContent = voices.length
  $('#voiceMetric').textContent = voices.length
  $('#requestCount').textContent = currentView === 'inbox' ? sessions.size : currentView === 'voices' ? voices.length : tools.length
  renderTools(tools)
  renderStickers(stickers)
  if (currentView === 'inbox') renderInbox()
  if (currentView === 'voices') renderVoiceView()
  if (currentView === 'tools') renderToolView()
  if (currentView === 'settings') renderSettingsView()
}

function renderInbox() {
  const list = $('#requestList')
  if (!sessions.size) {
    list.innerHTML = '<div class="empty-state"><span>◌</span><strong>还没有待回复消息</strong><small>从手机端发送一条消息开始测试</small></div>'
    return
  }
  const sorted = [...sessions.values()].sort((a, b) => b.createdAt - a.createdAt)
  list.innerHTML = sorted.map(session => `<button class="request-card ${session.requestId === selectedId ? 'selected' : ''}" data-request-id="${escapeHtml(session.requestId)}">
    <div class="request-text">${escapeHtml(session.userText || (session.sticker ? '收到一条贴纸消息' : '(空消息)'))}</div>
    <div class="request-meta"><span>${formatAge(session.createdAt)}</span><span class="request-status">${session.status === 'replying' ? '回复中' : '待处理'}</span></div>
  </button>`).join('')
  list.querySelectorAll('[data-request-id]').forEach(card => card.addEventListener('click', () => selectSession(card.dataset.requestId)))
}

function renderVoiceView() {
  $('#selectedTitle').textContent = '语音收件箱'
  $('#selectedMeta').textContent = '点击条目播放，右键下载'
  $('#selectedStatus').textContent = `${voices.length} 条`
  $('#incomingBox').innerHTML = '<div class="incoming-label">VOICE INBOX</div><div class="incoming-content">手机端上传的录音和 STT 结果会集中显示在这里。</div>'
  $('#replyInput').disabled = true
  $('#sendButton').disabled = true
  $('#stickerButton').disabled = true
  $('#cancelButton').disabled = true
  $('#requestList').innerHTML = voices.length ? `<div class="voice-list">${voices.slice(0, 50).map(voice => `<button class="voice-card" data-voice-id="${escapeHtml(voice.id)}"><strong>${escapeHtml(voice.text || voice.filename || '语音消息')}</strong><small>${formatTime(voice.receivedAt)} · ${formatBytes(voice.size)}</small></button>`).join('')}</div>` : '<div class="empty-state"><span>◉</span><strong>还没有语音消息</strong><small>从手机端发送一条语音开始测试</small></div>'
  $('#requestList').querySelectorAll('[data-voice-id]').forEach(card => card.addEventListener('click', () => playVoice(card.dataset.voiceId)))
}

function renderToolView() {
  $('#selectedTitle').textContent = '工具中心'
  $('#selectedMeta').textContent = '回复进行中时可以调用工具'
  $('#selectedStatus').textContent = `${tools.length} 个工具`
  $('#incomingBox').innerHTML = '<div class="incoming-label">TOOL CALLS</div><div class="incoming-content">先从左侧选择一个手机请求，再调用工具。工具结果会返回到当前调试会话。</div>'
  $('#replyInput').disabled = true
  $('#sendButton').disabled = true
  $('#stickerButton').disabled = true
  $('#cancelButton').disabled = true
  $('#requestList').innerHTML = tools.length ? `<div class="voice-list">${tools.map(tool => `<button class="voice-card" data-tool-name="${escapeHtml(tool.name)}"><strong>${escapeHtml(tool.name)}</strong><small>${escapeHtml(tool.description || '无描述')}</small></button>`).join('')}</div>` : '<div class="empty-state"><span>⌘</span><strong>没有可用工具</strong><small>服务尚未同步工具列表</small></div>'
  $('#requestList').querySelectorAll('[data-tool-name]').forEach(card => card.addEventListener('click', () => callTool(card.dataset.toolName)))
}

function renderSettingsView() {
  $('#selectedTitle').textContent = '服务设置'
  $('#selectedMeta').textContent = '桌面端会自动管理本机 Wisp 服务'
  $('#selectedStatus').textContent = 'LOCAL'
  $('#incomingBox').innerHTML = `<div class="incoming-label">SERVICE</div><div class="incoming-content">服务地址：${escapeHtml(serverBase)}\n交互模式：已开启\nToken：仅保存在当前桌面会话内</div>`
  $('#replyInput').disabled = true
  $('#sendButton').disabled = true
  $('#stickerButton').disabled = true
  $('#cancelButton').disabled = true
  $('#requestList').innerHTML = '<div class="empty-state"><span>⚙</span><strong>服务由桌面端自动启动</strong><small>点击右上角按钮可以重启 Wisp 服务</small></div>'
}

function renderTools(toolList) {
  const list = $('#toolMiniList')
  const active = Boolean(selectedId && sessions.get(selectedId)?.status === 'replying')
  list.innerHTML = toolList.length ? toolList.slice(0, 6).map(tool => `<button class="tool-button" data-mini-tool="${escapeHtml(tool.name)}" ${active ? '' : 'disabled'} title="${active ? '调用工具' : '选择正在回复的请求后使用'}">${escapeHtml(tool.name)}</button>`).join('') : '<div class="muted">等待服务同步工具…</div>'
  list.querySelectorAll('[data-mini-tool]').forEach(button => button.addEventListener('click', () => callTool(button.dataset.miniTool)))
}

function renderStickers(packList) {
  const list = $('#stickerMiniList')
  const active = Boolean(selectedId && sessions.get(selectedId)?.status === 'replying')
  const entries = packList.flatMap(pack => (pack.tags || []).map(tag => ({ pack: pack.pack, tag })))
  list.innerHTML = entries.length ? entries.slice(0, 8).map(item => `<button class="sticker-chip" data-sticker-pack="${escapeHtml(item.pack)}" data-sticker-tag="${escapeHtml(item.tag)}" ${active ? '' : 'disabled'} title="${active ? '发送贴纸' : '选择正在回复的请求后使用'}">${escapeHtml(item.tag)}</button>`).join('') : '<div class="muted">服务尚未同步贴纸</div>'
  list.querySelectorAll('[data-sticker-tag]').forEach(button => button.addEventListener('click', () => sendStickerChoice(button.dataset.stickerPack, button.dataset.stickerTag)))
}

function selectSession(id) {
  currentView = 'inbox'
  updateNav()
  const session = sessions.get(id)
  if (!session) return
  selectedId = id
  $('#selectedTitle').textContent = '回复手机消息'
  $('#selectedMeta').textContent = id
  $('#selectedStatus').textContent = session.status === 'replying' ? '回复中' : '待处理'
  $('#incomingBox').innerHTML = `<div class="incoming-label">PHONE MESSAGE</div><div class="incoming-content">${escapeHtml(session.userText || (session.sticker ? '收到一条贴纸消息' : '(空消息)'))}</div>`
  $('#replyInput').disabled = false
  $('#replyInput').value = ''
  $('#replyInput').focus()
  $('#sendButton').disabled = false
  $('#stickerButton').disabled = false
  $('#cancelButton').disabled = false
  renderInbox()
  if (session.status === 'pending' && sendWs({ type: 'reply.start', requestId: id })) {
    session.status = 'replying'
    sessions.set(id, session)
    renderEverything()
  } else {
    renderEverything()
  }
}

function clearSelection() {
  selectedId = null
  sending = false
  $('#selectedTitle').textContent = '选择一个请求'
  $('#selectedMeta').textContent = '回复内容会发送到手机端'
  $('#selectedStatus').textContent = '未选择'
  $('#incomingBox').innerHTML = '<div class="incoming-label">手机消息</div><div class="incoming-content">从左侧选择一条请求</div>'
  $('#replyInput').disabled = true
  $('#replyInput').value = ''
  $('#sendButton').disabled = true
  $('#stickerButton').disabled = true
  $('#cancelButton').disabled = true
}

function sendReply() {
  if (!selectedId || sending) return
  const content = $('#replyInput').value.trim()
  if (!content) return showToast('请输入回复内容')
  sending = true
  $('#sendButton').disabled = true
  if (!sendWs({ type: 'reply.send', requestId: selectedId, content })) {
    sending = false
    $('#sendButton').disabled = false
  }
}

function cancelReply() {
  if (!selectedId) return
  sendWs({ type: 'reply.cancel', requestId: selectedId })
  clearSelection()
}

function sendStickerChoice(pack, tag) {
  if (!selectedId || sending) return
  const session = sessions.get(selectedId)
  if (!session || session.status !== 'replying') return showToast('请先选择正在回复的请求')
  sending = true
  $('#sendButton').disabled = true
  if (!sendWs({ type: 'reply.send', requestId: selectedId, content: `【sticker:${pack}:${tag}】` })) {
    sending = false
    $('#sendButton').disabled = false
  }
}

function sendSticker() {
  if (!selectedId || sending) return
  const first = stickers.flatMap(pack => (pack.tags || []).map(tag => ({ pack: pack.pack, tag })))[0]
  if (first) return sendStickerChoice(first.pack, first.tag)
  const tag = window.prompt('输入贴纸标签')
  if (tag) sendStickerChoice('', tag)
}

async function callTool(name) {
  if (!selectedId) return showToast('先选择一条手机请求')
  const session = sessions.get(selectedId)
  if (!session || session.status !== 'replying') return showToast('请先开始回复')
  const descriptor = tools.find(tool => tool.name === name)
  const required = descriptor?.inputSchema?.required || []
  let args = {}
  if (required.length) {
    const raw = window.prompt(`${name} 参数 JSON`, '{}')
    if (raw === null) return
    try { args = JSON.parse(raw) } catch { return showToast('参数不是有效 JSON') }
  }
  try {
    const response = await apiFetch('/api/v1/debug/reply/action', { method: 'POST', body: JSON.stringify({ requestId: selectedId, action: 'tool', tool: name, arguments: args }), headers: { 'content-type': 'application/json' } })
    const body = await response.json()
    if (!response.ok) throw new Error(body.error?.message || `HTTP ${response.status}`)
    showToast(`${name} 执行完成`)
  } catch (error) { showToast(`工具失败: ${error.message}`) }
}

async function playVoice(id) {
  const voice = voices.find(item => item.id === id)
  try {
    const response = await apiFetch(`/api/v1/debug/voices/${encodeURIComponent(id)}/download`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    if (audioUrl) URL.revokeObjectURL(audioUrl)
    audioUrl = URL.createObjectURL(await response.blob())
    $('#audioPlayer').src = audioUrl
    await $('#audioPlayer').play()
  } catch (error) { showToast(`语音播放失败: ${error.message}`) }
  if (voice) showToast(voice.text || '正在播放语音')
}

async function testTts() {
  const text = $('#ttsText').value.trim()
  if (!text) return
  const engine = $('#ttsEngine').value
  try {
    const response = await apiFetch(`/api/v1/tts/proxy?engine=${encodeURIComponent(engine)}&text=${encodeURIComponent(text)}`)
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.error?.message || `HTTP ${response.status}`) }
    if (audioUrl) URL.revokeObjectURL(audioUrl)
    audioUrl = URL.createObjectURL(await response.blob())
    $('#audioPlayer').src = audioUrl
    await $('#audioPlayer').play()
  } catch (error) { showToast(`TTS 失败: ${error.message}`) }
}

function setView(view) {
  currentView = view
  if (view !== 'inbox') clearSelection()
  updateNav()
  renderEverything()
}

function updateNav() {
  document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === currentView))
}

function formatAge(timestamp) { const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000)); return seconds < 60 ? `${seconds}s 前` : `${Math.floor(seconds / 60)}m 前` }
function formatTime(timestamp) { return timestamp ? new Date(timestamp).toLocaleTimeString() : '' }
function formatBytes(size = 0) { return `${(size / 1024).toFixed(1)}KB` }

let toastTimer = null
function showToast(message) {
  const toast = $('#toast')
  toast.textContent = message
  toast.classList.add('show')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => toast.classList.remove('show'), 2600)
}

document.querySelectorAll('.nav-item').forEach(item => item.addEventListener('click', () => setView(item.dataset.view)))
document.querySelectorAll('[data-view="tools"]').forEach(item => item.addEventListener('click', () => setView('tools')))
document.querySelectorAll('[data-quick]').forEach(item => item.addEventListener('click', () => { $('#replyInput').value = item.dataset.quick; $('#replyInput').dispatchEvent(new Event('input')) }))
$('#sendButton').addEventListener('click', sendReply)
$('#stickerButton').addEventListener('click', sendSticker)
$('#cancelButton').addEventListener('click', cancelReply)
$('#cancelAllButton').addEventListener('click', () => { if (confirm('确定忽略全部待处理请求吗？')) sendWs({ type: 'reply.cancelAll' }) })
$('#ttsButton').addEventListener('click', testTts)
$('#restartButton').addEventListener('click', async () => { showToast('正在重启服务…'); try { await window.wispDesktop.restartServer(); showToast('Wisp 服务已重启') } catch (error) { showToast(`重启失败: ${error.message}`) } })
$('#replyInput').addEventListener('input', () => { $('#draftCount').textContent = `${$('#replyInput').value.length} 字` })
$('#replyInput').addEventListener('keydown', event => { if (event.key === 'Enter' && event.ctrlKey) { event.preventDefault(); sendReply() } })
window.wispDesktop.onServerExit(() => setConnection('offline', '服务已停止'))
window.wispDesktop.getStatus().then(status => { $('#portText').textContent = `127.0.0.1:${status.port}`; $('#serverAddress').textContent = status.serverUrl })

connect()
