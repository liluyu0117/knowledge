'use strict'

/**
 * 预加载脚本：只暴露最小能力给页面，保持 contextIsolation。
 * 前端可用 window.__DESKTOP__ 判断是否运行在桌面壳内。
 */

const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('__DESKTOP__', true)

contextBridge.exposeInMainWorld('desktop', {
  isDesktop: true,
  /** 用系统默认浏览器打开链接 */
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
})
