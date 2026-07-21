import { app, BrowserWindow, dialog, ipcMain } from 'electron'
import { execFile, spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { randomBytes } from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = app.isPackaged ? path.join(process.resourcesPath, 'project') : path.resolve(__dirname, '..')
const serverScript = path.join(projectRoot, 'scripts', 'wisp-dev-server.mjs')
const rendererPath = path.join(__dirname, 'renderer', 'index.html')
const dashboardToken = randomBytes(24).toString('hex')
const configuredHost = process.env.WISP_DESKTOP_HOST || '127.0.0.1'
const dashboardHost = ['0.0.0.0', '::'].includes(configuredHost) ? '127.0.0.1' : configuredHost
const execFileAsync = promisify(execFile)

app.setName('Wisp Desktop')
app.setAppUserModelId('com.aftglw.wisp.desktop')
// Use a fresh app-owned profile after Electron's old profile can no longer
// decrypt its Windows DPAPI data. Wisp Desktop does not store credentials.
app.setPath('userData', path.join(os.tmpdir(), 'WispDesktop-v2'))

// GPU fallback is opt-in so machines with a healthy integrated GPU can keep acceleration.
if (process.env.WISP_DESKTOP_DISABLE_GPU === '1') {
  app.commandLine.appendSwitch('disable-gpu')
  app.disableHardwareAcceleration()
}

let mainWindow = null
let tray = null
let serverProcess = null
let serverPort = 0
let shuttingDown = false
let voiceConfig = null
let restartPromise = null
let shutdownPromise = null
let shutdownComplete = false

const envPositiveInt = (name, fallback) => {
  const value = Number.parseInt(process.env[name] || '', 10)
  return Number.isInteger(value) && value > 0 ? value : fallback
}

const defaultVoiceConfig = {
  gptSovitsUrl: process.env.WISP_GPT_SOVITS_URL || '',
  qwen3TtsUrl: process.env.WISP_QWEN3_TTS_URL || '',
  gsvVoice: process.env.WISP_GSV_VOICE || 'default',
  gsvRefAudioPath: process.env.WISP_GSV_REF_AUDIO_PATH || '',
  gsvPromptText: process.env.WISP_GSV_PROMPT_TEXT || '',
  gsvPromptLang: process.env.WISP_GSV_PROMPT_LANG || 'zh',
  gsvTextLang: process.env.WISP_GSV_TEXT_LANG || 'zh',
  gsvMediaType: process.env.WISP_GSV_MEDIA_TYPE || 'wav',
  qwen3Language: process.env.WISP_QWEN3_LANGUAGE || 'Chinese',
  qwen3Speaker: process.env.WISP_QWEN3_SPEAKER || 'Vivian',
  qwen3Instruct: process.env.WISP_QWEN3_INSTRUCT || '',
  qwen3Mode: process.env.WISP_QWEN3_MODE || '',
  qwen3RefAudioPath: process.env.WISP_QWEN3_REF_AUDIO || '',
  qwen3RefText: process.env.WISP_QWEN3_REF_TEXT || '',
  qwen3XVectorOnly: ['1', 'true', 'yes'].includes(String(process.env.WISP_QWEN3_X_VECTOR_ONLY || '').toLowerCase()),
  ttsTimeoutMs: envPositiveInt('WISP_TTS_TIMEOUT_MS', 120000),
  qwen3TtsTimeoutMs: envPositiveInt('WISP_QWEN3_TTS_TIMEOUT_MS', 120000),
}

const voiceConfigPath = path.join(app.getPath('userData'), 'voice-config.json')

function normalizeUrl(value, label) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  let parsed
  try { parsed = new URL(text) } catch { throw new Error(`${label} 必须是 http:// 或 https:// 地址`) }
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error(`${label} 必须是 http:// 或 https:// 地址`)
  return text.replace(/\/+$/, '')
}

function normalizePositiveInt(value, label) {
  const number = Number(value)
  if (!Number.isInteger(number) || number < 1000 || number > 600000) {
    throw new Error(`${label} 必须是 1000 到 600000 之间的整数`)
  }
  return number
}

