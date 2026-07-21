const query = new URLSearchParams(location.search)
let serverBase = (query.get('server') || 'http://127.0.0.1:17890').replace(/\/$/, '')
const dashboardToken = query.get('token') || ''
let wsBase = serverBase.replace(/^http/, 'ws')

const state = {
  ws: null,
  reconnectTimer: null,
  sessions: new Map(),
  voices: [],
  tools: [],
  stickers: [],
  affectSnapshots: new Map(),
  affectEvents: [],
  selectedAffectChat: '',
  toolResults: new Map(),
  toolBusy: new Set(),
  selectedId: null,
  currentView: 'inbox',
  sending: false,
  audioUrl: null,
  voiceConfig: null,
  voiceProfiles: [],
  engines: {
    gptsovits: { health: null, speakers: [] },
      qwen3: { health: null, speakers: [], capabilities: null },
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

function readVoiceConfigForm() {
  return {
    gptSovitsUrl: $('#voiceConfigGptUrl').value,
    qwen3TtsUrl: $('#voiceConfigQwenUrl').value,
    gsvVoice: $('#voiceConfigGsvVoice').value,
    gsvRefAudioPath: $('#voiceConfigGsvRefAudio').value,
    gsvPromptText: $('#voiceConfigGsvPromptText').value,
    gsvPromptLang: $('#voiceConfigGsvPromptLang').value,
    gsvTextLang: $('#voiceConfigGsvTextLang').value,
    gsvMediaType: $('#voiceConfigGsvMediaType').value,
    qwen3Language: $('#voiceConfigQwenLanguage').value,
    qwen3Speaker: $('#voiceConfigQwenSpeaker').value,
    qwen3Instruct: $('#voiceConfigQwenInstruct').value,
    qwen3Mode: $('#cloneQwenMode')?.value || state.voiceConfig?.qwen3Mode || '',
    qwen3RefAudioPath: $('#cloneRefAudioQwen')?.value.trim() || state.voiceConfig?.qwen3RefAudioPath || '',
    qwen3RefText: $('#cloneRefText')?.value.trim() || state.voiceConfig?.qwen3RefText || '',
    qwen3XVectorOnly: Boolean($('#cloneXVectorOnly')?.checked),
    ttsTimeoutMs: Number($('#voiceConfigTtsTimeout').value),
    qwen3TtsTimeoutMs: Number($('#voiceConfigQwenTimeout').value),
  }
}

function fillVoiceConfig(config) {
  if (!config) return
  const values = {
    voiceConfigGptUrl: config.gptSovitsUrl,
    voiceConfigQwenUrl: config.qwen3TtsUrl,
    voiceConfigGsvVoice: config.gsvVoice,
    voiceConfigGsvRefAudio: config.gsvRefAudioPath,
    voiceConfigGsvPromptText: config.gsvPromptText,
    voiceConfigGsvPromptLang: config.gsvPromptLang,
    voiceConfigGsvTextLang: config.gsvTextLang,
    voiceConfigGsvMediaType: config.gsvMediaType,
    voiceConfigQwenLanguage: config.qwen3Language,
    voiceConfigQwenSpeaker: config.qwen3Speaker,
    voiceConfigQwenInstruct: config.qwen3Instruct,
    cloneQwenMode: config.qwen3Mode,
    cloneRefAudioQwen: config.qwen3RefAudioPath,
    cloneRefText: config.qwen3RefText,
    voiceConfigTtsTimeout: config.ttsTimeoutMs,
    voiceConfigQwenTimeout: config.qwen3TtsTimeoutMs,
  }
  Object.entries(values).forEach(([id, value]) => { if ($(`#${id}`)) $(`#${id}`).value = value ?? '' })
  if ($('#cloneXVectorOnly')) $('#cloneXVectorOnly').checked = Boolean(config.qwen3XVectorOnly)
  state.voiceConfig = { ...config }
  $('#ttsInstruct').value = config.qwen3Instruct || ''
  const previewLanguage = config.qwen3Language || config.gsvTextLang
  if ([...$('#ttsLanguage').options].some(option => option.value === previewLanguage)) $('#ttsLanguage').value = previewLanguage
  renderEnginePanel()
}

async function loadVoiceConfig() {
  if (!window.wispDesktop?.getVoiceConfig) {
    $('#voiceConfigStatus').textContent = '当前环境不支持桌面配置'
    return
  }
  try {
    const result = await window.wispDesktop.getVoiceConfig()
    fillVoiceConfig(result.config)
    $('#voiceConfigStatus').textContent = `配置文件：${result.path}`
  } catch (error) {
    $('#voiceConfigStatus').textContent = `读取失败：${error.message}`
  }
}

const cloneProfileStorageKey = 'wisp.voiceProfiles.v1'

function cloneProfileForm() {
  return {
    id: $('#cloneProfileSelect').value || `profile-${Date.now()}`,
    name: $('#cloneProfileName').value.trim(),
    engine: $('#cloneEngine').value,
    voice: $('#cloneVoice').value.trim(),
    language: $('#cloneLanguage').value.trim(),
    refAudioPath: $('#cloneRefAudio').value.trim(),
    promptText: $('#clonePromptText').value.trim(),
    promptLang: $('#clonePromptLang').value.trim(),
    instruct: $('#cloneInstruct').value.trim(),
    qwenMode: $('#cloneQwenMode')?.value || '',
    qwenRefAudioPath: $('#cloneRefAudioQwen')?.value.trim() || '',
    refText: $('#cloneRefText')?.value.trim() || '',
    xVectorOnly: Boolean($('#cloneXVectorOnly')?.checked),
  }
}

function renderCloneProfiles() {
  const select = $('#cloneProfileSelect')
  if (!select) return
  const current = select.value
  select.innerHTML = '<option value="">新建角色预设</option>' + state.voiceProfiles.map(profile => `<option value="${escapeHtml(profile.id)}">${escapeHtml(profile.name)}</option>`).join('')
  if (state.voiceProfiles.some(profile => profile.id === current)) select.value = current
}

function loadCloneProfiles() {
  try {
    const saved = JSON.parse(localStorage.getItem(cloneProfileStorageKey) || '[]')
    state.voiceProfiles = Array.isArray(saved) ? saved.filter(profile => profile && profile.id && profile.name) : []
  } catch { state.voiceProfiles = [] }
  renderCloneProfiles()
}

function writeCloneProfiles() {
  localStorage.setItem(cloneProfileStorageKey, JSON.stringify(state.voiceProfiles))
  renderCloneProfiles()
}

function fillCloneProfile(profile) {
  if (!profile) {
    $('#cloneProfileSelect').value = ''
    $('#cloneProfileName').value = ''
    $('#cloneEngine').value = 'gptsovits'
    $('#cloneVoice').value = ''
    $('#cloneLanguage').value = ''
    $('#cloneRefAudio').value = ''
    $('#clonePromptText').value = ''
    $('#clonePromptLang').value = ''
    $('#cloneInstruct').value = ''
    if ($('#cloneQwenMode')) $('#cloneQwenMode').value = ''
    if ($('#cloneRefAudioQwen')) $('#cloneRefAudioQwen').value = ''
    if ($('#cloneRefText')) $('#cloneRefText').value = ''
    if ($('#cloneXVectorOnly')) $('#cloneXVectorOnly').checked = false
    $('#cloneStatus').textContent = '尚未选择角色预设'
    return
  }
  $('#cloneProfileSelect').value = profile.id
  $('#cloneProfileName').value = profile.name || ''
  $('#cloneEngine').value = profile.engine || 'gptsovits'
  $('#cloneVoice').value = profile.voice || ''
  $('#cloneLanguage').value = profile.language || ''
  $('#cloneRefAudio').value = profile.refAudioPath || ''
  $('#clonePromptText').value = profile.promptText || ''
  $('#clonePromptLang').value = profile.promptLang || ''
  $('#cloneInstruct').value = profile.instruct || ''
  if ($('#cloneQwenMode')) $('#cloneQwenMode').value = profile.qwenMode || ''
  if ($('#cloneRefAudioQwen')) $('#cloneRefAudioQwen').value = profile.qwenRefAudioPath || profile.refAudioPath || ''
  if ($('#cloneRefText')) $('#cloneRefText').value = profile.refText || ''
  if ($('#cloneXVectorOnly')) $('#cloneXVectorOnly').checked = Boolean(profile.xVectorOnly)
  $('#cloneStatus').textContent = `已选择：${profile.name}`
}

function saveCloneProfile() {
  const profile = cloneProfileForm()
  if (!profile.name) return showToast('请先填写角色名称')
  const index = state.voiceProfiles.findIndex(item => item.id === profile.id)
  if (index >= 0) state.voiceProfiles[index] = profile
  else state.voiceProfiles.push(profile)
  writeCloneProfiles()
  $('#cloneProfileSelect').value = profile.id
  $('#cloneStatus').textContent = `已保存：${profile.name}`
  showToast('角色音色预设已保存')
}

function deleteCloneProfile() {
  const id = $('#cloneProfileSelect').value
  if (!id) return showToast('当前没有选中的预设')
  const profile = state.voiceProfiles.find(item => item.id === id)
  if (!confirm(`确定删除“${profile?.name || id}”吗？`)) return
  state.voiceProfiles = state.voiceProfiles.filter(item => item.id !== id)
  writeCloneProfiles()
  fillCloneProfile(null)
}

async function applyCloneProfile() {
  const profile = cloneProfileForm()
  if (!profile.name) return showToast('请先填写或选择角色预设')
  if (profile.engine === 'gptsovits') {
    $('#voiceConfigGsvVoice').value = profile.voice || 'default'
    $('#voiceConfigGsvRefAudio').value = profile.refAudioPath
    $('#voiceConfigGsvPromptText').value = profile.promptText
    $('#voiceConfigGsvPromptLang').value = profile.promptLang || 'zh'
    $('#voiceConfigGsvTextLang').value = profile.language || 'zh'
    $('#ttsEngine').value = 'gptsovits'
  } else {
    $('#voiceConfigQwenSpeaker').value = profile.voice || 'Vivian'
    $('#voiceConfigQwenLanguage').value = profile.language || 'Chinese'
    $('#voiceConfigQwenInstruct').value = profile.instruct
    if ($('#cloneQwenMode')) $('#cloneQwenMode').value = profile.qwenMode || 'base'
    if ($('#cloneRefAudioQwen')) $('#cloneRefAudioQwen').value = profile.qwenRefAudioPath || profile.refAudioPath || ''
    if ($('#cloneRefText')) $('#cloneRefText').value = profile.refText || ''
    if ($('#cloneXVectorOnly')) $('#cloneXVectorOnly').checked = Boolean(profile.xVectorOnly)
    $('#ttsEngine').value = 'qwen3'
  }
  renderEnginePanel()
  $('#cloneStatus').textContent = `正在应用：${profile.name}`
  await saveVoiceConfig(false)
}

async function saveVoiceConfig(reset = false) {
  if (!window.wispDesktop?.saveVoiceConfig) return showToast('当前环境不支持保存语音配置')
  const button = $('#voiceConfigSaveButton')
  const resetButton = $('#voiceConfigResetButton')
  button.disabled = true
  resetButton.disabled = true
  $('#voiceConfigStatus').textContent = reset ? '正在恢复默认并重启语音桥…' : '正在保存并重启语音桥…'
  try {
    const result = await window.wispDesktop.saveVoiceConfig(reset ? null : readVoiceConfigForm())
    fillVoiceConfig(result.config)
    serverBase = result.serverUrl.replace(/\/$/, '')
    wsBase = serverBase.replace(/^http/, 'ws')
    $('#portText').textContent = new URL(serverBase).host
    $('#bridgeAddress').textContent = result.serverUrl
    $('#voiceConfigStatus').textContent = '配置已生效，语音桥已重启'
    showToast('语音配置已生效')
    state.ws?.close()
    state.ws = null
    connect()
    await refreshVoiceBridge()
  } catch (error) {
    $('#voiceConfigStatus').textContent = `保存失败：${error.message}`
    showToast(`语音配置保存失败：${error.message}`)
  } finally {
    button.disabled = false
    resetButton.disabled = false
  }
}

function setConnection(stateName, label) {
  const element = $('.connection')
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
    state.affectSnapshots = new Map((message.affectSnapshots || []).map(snapshot => [snapshot.chatName, snapshot]))
    state.affectEvents = message.affectEvents || []
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
  if (message.type === 'affect.updated') {
    const snapshot = message.snapshot
    if (snapshot?.chatName) state.affectSnapshots.set(snapshot.chatName, snapshot)
    if (message.event) state.affectEvents = [message.event, ...state.affectEvents.filter(event => event.eventId !== message.event.eventId)].slice(0, 200)
    renderAll()
    return
  }
  if (message.type === 'affect.cleared') {
    if (message.chatName) {
      state.affectSnapshots.delete(message.chatName)
      state.affectEvents = state.affectEvents.filter(event => event.chatName !== message.chatName)
    } else {
      state.affectSnapshots.clear()
      state.affectEvents = []
    }
    renderAll()
    return
  }
  // P2.4 修复: 处理 affect.deleted 事件（按时间范围/角色删除）
  // 由于可能涉及时间范围筛选，前端难以精准局部更新，采用"按条件过滤 + 重新拉取"策略
  if (message.type === 'affect.deleted') {
    if (message.chatName) {
      // 按角色删除：移除该角色的所有事件和快照
      state.affectSnapshots.delete(message.chatName)
      state.affectEvents = state.affectEvents.filter(event => event.chatName !== message.chatName)
    } else {
      // 全量或时间范围删除：前端无法精准判断哪些事件在时间范围内，
      // 标记需要刷新，下次 renderAll 时触发拉取
      state.affectEvents = state.affectEvents.filter(event => {
        if (!event.receivedAt) return true
        const eventTs = new Date(event.receivedAt).getTime()
        if (!Number.isFinite(eventTs)) return true
        if (message.since) {
          const sinceTs = new Date(message.since).getTime()
          if (Number.isFinite(sinceTs) && eventTs < sinceTs) return true
        }
        if (message.until) {
          const untilTs = new Date(message.until).getTime()
          if (Number.isFinite(untilTs) && eventTs > untilTs) return true
        }
        return false
      })
      // 清理已无事件的快照
      for (const chatName of [...state.affectSnapshots.keys()]) {
        if (!state.affectEvents.some(e => e.chatName === chatName)) {
          state.affectSnapshots.delete(chatName)
        }
      }
    }
    showToast(`已删除 ${message.deletedCount} 条 affectEvents`)
    renderAll()
    return
  }
  if (message.type === 'cancelledAll') {
    showToast(`已忽略 ${message.count} 个请求`)
    return
  }
  if (message.type === 'error') {
    const session = message.requestId ? state.sessions.get(message.requestId) : null
    if (session && session.status === 'replying') {
      session.status = 'pending'
      state.sessions.set(message.requestId, session)
      renderAll()
    }
    showToast(message.message || '服务发生错误')
  }
}

function renderAll() {
  $('.app-shell').classList.toggle('tools-mode', state.currentView === 'tools' || state.currentView === 'affect')
  $('#pendingBadge').textContent = state.sessions.size
  $('#voiceBadge').textContent = state.voices.length
  $('#affectBadge').textContent = state.affectSnapshots.size
  $('#requestCount').textContent = state.currentView === 'voices' ? state.voices.length : state.currentView === 'tools' ? state.tools.length : state.currentView === 'affect' ? state.affectSnapshots.size : state.sessions.size
  $('#workspaceView').classList.toggle('hidden', state.currentView === 'voicebridge' || state.currentView === 'tools' || state.currentView === 'affect')
  $('#toolsView').classList.toggle('hidden', state.currentView !== 'tools')
  $('#bridgeView').classList.toggle('hidden', state.currentView !== 'voicebridge')
  $('#affectView').classList.toggle('hidden', state.currentView !== 'affect')
  renderTools()
  renderStickers()
  if (state.currentView === 'inbox') renderInbox()
  if (state.currentView === 'voices') renderVoiceView()
  if (state.currentView === 'tools') renderToolMonitor()
  if (state.currentView === 'affect') renderAffectView()
  if (state.currentView === 'voicebridge') { renderInbox(); renderEnginePanel() }
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
    $('#incomingBox').innerHTML = '<span class="message-label">PHONE MESSAGE</span><div class="message-body">从左侧选择一条请求</div>'
    setComposerEnabled(false)
    return
  }
  $('#selectedTitle').textContent = '回复手机消息'
  $('#selectedMeta').textContent = session.requestId
  $('#selectedStatus').textContent = session.status === 'replying' ? '回复中' : '待处理'
  $('#incomingBox').innerHTML = `<span class="message-label">PHONE MESSAGE</span><div class="message-body">${escapeHtml(session.userText || (session.sticker ? '收到一条贴纸消息' : '(空消息)'))}</div>`
  setComposerEnabled(true)
}

function setComposerEnabled(enabled) {
  const session = state.selectedId ? state.sessions.get(state.selectedId) : null
  const pending = Boolean(enabled && session?.status === 'pending')
  const replying = Boolean(enabled && session?.status === 'replying')
  $('#replyInput').disabled = !replying
  $('#sendButton').disabled = !replying
  $('#stickerButton').disabled = !replying
  $('#toolActionButton').disabled = !replying
  $('#cancelButton').disabled = !(pending || replying)
  $('#startReplyButton').disabled = !pending
  updateFlow(session)
}

function updateFlow(session) {
  const steps = document.querySelectorAll('.flow-step')
  steps.forEach(step => step.classList.remove('current', 'done'))
  if (!session) { steps[0].classList.add('current'); return }
  steps[0].classList.add('done')
  if (session.status === 'replying') { steps[1].classList.add('done'); steps[2].classList.add('current') } else steps[1].classList.add('current')
}

function renderVoiceView() {
  clearSelection()
  $('#selectedTitle').textContent = '语音收件箱'
  $('#selectedMeta').textContent = '点击条目播放录音'
  $('#selectedStatus').textContent = `${state.voices.length} 条`
  $('#incomingBox').innerHTML = '<span class="message-label">VOICE INBOX</span><div class="message-body">手机上传的录音和 STT 结果会集中显示在这里。</div>'
  setComposerEnabled(false)
  $('#requestList').innerHTML = state.voices.length ? `<div class="voice-list">${state.voices.slice(0, 50).map(voice => `<button class="voice-card" data-voice-id="${escapeHtml(voice.id)}"><strong>${escapeHtml(voice.text || voice.filename || '语音消息')}</strong><small>${formatTime(voice.receivedAt)} · ${formatBytes(voice.size)}</small></button>`).join('')}</div>` : '<div class="empty-state"><strong>还没有语音消息</strong><small>从手机端发送一条语音开始测试</small></div>'
  $('#requestList').querySelectorAll('[data-voice-id]').forEach(card => card.addEventListener('click', () => playVoice(card.dataset.voiceId)))
}

const previewDefaults = {
  recall: { q: 'Wisp', limit: 5 },
  web_search: { q: 'Wisp project', count: 3 },
  calculator: { expr: '1 + 2 * 3' },
  check_http: { url: 'https://example.com' },
  exchange_rate: { from: 'USD', to: 'CNY' },
  encode_base64: { text: 'Wisp Desktop' },
  note: { text: '工具预览测试', topic: 'desktop-preview' },
  send_message: { text: '工具预览测试', chat: 'debug' },
}

function previewIsReadOnly(name) {
  return name !== 'note' && name !== 'send_message' && !name.startsWith('sticker')
}

function previewArgument(name, key, descriptor) {
  if (previewDefaults[name]?.[key] !== undefined) return previewDefaults[name][key]
  const type = descriptor?.type || 'string'
  if (key === 'url') return 'https://example.com'
  if (type === 'number') return 1
  return ''
}

function toolResultText(result) {
  if (!result) return '尚未刷新'
  const value = result.ok ? result.result : result.error
  if (value === undefined || value === null) return result.ok ? '返回空值' : '请求失败'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}

function renderToolMonitor() {
  const grid = $('#toolPreviewGrid')
  if (!state.tools.length) {
    grid.innerHTML = '<div class="empty-state"><strong>没有可用工具</strong><small>服务尚未同步 AI 工具列表</small></div>'
    $('#toolCountMetric').textContent = '0'
    $('#toolLiveMetric').textContent = '0'
    $('#toolErrorMetric').textContent = '0'
    return
  }
  const results = [...state.toolResults.values()]
  $('#toolCountMetric').textContent = String(state.tools.length)
  $('#toolLiveMetric').textContent = String(results.filter(result => result.ok).length)
  $('#toolErrorMetric').textContent = String(results.filter(result => !result.ok).length)
  grid.innerHTML = state.tools.map(tool => {
    const properties = tool.inputSchema?.properties || {}
    const result = state.toolResults.get(tool.name)
    const busy = state.toolBusy.has(tool.name)
    const readOnly = previewIsReadOnly(tool.name)
    const fields = Object.entries(properties).map(([key, descriptor]) => `<label>${escapeHtml(key)}<input data-tool-input="${escapeHtml(key)}" type="${descriptor.type === 'number' ? 'number' : 'text'}" value="${escapeHtml(previewArgument(tool.name, key, descriptor))}" placeholder="${escapeHtml(descriptor.description || '')}"></label>`).join('')
    return `<article class="tool-preview-card ${result?.ok ? 'has-result' : result ? 'has-error' : ''}"><header><div><span class="tool-kind">${escapeHtml(tool.kind || 'tool')}</span><h3>${escapeHtml(tool.name)}</h3></div><span class="tool-elapsed">${result ? `${result.elapsedMs ?? '-'} ms` : '—'}</span></header><p>${escapeHtml(tool.description || '无描述')}</p>${fields ? `<div class="tool-preview-fields">${fields}</div>` : ''}<pre class="tool-result">${escapeHtml(toolResultText(result))}</pre><footer><span class="tool-state">${busy ? '正在请求…' : result?.ok ? (result.result?.simulated ? '桌面模拟返回' : '已返回实时结果') : result ? '请求失败' : readOnly ? '可刷新' : '仅在回复流程中调用'}</span><button class="tool-preview-button" data-preview-tool="${escapeHtml(tool.name)}" ${busy || !readOnly ? 'disabled' : ''}>${busy ? '请求中' : '刷新'}</button></footer></article>`
  }).join('')
  grid.querySelectorAll('[data-preview-tool]').forEach(button => button.addEventListener('click', () => previewTool(button.dataset.previewTool, button.closest('.tool-preview-card'))))
}

async function previewTool(name, card) {
  if (state.toolBusy.has(name)) return
  const tool = state.tools.find(item => item.name === name)
  if (!tool || !previewIsReadOnly(name)) return
  const args = {}
  card?.querySelectorAll('[data-tool-input]').forEach(input => {
    if (input.value !== '') args[input.dataset.toolInput] = input.type === 'number' ? Number(input.value) : input.value
  })
  if (!card) Object.entries(tool.inputSchema?.properties || {}).forEach(([key, descriptor]) => {
    const value = previewArgument(name, key, descriptor)
    if (value !== '') args[key] = value
  })
  state.toolBusy.add(name)
  renderToolMonitor()
  try {
    const response = await apiFetch('/api/v1/tools/call', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ requestId: `desktop-preview-${Date.now()}`, tool: name, arguments: args }) })
    const body = await response.json().catch(() => ({ ok: false, error: { message: `HTTP ${response.status}` } }))
    state.toolResults.set(name, body)
  } catch (error) {
    state.toolResults.set(name, { ok: false, error: { message: error.message } })
  } finally {
    state.toolBusy.delete(name)
    $('#toolPreviewUpdated').textContent = `最近刷新：${new Date().toLocaleTimeString()}`
    renderToolMonitor()
  }
}

