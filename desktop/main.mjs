import { app, BrowserWindow, dialog, ipcMain } from 'electron'
import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { randomBytes } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = app.isPackaged ? path.join(process.resourcesPath, 'project') : path.resolve(__dirname, '..')
const serverScript = path.join(projectRoot, 'scripts', 'wisp-dev-server.mjs')
const rendererPath = path.join(__dirname, 'renderer', 'index.html')
const dashboardToken = randomBytes(24).toString('hex')
const configuredHost = process.env.WISP_DESKTOP_HOST || '127.0.0.1'
const dashboardHost = ['0.0.0.0', '::'].includes(configuredHost) ? '127.0.0.1' : configuredHost

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
  serverPort = await findFreePort(Number.parseInt(process.env.WISP_DEBUG_PORT || '17890', 10))
  const env = {
    ...process.env,
    WISP_ROOT: projectRoot,
    WISP_DEBUG_HOST: configuredHost,
    WISP_DEBUG_PORT: String(serverPort),
    WISP_INTERACTIVE: '1',
    WISP_DASHBOARD_TOKEN: dashboardToken,
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
  await waitForServer(serverPort)
}

async function stopServer() {
  const child = serverProcess
  if (!child) return
  await new Promise((resolve) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      resolve()
    }
    child.once('exit', finish)
    child.kill()
    setTimeout(finish, 3000)
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

ipcMain.handle('wisp:restart', async () => {
  await stopServer()
  await startServer()
  const query = new URLSearchParams({ server: `http://${dashboardHost}:${serverPort}`, token: dashboardToken })
  await mainWindow?.loadFile(rendererPath, { query: Object.fromEntries(query.entries()) })
  return { port: serverPort }
})

app.whenReady().then(async () => {
  try {
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

app.on('before-quit', () => {
  shuttingDown = true
  if (serverProcess) serverProcess.kill()
  tray?.destroy()
})