function normalizeVoiceConfig(input = {}) {
  const value = { ...defaultVoiceConfig, ...(input || {}) }
  return {
    gptSovitsUrl: normalizeUrl(value.gptSovitsUrl, 'GPT-SoVITS 地址'),
    qwen3TtsUrl: normalizeUrl(value.qwen3TtsUrl, 'Qwen3-TTS 地址'),
    gsvVoice: String(value.gsvVoice ?? '').trim() || 'default',
    gsvRefAudioPath: String(value.gsvRefAudioPath ?? '').trim(),
    gsvPromptText: String(value.gsvPromptText ?? '').trim(),
    gsvPromptLang: String(value.gsvPromptLang ?? '').trim() || 'zh',
    gsvTextLang: String(value.gsvTextLang ?? '').trim() || 'zh',
    gsvMediaType: String(value.gsvMediaType ?? '').trim() || 'wav',
    qwen3Language: String(value.qwen3Language ?? '').trim() || 'Chinese',
    qwen3Speaker: String(value.qwen3Speaker ?? '').trim() || 'Vivian',
    qwen3Instruct: String(value.qwen3Instruct ?? '').trim(),
    qwen3Mode: String(value.qwen3Mode ?? '').trim(),
    qwen3RefAudioPath: String(value.qwen3RefAudioPath ?? '').trim(),
    qwen3RefText: String(value.qwen3RefText ?? '').trim(),
    qwen3XVectorOnly: Boolean(value.qwen3XVectorOnly),
    ttsTimeoutMs: normalizePositiveInt(value.ttsTimeoutMs, 'GPT-SoVITS 超时'),
    qwen3TtsTimeoutMs: normalizePositiveInt(value.qwen3TtsTimeoutMs, 'Qwen3-TTS 超时'),
  }
}

async function loadVoiceConfig() {
  try {
    const saved = JSON.parse(await fs.readFile(voiceConfigPath, 'utf8'))
    voiceConfig = normalizeVoiceConfig(saved)
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`[wisp] voice config ignored: ${error.message}`)
    voiceConfig = normalizeVoiceConfig()
  }
  return voiceConfig
}

function currentVoiceConfig() {
  return voiceConfig || normalizeVoiceConfig()
}

async function persistVoiceConfig(input) {
  const normalized = normalizeVoiceConfig(input)
  await fs.mkdir(path.dirname(voiceConfigPath), { recursive: true })
  await fs.writeFile(voiceConfigPath, `${JSON.stringify(normalized, null, 2)}\n`, 'utf8')
  voiceConfig = normalized
  return normalized
}

async function findFreePort(preferred = 17890) {
  for (let port = preferred; port < preferred + 20; port += 1) {
    const available = await new Promise((resolve) => {
      const probe = createServer()
      probe.once('error', () => resolve(false))
      probe.listen(port, '127.0.0.1', () => probe.close(() => resolve(true)))
    })
    if (available) return port
  }
  return await new Promise((resolve, reject) => {
    const probe = createServer()
    probe.once('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port
      probe.close(() => resolve(port))
    })
  })
}

async function waitForServer(port) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(`http://${dashboardHost}:${port}/api/v1/health`)
      if (response.ok) return
    } catch {
      // The child process may need a moment to load tools and stickers.
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('Wisp 服务启动超时')
}