async function refreshToolPreviews() {
  const names = state.tools.filter(tool => previewIsReadOnly(tool.name)).map(tool => tool.name)
  await Promise.allSettled(names.map(name => previewTool(name)))
}

function renderSettingsView() {
  clearSelection()
  $('#selectedTitle').textContent = '服务设置'
  $('#selectedMeta').textContent = '桌面端自动管理本机 Wisp 服务'
  $('#selectedStatus').textContent = 'LOCAL'
  $('#incomingBox').innerHTML = `<span class="message-label">SERVICE</span><div class="message-body">服务地址：${escapeHtml(serverBase)}\n交互模式：已开启\nToken：仅保存在当前桌面会话内</div>`
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
  state.sessions.set(id, session)
  renderAll()
  $('#replyInput').value = ''
  if (session.status === 'replying') $('#replyInput').focus()
}

function startReply() {
  if (!state.selectedId) return showToast('先选择一条手机请求')
  const session = state.sessions.get(state.selectedId)
  if (!session || session.status !== 'pending') return
  if (sendWs({ type: 'reply.start', requestId: state.selectedId })) {
    session.status = 'replying'
    state.sessions.set(state.selectedId, session)
    renderAll()
    $('#replyInput').focus()
  }
}

function clearSelection() {
  state.selectedId = null
  state.sending = false
  if (state.currentView === 'inbox') $('#replyInput').value = ''
}

