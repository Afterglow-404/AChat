const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('wispDesktop', {
  getStatus: () => ipcRenderer.invoke('wisp:status'),
  restartServer: () => ipcRenderer.invoke('wisp:restart'),
  onServerExit: (callback) => {
    if (typeof callback !== 'function') return () => {}
    const listener = (_event, details) => callback(details)
    ipcRenderer.on('wisp:server-exit', listener)
    return () => ipcRenderer.removeListener('wisp:server-exit', listener)
  },
})
