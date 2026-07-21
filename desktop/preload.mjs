import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('wispDesktop', {
  getStatus: () => ipcRenderer.invoke('wisp:status'),
  restartServer: () => ipcRenderer.invoke('wisp:restart'),
  onServerExit: (callback) => ipcRenderer.on('wisp:server-exit', (_event, details) => callback(details)),
})
