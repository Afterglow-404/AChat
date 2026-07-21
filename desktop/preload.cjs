const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('wispDesktop', {
  getStatus: () => ipcRenderer.invoke('wisp:status'),
  getVoiceConfig: () => ipcRenderer.invoke('wisp:voice-config'),
  saveVoiceConfig: (config) => ipcRenderer.invoke('wisp:voice-config-save', config),
  pickAudioFile: () => ipcRenderer.invoke('wisp:pick-audio'),
  restartServer: () => ipcRenderer.invoke('wisp:restart'),
  onServerExit: (callback) => {
    if (typeof callback !== 'function') return () => {}
    const listener = (_event, details) => callback(details)
    ipcRenderer.on('wisp:server-exit', listener)
    return () => ipcRenderer.removeListener('wisp:server-exit', listener)
  },
})
