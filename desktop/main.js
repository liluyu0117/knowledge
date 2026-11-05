'use strict'

/**
 * 两翼知识库 · 桌面端主进程
 *
 * 职责：选端口 → 拉起 Java 服务（sidecar）→ 等 /api/health 就绪 → 开窗口
 *      关窗隐藏到托盘；托盘「退出」才结束服务进程。
 */

const { app, BrowserWindow, Tray, Menu, shell, dialog, ipcMain, nativeImage } = require('electron')
const { spawn } = require('node:child_process')
const path = require('node:path')
const fs = require('node:fs')
const net = require('node:net')
const http = require('node:http')

const isDev = !app.isPackaged
const START_PORT = 39300
const MAX_RESTART = 3

let child = null
let win = null
let tray = null
let port = 0
let quitting = false
let restarts = 0
let logPath = null

/* ────────────────────────── 工具 ────────────────────────── */

function log(...args) {
  const line = `[${new Date().toISOString()}] ${args.join(' ')}`
  console.log(line)
  try {
    if (logPath) fs.appendFileSync(logPath, line + '\n')
  } catch (_) { /* 忽略 */ }
}

/** 程序目录：打包后为 exe 所在目录，开发时为仓库根 */
function appRoot() {
  return app.isPackaged ? path.dirname(process.execPath) : path.join(__dirname, '..')
}

/** 数据目录：打包后 <安装目录>/data（绿色便携），开发时仓库内独立目录，避免污染线上 .kb */
function dataRoot() {
  return app.isPackaged
    ? path.join(appRoot(), 'data')
    : path.join(__dirname, '..', '.dev', 'desktop-data')
}

function javaExe() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'jre', 'bin', 'javaw.exe')
    : path.join(appRoot(), 'portable', 'jdk', 'jdk-17.0.20.1+1', 'bin', 'java.exe')
}

function jarFile() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'kb.jar')
    : path.join(appRoot(), 'server', 'target', 'kb-server-0.1.0.jar')
}

/** 探测一个可用端口（优先从 39300 起，被占则让系统分配） */
function pickPort() {
  return new Promise((resolve) => {
    const srv = net.createServer()
    srv.once('error', () => {
      const s2 = net.createServer()
      s2.once('error', () => resolve(START_PORT))
      s2.listen(0, '127.0.0.1', () => {
        const p = s2.address().port
        s2.close(() => resolve(p))
      })
    })
    srv.listen(START_PORT, '127.0.0.1', () => srv.close(() => resolve(START_PORT)))
  })
}

function health(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/api/health', timeout: 1500 }, (res) => {
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => {
        try { resolve(res.statusCode === 200 ? JSON.parse(body) : null) }
        catch { resolve(null) }
      })
    })
    req.on('timeout', () => { req.destroy(); resolve(null) })
    req.on('error', () => resolve(null))
  })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitReady(port, timeoutMs = 45000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const h = await health(port)
    if (h && h.ok) return h
    await sleep(400)
  }
  return null
}

/* ────────────────────────── 服务进程 ────────────────────────── */