async function sendReply() {
  if (!state.selectedId || state.sending) return
  const content = $('#replyInput').value.trim()
  if (!content) return showToast('请输入回复内容')
  const requestId = state.selectedId
  state.sending = true
  $('#sendButton').disabled = true
  try {
    const response = await apiFetch('/api/v1/debug/reply/end', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ requestId, content }) })
    const body = await response.json()
    if (!response.ok) throw new Error(body.error?.message || `HTTP ${response.status}`)
    state.sessions.delete(requestId)
    clearSelection()
    renderAll()
    showToast('回复已发送')
  } catch (error) {
    state.sending = false
    $('#sendButton').disabled = false
    showToast(`发送失败: ${error.message}`)
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
  if (!sendWs({ type: 'reply.action', requestId: state.selectedId, action: 'sticker', pack, tag })) {
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
      state.toolResults.set(name, body)
      if (state.currentView === 'tools') renderToolMonitor()
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
  if (engine === 'qwen3') {
    params.set('mode', $('#cloneQwenMode')?.value || state.voiceConfig?.qwen3Mode || '')
    params.set('ref_audio', $('#cloneRefAudioQwen')?.value.trim() || state.voiceConfig?.qwen3RefAudioPath || '')
    params.set('ref_text', $('#cloneRefText')?.value.trim() || state.voiceConfig?.qwen3RefText || '')
    params.set('x_vector_only_mode', $('#cloneXVectorOnly')?.checked ? 'true' : 'false')
  }
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
    const [gptSpeakers, qwenSpeakers, qwenCapabilities] = await Promise.all([readSpeakers('/speakers'), readSpeakers('/qwen3/speakers'), apiFetch('/qwen3/capabilities').then(response => response.json().catch(() => ({})))])
    state.engines.gptsovits.speakers = gptSpeakers
    state.engines.qwen3.speakers = qwenSpeakers
    state.engines.qwen3.capabilities = qwenCapabilities
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
  const configuredVoice = engine === 'qwen3' ? state.voiceConfig?.qwen3Speaker : state.voiceConfig?.gsvVoice
  if (values.includes(configuredVoice)) select.value = configuredVoice
  else if (values.includes(current)) select.value = current
  $('#bridgeAddress').textContent = serverBase
}

function affectNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function affectLabel(name, value) {
  const number = affectNumber(value)
  if (name === 'tension') return number > 0.6 ? 'high tension' : number > 0.2 ? 'tense' : number > -0.2 ? 'calm' : 'harmonious'
  if (name === 'warmth') return number > 0.6 ? 'close' : number > 0.2 ? 'familiar' : number > -0.2 ? 'neutral' : 'distant'
  if (name === 'anticipation') return number > 0.7 ? 'waiting' : number > 0.3 ? 'some expectation' : 'quiet'
  return number > 0.3 ? 'moving closer' : number > -0.1 ? 'stable' : 'drifting apart'
}

function currentAffectSnapshot() {
  if (state.selectedAffectChat && state.affectSnapshots.has(state.selectedAffectChat)) return state.affectSnapshots.get(state.selectedAffectChat)
  const first = state.affectSnapshots.values().next().value || null
  if (first) state.selectedAffectChat = first.chatName
  return first
}

function debugRow(label, value) {
  return `<div class="debug-row"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`
}

function renderAffectView() {
  const select = $('#affectChatSelect')
  const names = [...state.affectSnapshots.keys()].sort()
  if (state.selectedAffectChat && !state.affectSnapshots.has(state.selectedAffectChat)) state.selectedAffectChat = ''
  if (!state.selectedAffectChat && names.length) state.selectedAffectChat = names[0]
  select.innerHTML = names.length
    ? names.map(name => `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`).join('')
    : '<option value="">No snapshot received</option>'
  select.value = state.selectedAffectChat

  const snapshot = currentAffectSnapshot()
  const grid = $('#affectMetricGrid')
  if (!snapshot) {
    grid.innerHTML = '<div class="affect-empty">Waiting for a local Android snapshot.</div>'
    $('#rhythmPanel').innerHTML = '<div class="affect-empty">No rhythm samples yet.</div>'
    $('#assessmentPanel').innerHTML = '<div class="affect-empty">No response assessment yet.</div>'
    $('#affectPendingList').innerHTML = '<div class="affect-empty">No pending events.</div>'
    $('#pendingEventCount').textContent = '0'
    $('#affectUpdated').textContent = 'Waiting for an Android snapshot'
  } else {
    const field = snapshot.affectiveField || {}
    const metrics = [['tension', 'Tension'], ['warmth', 'Warmth'], ['anticipation', 'Anticipation'], ['drift', 'Drift']]
    grid.innerHTML = metrics.map(([name, title]) => {
      const value = affectNumber(field[name])
      const width = Math.round(((value + 1) / 2) * 100)
      return `<article class="affect-metric"><header><label>${title}</label><strong>${value.toFixed(2)}</strong></header><small>${escapeHtml(affectLabel(name, value))}</small><div class="affect-meter"><i style="width:${width}%"></i></div></article>`
    }).join('')
    const rhythm = snapshot.rhythmProfile || {}
    $('#rhythmPanel').innerHTML = [
      debugRow('Samples', `${Math.max(0, Number(rhythm.sampleCount) || 0)}`),
      debugRow('Latency percentile', affectNumber(rhythm.latencyPercentile, .5).toFixed(2)),
      debugRow('Length percentile', affectNumber(rhythm.lengthPercentile, .5).toFixed(2)),
      debugRow('Latency trend', affectNumber(rhythm.latencyTrend).toFixed(2)),
      debugRow('Length trend', affectNumber(rhythm.lengthTrend).toFixed(2)),
      debugRow('Initiative rate (7d)', `${Math.round(affectNumber(rhythm.initiativeRate7d) * 100)}%`),
      ...(['observation', 'hypothesis', 'actionSuggestion'].map(key => snapshot.stateHint?.[key] ? `<div class="debug-note">${escapeHtml(snapshot.stateHint[key])}</div>` : '')),
    ].join('')
    const assessment = snapshot.responseAssessment
    $('#assessmentPanel').innerHTML = assessment ? [
      debugRow('User disclosed', assessment.userDisclosed ? 'yes' : 'no'),
      debugRow('Asked a question', assessment.userAskedQuestion ? 'yes' : 'no'),
      debugRow('Shared positive news', assessment.userSharedPositive ? 'yes' : 'no'),
      debugRow('Emotion answered', assessment.aiRespondedToEmotion ? 'yes' : 'no'),
      debugRow('Content answered', assessment.aiAnsweredContent ? 'yes' : 'no'),
      debugRow('Warmth delta', affectNumber(assessment.warmthDelta).toFixed(2)),
      assessment.pendingSummary ? `<div class="debug-note">${escapeHtml(assessment.pendingSummary)}</div>` : '',
    ].join('') : '<div class="affect-empty">No assessment attached to the latest snapshot.</div>'
    const pending = snapshot.pendingEvents || []
    $('#pendingEventCount').textContent = String(pending.length)
    $('#affectPendingList').innerHTML = pending.length ? pending.map(event => `<div class="pending-debug-item"><strong>${escapeHtml(event.summary || '(untitled event)')}</strong><small>${escapeHtml(event.closureType || 'unknown')} · staleness ${(affectNumber(event.staleness) * 100).toFixed(0)}% · attempts ${event.attemptCount || 0}</small></div>`).join('') : '<div class="affect-empty">No active pending events.</div>'
    $('#affectUpdated').textContent = `Updated ${formatTime(snapshot.receivedAt)} · ${escapeHtml(snapshot.eventId || 'no eventId')}`
  }

  const visibleEvents = state.affectEvents.filter(event => !state.selectedAffectChat || event.chatName === state.selectedAffectChat).slice(0, 12)
  $('#affectEventList').innerHTML = visibleEvents.length ? visibleEvents.map(event => `<div class="pending-debug-item"><strong>${escapeHtml(event.chatName)} · ${escapeHtml(event.eventId || 'no eventId')}</strong><small>${escapeHtml(formatTime(event.receivedAt))} · pending ${event.pendingCount || 0} · warmth ${(affectNumber(event.affectiveField?.warmth)).toFixed(2)}</small></div>`).join('') : '<div class="affect-empty">No events received.</div>'
}

async function refreshAffectState() {
  try {
    const response = await apiFetch('/api/v1/debug/affect')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const payload = await response.json()
    state.affectSnapshots = new Map((payload.snapshots || []).map(snapshot => [snapshot.chatName, snapshot]))
    state.affectEvents = payload.events || []
    renderAll()
    showToast(`Loaded ${state.affectSnapshots.size} relationship snapshot(s)`)
  } catch (error) {
    showToast(`AffectiveField refresh failed: ${error.message}`)
  }
}

async function clearAffectState() {
  const chatName = state.selectedAffectChat
  if (!confirm(chatName ? `Clear snapshot for ${chatName}?` : 'Clear all relationship snapshots?')) return
  try {
    const response = await apiFetch('/api/v1/debug/affect/clear', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ chatName }) })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    showToast('Relationship snapshot cleared')
  } catch (error) {
    showToast(`Clear failed: ${error.message}`)
  }
}

