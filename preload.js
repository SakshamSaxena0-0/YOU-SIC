'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('pa', {
  getLibrary: () => ipcRenderer.invoke('lib:get'),
  scan: (force) => ipcRenderer.invoke('lib:scan', force),
  addFolder: () => ipcRenderer.invoke('lib:addFolder'),
  removeFolder: (f) => ipcRenderer.invoke('lib:removeFolder', f),
  lyrics: (file) => ipcRenderer.invoke('lyrics:get', file),
  load: (name) => ipcRenderer.invoke('store:get', name),
  save: (name, data) => ipcRenderer.invoke('store:set', name, data),
  saveSync: (name, data) => ipcRenderer.sendSync('store:setSync', name, data),
  minimize: () => ipcRenderer.send('win:min'),
  maximize: () => ipcRenderer.send('win:max'),
  close: () => ipcRenderer.send('win:close'),
  setGlass: (on) => ipcRenderer.invoke('win:glass', on),
  glassSupported: () => ipcRenderer.invoke('win:glassSupported'),
  setTitle: (t) => ipcRenderer.send('win:title', t),
  setProgress: (v) => ipcRenderer.send('win:progress', v),
  showInFolder: (f) => ipcRenderer.send('shell:show', f),
  setThumbar: (icons) => ipcRenderer.send('thumbar', icons),
  onProgress: (cb) => ipcRenderer.on('lib:progress', (_e, m) => cb(m)),
  onCommand: (cb) => ipcRenderer.on('cmd', (_e, c) => cb(c)),
  onWinState: (cb) => ipcRenderer.on('win:state', (_e, s) => cb(s)),
  setWallpaperMode: (on) => ipcRenderer.send('win:wallpaper', on),
  onWallpaper: (cb) => ipcRenderer.on('wall', (_e, w) => cb(w)),
  onGeom: (cb) => ipcRenderer.on('win:geom', (_e, g) => cb(g)),
});