async function startServer() {
  port = await pickPort()
  const java = javaExe()
  const jar = jarFile()
  if (!fs.existsSync(java)) throw new Error(`找不到 Java 运行时：${java}`)
  if (!fs.existsSync(jar)) throw new Error(`找不到服务包：${jar}`)

  const data = dataRoot()
  fs.mkdirSync(data, { recursive: true })
  const tmp = path.join(app.getPath('temp'), 'kb-desktop')
  fs.mkdirSync(tmp, { recursive: true })   // Spring Boot 要用它建 Tomcat 临时目录

  log(`启动服务: port=${port} data=${data}`)
  child = spawn(java, [
    `-Dkb.home=${data}`,
    `-Dfile.encoding=UTF-8`,
    `-Xmx1200m`,
    `-Djava.io.tmpdir=${tmp}`,
    '-jar', jar,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    '--kb.quiet',
  ], { cwd: data, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })

  child.stdout.on('data', (b) => log('[java]', b.toString().trim()))
  child.stderr.on('data', (b) => log('[java]', b.toString().trim()))

  child.on('exit', (code) => {
    log(`服务进程退出，code=${code}`)
    child = null
    if (quitting) return
    if (restarts < MAX_RESTART) {
      restarts++
      log(`尝试自动重启（第 ${restarts} 次）`)
      setTimeout(() => startServer().then(() => {
        if (win) win.loadURL(`http://127.0.0.1:${port}`)
      }).catch((e) => log('重启失败', e.message)), 1200)
    } else {
      dialog.showErrorBox('服务已停止', '后台服务连续异常退出，请重启应用。')
    }
  })

  const h = await waitReady(port)
  if (!h) throw new Error('服务启动超时（45 秒内未就绪）')
  log(`服务就绪: ${JSON.stringify(h)}`)
  return h
}

/** 结束服务进程树（Windows 下 javaw 可能有子进程） */
function stopServer() {
  if (!child || !child.pid) return
  try {
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true })
    } else {
      child.kill('SIGTERM')
    }
  } catch (e) {
    log('结束服务失败', e.message)
  }
  child = null
}

/* ────────────────────────── 窗口与托盘 ────────────────────────── */

function createWindow() {
  win = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 1000,
    minHeight: 680,
    show: false,
    backgroundColor: '#F6F7F9',
    title: '两翼知识库',
    icon: path.join(__dirname, 'icons', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  })

  win.loadURL(`http://127.0.0.1:${port}`)
  win.once('ready-to-show', () => win.show())

  // 外链交给系统浏览器；本机接口（如 /api/doc/raw 打开原文件）允许新窗口
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith(`http://127.0.0.1:${port}`) || url.startsWith(`http://localhost:${port}`)) {
      return { action: 'allow', overrideBrowserWindowOptions: { width: 1100, height: 820, autoHideMenuBar: true } }
    }
    if (/^https?:/i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(`http://127.0.0.1:${port}`) && !url.startsWith(`http://localhost:${port}`)) {
      e.preventDefault()
      if (/^https?:/i.test(url)) shell.openExternal(url)
    }
  })

  // 关窗 = 收进托盘
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault()
      win.hide()
      if (tray) log('窗口已隐藏到托盘')
    }
  })
}

function createTray() {
  const img = nativeImage.createFromPath(path.join(__dirname, 'icons', 'tray.png'))
  tray = new Tray(img.isEmpty() ? nativeImage.createEmpty() : img)
  tray.setToolTip('两翼知识库')
  const menu = Menu.buildFromTemplate([
    { label: '打开主界面', click: () => showWindow() },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit() } },
  ])
  tray.setContextMenu(menu)
  tray.on('click', () => showWindow())
}

function showWindow() {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

/* ────────────────────────── 生命周期 ────────────────────────── */

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    log('检测到第二个实例，激活已有窗口')
    showWindow()
  })

  app.whenReady().then(async () => {
    logPath = path.join(app.getPath('userData'), 'desktop.log')
    log('应用启动', app.isPackaged ? '（打包模式）' : '（开发模式）')

    try {
      await startServer()
      createWindow()
      createTray()
    } catch (e) {
      log('启动失败', e.message)
      dialog.showErrorBox('启动失败', `${e.message}\n\n日志：${logPath}`)
      app.quit()
    }
  })

  app.on('before-quit', () => {
    quitting = true
    stopServer()
  })

  app.on('window-all-closed', () => {
    // 托盘常驻：不在这里退出
    if (process.platform !== 'darwin' && quitting) app.quit()
  })

  app.on('activate', () => showWindow())
}

ipcMain.handle('open-external', (_e, url) => {
  if (typeof url === 'string' && /^https?:/i.test(url)) shell.openExternal(url)
})
