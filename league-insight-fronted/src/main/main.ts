import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { join } from 'path'
import { spawn, ChildProcess } from 'child_process'

let mainWindow: BrowserWindow | null = null
let backendProcess: ChildProcess | null = null

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged

/**
 * 创建主窗口
 */
function createWindow() {
  // 生产环境和开发环境的图标路径
  const iconPath = isDev
    ? join(__dirname, '../../public/icon.ico')
    : join(process.resourcesPath, 'public/icon.ico')

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    frame: false,
    transparent: false,
    backgroundColor: '#1a1a2e',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: join(__dirname, '../preload/preload.js'),
      webSecurity: true
    },
    icon: iconPath,
    titleBarStyle: 'hiddenInset'
  })

  // 开发模式加载 Vite 开发服务器
  if (isDev) {
    mainWindow.loadURL('http://localhost:5173')
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }

  // 处理外部链接
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) {
      shell.openExternal(url)
    }
    return { action: 'deny' }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

/**
 * 启动后端服务 (jpackage 打包，带 JVM)
 */
async function startBackend(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (isDev) {
      // 开发模式：假设后端已在运行
      console.log('Development mode: Backend should be running on port 8080')
      resolve()
      return
    }

    // 优先使用 jpackage 打包的后端（带 JVM）
    const jpackageExePath = join(process.resourcesPath, 'backend', 'league-insight', 'league-insight.exe')
    // 备用：Native Image（如果 jpackage 不存在）
    const nativeExePath = join(process.resourcesPath, 'backend', 'league-insight-backend.exe')

    const fs = require('fs')
    const exePath = fs.existsSync(jpackageExePath) ? jpackageExePath : nativeExePath

    console.log('Starting backend from:', exePath)

    backendProcess = spawn(exePath, [], {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    })

    backendProcess.stdout?.on('data', (data) => {
      console.log(`Backend: ${data}`)
    })

    backendProcess.stderr?.on('data', (data) => {
      console.error(`Backend Error: ${data}`)
    })

    backendProcess.on('error', (err) => {
      console.error('Failed to start backend:', err)
      reject(err)
    })

    // 等待后端启动
    waitForBackend().then(resolve).catch(reject)
  })
}

/**
 * 等待后端服务就绪
 */
async function waitForBackend(): Promise<void> {
  const maxRetries = 30
  const retryInterval = 500

  for (let i = 0; i < maxRetries; i++) {
    try {
      const response = await fetch('http://127.0.0.1:8080/actuator/health')
      if (response.ok) {
        console.log('Backend is ready!')
        return
      }
    } catch {
      // 继续等待
    }
    await new Promise(resolve => setTimeout(resolve, retryInterval))
  }

  throw new Error('Backend failed to start within timeout')
}

/**
 * 停止后端服务
 */
function stopBackend() {
  if (backendProcess) {
    console.log('Stopping backend...')
    backendProcess.kill()
    backendProcess = null
  }
}

// ========== IPC 处理 ==========

// 窗口控制
ipcMain.handle('window:minimize', () => {
  mainWindow?.minimize()
})

ipcMain.handle('window:maximize', () => {
  if (mainWindow?.isMaximized()) {
    mainWindow.unmaximize()
  } else {
    mainWindow?.maximize()
  }
})

ipcMain.handle('window:close', () => {
  mainWindow?.close()
})

// 打开外部链接
ipcMain.handle('shell:openExternal', (_, url: string) => {
  shell.openExternal(url)
})

// 获取应用版本
ipcMain.handle('app:getVersion', () => {
  return app.getVersion()
})

// ========== 应用生命周期 ==========

app.whenReady().then(async () => {
  try {
    await startBackend()
    createWindow()
  } catch (error) {
    console.error('Failed to start application:', error)
    app.quit()
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  stopBackend()
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', () => {
  stopBackend()
})

// 处理未捕获的异常
process.on('uncaughtException', (error) => {
  console.error('Uncaught Exception:', error)
})