function setView(view) {
  state.currentView = view
  if (view !== 'inbox') clearSelection()
  updateNav()
  renderAll()
  if (view === 'tools' && !state.toolResults.size) refreshToolPreviews()
  if (view === 'affect') refreshAffectState()
}

function updateNav() { document.querySelectorAll('.top-nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === state.currentView)) }
function formatAge(timestamp) { const seconds = Math.max(0, Math.floor((Date.now() - (timestamp || Date.now())) / 1000)); return seconds < 60 ? `${seconds}s 前` : `${Math.floor(seconds / 60)}m 前` }
function formatTime(timestamp) { return timestamp ? new Date(timestamp).toLocaleTimeString() : '' }
function formatBytes(size = 0) { return `${(size / 1024).toFixed(1)}KB` }

let toastTimer = null
function showToast(message) { const toast = $('#toast'); toast.textContent = message; toast.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.classList.remove('show'), 2600) }

document.querySelectorAll('.top-nav-item').forEach(item => item.addEventListener('click', () => setView(item.dataset.view)))
document.querySelectorAll('[data-quick]').forEach(item => item.addEventListener('click', () => { $('#replyInput').value = item.dataset.quick; $('#replyInput').dispatchEvent(new Event('input')) }))
$('#sendButton').addEventListener('click', sendReply)
$('#startReplyButton').addEventListener('click', startReply)
$('#toolActionButton').addEventListener('click', () => document.querySelector('.inline-tools')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }))
$('#stickerButton').addEventListener('click', () => { const first = state.stickers.flatMap(pack => (pack.tags || []).map(tag => ({ pack: pack.pack, tag })))[0]; if (first) sendStickerChoice(first.pack, first.tag); else showToast('没有可用的贴纸') })
$('#cancelButton').addEventListener('click', cancelReply)
$('#cancelAllButton').addEventListener('click', () => { if (confirm('确定忽略全部待处理请求吗？')) sendWs({ type: 'reply.cancelAll' }) })
$('#ttsButton').addEventListener('click', testTts)
$('#voiceReloadButton').addEventListener('click', refreshVoiceBridge)
$('#ttsEngine').addEventListener('change', renderEnginePanel)
$('#voiceConfigSaveButton').addEventListener('click', () => saveVoiceConfig(false))
$('#voiceConfigResetButton').addEventListener('click', () => {
  if (confirm('确定恢复默认语音配置并重启语音桥吗？')) saveVoiceConfig(true)
})
$('#refreshToolsButton').addEventListener('click', refreshToolPreviews)
$('#affectChatSelect').addEventListener('change', event => { state.selectedAffectChat = event.target.value; renderAffectView() })
$('#refreshAffectButton').addEventListener('click', refreshAffectState)
$('#clearAffectButton').addEventListener('click', clearAffectState)
$('#cloneProfileSelect').addEventListener('change', () => fillCloneProfile(state.voiceProfiles.find(profile => profile.id === $('#cloneProfileSelect').value)))
$('#cloneSaveButton').addEventListener('click', saveCloneProfile)
$('#cloneDeleteButton').addEventListener('click', deleteCloneProfile)
$('#cloneApplyButton').addEventListener('click', applyCloneProfile)
$('#clonePickAudioButton')?.addEventListener('click', async () => {
  if (!window.wispDesktop?.pickAudioFile) return showToast('File picker is unavailable in this environment')
  const selected = await window.wispDesktop.pickAudioFile()
  if (selected && $('#cloneRefAudioQwen')) $('#cloneRefAudioQwen').value = selected
})
$('#restartButton').addEventListener('click', async () => { showToast('正在重启服务…'); try { await window.wispDesktop.restartServer(); showToast('Wisp 服务已重启') } catch (error) { showToast(`重启失败: ${error.message}`) } })
$('#replyInput').addEventListener('input', () => { $('#draftCount').textContent = `${$('#replyInput').value.length} 字` })
$('#replyInput').addEventListener('keydown', event => { if (event.key === 'Enter' && event.ctrlKey) { event.preventDefault(); sendReply() } })
window.wispDesktop?.onServerExit(() => setConnection('offline', '服务已停止'))
if (window.wispDesktop?.getStatus) window.wispDesktop.getStatus().then(status => { $('#portText').textContent = new URL(status.serverUrl).host; $('#bridgeAddress').textContent = status.serverUrl; refreshVoiceBridge() })
loadVoiceConfig()
loadCloneProfiles()
const cloneNote = document.querySelector('.clone-note span')
if (cloneNote) cloneNote.textContent = 'GPT-SoVITS uses reference audio and prompt text. Qwen3 Base uses the official voice clone API with reference audio and transcript.'
renderEnginePanel()
connect()