async function startServer() {
  const configuredVoice = currentVoiceConfig()
  serverPort = await findFreePort(Number.parseInt(process.env.WISP_DEBUG_PORT || '17890', 10))
  const env = {
    ...process.env,
    WISP_ROOT: projectRoot,
    WISP_DEBUG_HOST: configuredHost,
    WISP_DEBUG_PORT: String(serverPort),
    WISP_INTERACTIVE: '1',
    WISP_DASHBOARD_TOKEN: dashboardToken,
    WISP_GPT_SOVITS_URL: configuredVoice.gptSovitsUrl,
    WISP_QWEN3_TTS_URL: configuredVoice.qwen3TtsUrl,
    WISP_GSV_VOICE: configuredVoice.gsvVoice,
    WISP_GSV_REF_AUDIO_PATH: configuredVoice.gsvRefAudioPath,
    WISP_GSV_PROMPT_TEXT: configuredVoice.gsvPromptText,
    WISP_GSV_PROMPT_LANG: configuredVoice.gsvPromptLang,
    WISP_GSV_TEXT_LANG: configuredVoice.gsvTextLang,
    WISP_GSV_MEDIA_TYPE: configuredVoice.gsvMediaType,
    WISP_QWEN3_LANGUAGE: configuredVoice.qwen3Language,
    WISP_QWEN3_SPEAKER: configuredVoice.qwen3Speaker,
    WISP_QWEN3_INSTRUCT: configuredVoice.qwen3Instruct,
    WISP_QWEN3_MODE: configuredVoice.qwen3Mode,
    WISP_QWEN3_REF_AUDIO: configuredVoice.qwen3RefAudioPath,
    WISP_QWEN3_REF_TEXT: configuredVoice.qwen3RefText,
    WISP_QWEN3_X_VECTOR_ONLY: configuredVoice.qwen3XVectorOnly ? '1' : '0',
    WISP_TTS_TIMEOUT_MS: String(configuredVoice.ttsTimeoutMs),
    WISP_QWEN3_TTS_TIMEOUT_MS: String(configuredVoice.qwen3TtsTimeoutMs),
  }
  const nodeExecutable = app.isPackaged ? process.execPath : (process.env.WISP_NODE_PATH || 'node')
  if (app.isPackaged) env.ELECTRON_RUN_AS_NODE = '1'
  serverProcess = spawn(nodeExecutable, [serverScript], {
    cwd: projectRoot,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  serverProcess.stdout.on('data', (chunk) => console.log(`[wisp] ${chunk.toString().trimEnd()}`))
  serverProcess.stderr.on('data', (chunk) => console.error(`[wisp] ${chunk.toString().trimEnd()}`))
  serverProcess.once('exit', (code) => {
    serverProcess = null
    if (!shuttingDown && mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('wisp:server-exit', { code })
    }
  })
  try {
    await waitForServer(serverPort)
  } catch (error) {
    await stopServer()
    throw error
  }
}

async function stopServer() {
  const child = serverProcess
  if (!child) return
  await new Promise((resolve) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      resolve()
    }
    const timeout = setTimeout(finish, 3000)
    child.once('exit', finish)
    child.once('error', finish)

    if (process.platform === 'win32' && child.pid) {
      // Electron's child can own descendants on Windows. Killing only the
      // direct Node process leaves the actual server alive after the window
      // closes, so terminate the whole process tree.
      execFileAsync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true })
        .catch(() => {
          if (child.exitCode === null) child.kill()
        })
    } else if (child.exitCode === null) {
      child.kill('SIGTERM')
    }
  })
  if (serverProcess === child) serverProcess = null
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1080,
    minHeight: 680,
    backgroundColor: '#10151d',
    title: 'Wisp Desktop',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  const query = new URLSearchParams({ server: `http://${dashboardHost}:${serverPort}`, token: dashboardToken })
  mainWindow.loadFile(rendererPath, { query: Object.fromEntries(query.entries()) })
  mainWindow.on('closed', () => { mainWindow = null })
}

function createTray() {
  // Tray support is intentionally deferred until a real application icon is added.
  // Some Windows Electron builds crash while creating a tray from a generated image.
  tray = null
}

ipcMain.handle('wisp:status', () => ({
  port: serverPort,
  running: Boolean(serverProcess),
  serverUrl: `http://${dashboardHost}:${serverPort}`,
}))

ipcMain.handle('wisp:voice-config', async () => ({
  config: await loadVoiceConfig(),
  path: voiceConfigPath,
}))

ipcMain.handle('wisp:pick-audio', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: '选择 Qwen3-TTS 参考音频',
    properties: ['openFile'],
    filters: [{ name: '音频文件', extensions: ['wav', 'flac', 'ogg', 'mp3', 'm4a', 'aac'] }],
  })
  return result.canceled ? '' : (result.filePaths[0] || '')
})

async function restartServer({ reloadWindow = false } = {}) {
  if (restartPromise) return restartPromise
  restartPromise = (async () => {
    await stopServer()
    await startServer()
    if (reloadWindow) {
      const query = new URLSearchParams({ server: `http://${dashboardHost}:${serverPort}`, token: dashboardToken })
      await mainWindow?.loadFile(rendererPath, { query: Object.fromEntries(query.entries()) })
    }
    return { port: serverPort, serverUrl: `http://${dashboardHost}:${serverPort}` }
  })()
  try { return await restartPromise } finally { restartPromise = null }
}

ipcMain.handle('wisp:voice-config-save', async (_event, input) => {
  const config = await persistVoiceConfig(input)
  const server = await restartServer()
  return { config, ...server, path: voiceConfigPath }
})

ipcMain.handle('wisp:restart', async () => {
  return restartServer({ reloadWindow: true })
})

app.whenReady().then(async () => {
  try {
    await loadVoiceConfig()
    await startServer()
    createWindow()
    createTray()
  } catch (error) {
    dialog.showErrorBox('Wisp Desktop 启动失败', error instanceof Error ? error.message : String(error))
    app.quit()
  }
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', (event) => {
  if (shutdownComplete) return
  if (shutdownPromise) {
    event.preventDefault()
    return
  }

  if (!serverProcess) {
    shutdownComplete = true
    tray?.destroy()
    return
  }

  event.preventDefault()
  shuttingDown = true
  shutdownPromise = stopServer()
    .catch((error) => console.error(`[wisp] shutdown failed: ${error.message}`))
    .finally(() => {
      shutdownComplete = true
      tray?.destroy()
      app.quit()
    })
})
