<script setup lang="ts">
import { ref } from 'vue'
import { apiClient } from '@/api/httpClient'
import { useThemeStore } from '@/stores/theme'

const themeStore = useThemeStore()
const appVersion = ref('1.0.0')

// 获取版本号
if (window.electronAPI) {
  window.electronAPI.getVersion().then(v => appVersion.value = v)
}

// 清除缓存
async function clearCache() {
  if (!confirm('确定要清除所有缓存吗？')) return

  try {
    // 清除 localStorage
    localStorage.clear()

    // 调用后端刷新缓存
    await apiClient.getConfig()

    alert('缓存已清除')
  } catch (e) {
    console.error('清除缓存失败', e)
    alert('清除缓存失败')
  }
}

// 导出配置
async function exportConfig() {
  try {
    const config = await apiClient.getConfig()

    // 创建下载文件
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `league-insight-config-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    console.error('导出配置失败', e)
    alert('导出配置失败')
  }
}

// 导入配置
async function importConfig() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json'

  input.onchange = async (e) => {
    const file = (e.target as HTMLInputElement).files?.[0]
    if (!file) return

    try {
      const text = await file.text()
      const config = JSON.parse(text)

      // 保存配置
      if (config.settings) {
        await apiClient.setConfig('settings', config.settings)
      }

      alert('配置已导入')
    } catch (e) {
      console.error('导入配置失败', e)
      alert('导入配置失败：文件格式不正确')
    }
  }

  input.click()
}

// 打开外部链接
function openExternal(url: string) {
  window.electronAPI?.openExternal(url)
}
</script>

<template>
  <div class="settings-view">
    <div class="page-header">
      <h1>系统设置</h1>
      <p>应用程序配置</p>
    </div>

    <!-- 关于 -->
    <div class="settings-section">
      <h2>关于</h2>
      <div class="about-card">
        <div class="app-logo">🎮</div>
        <div class="app-info">
          <h3>League Insight</h3>
          <p>英雄联盟战绩查询工具</p>
          <p class="version">版本 {{ appVersion }}</p>
        </div>
      </div>
    </div>

    <!-- 外观设置 -->
    <div class="settings-section">
      <h2>外观</h2>
      <div class="appearance-settings">
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">主题模式</span>
            <span class="setting-desc">选择明亮或暗黑主题</span>
          </div>
          <div class="theme-toggle">
            <button
              class="theme-btn"
              :class="{ active: themeStore.theme === 'light' }"
              @click="themeStore.setTheme('light')"
              title="明亮模式"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="5"/>
                <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
              </svg>
            </button>
            <button
              class="theme-btn"
              :class="{ active: themeStore.theme === 'dark' }"
              @click="themeStore.setTheme('dark')"
              title="暗黑模式"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 快捷键 -->
    <div class="settings-section">
      <h2>快捷键</h2>
      <div class="shortcut-list">
        <div class="shortcut-item">
          <span class="shortcut-key">Ctrl + R</span>
          <span class="shortcut-action">刷新数据</span>
        </div>
        <div class="shortcut-item">
          <span class="shortcut-key">Ctrl + W</span>
          <span class="shortcut-action">关闭窗口</span>
        </div>
        <div class="shortcut-item">
          <span class="shortcut-key">F12</span>
          <span class="shortcut-action">开发者工具</span>
        </div>
      </div>
    </div>

    <!-- 链接 -->
    <div class="settings-section">
      <h2>相关链接</h2>
      <div class="link-list">
        <a href="#" class="link-item" @click.prevent="openExternal('https://github.com')">
          <span class="link-icon">📦</span>
          <span class="link-text">GitHub 仓库</span>
          <span class="link-arrow">→</span>
        </a>
        <a href="#" class="link-item" @click.prevent="openExternal('https://github.com/issues')">
          <span class="link-icon">🐛</span>
          <span class="link-text">反馈问题</span>
          <span class="link-arrow">→</span>
        </a>
      </div>
    </div>

    <!-- 数据 -->
    <div class="settings-section">
      <h2>数据管理</h2>
      <div class="data-actions">
        <button class="data-btn" @click="clearCache">清除缓存</button>
        <button class="data-btn" @click="exportConfig">导出配置</button>
        <button class="data-btn" @click="importConfig">导入配置</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  max-width: 600px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}

.page-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 8px 0;
  color: var(--text-primary);
}

.page-header p {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.settings-section {
  margin-bottom: 32px;
}

.settings-section h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 16px 0;
  color: var(--text-primary);
}

.about-card {
  display: flex;
  gap: 20px;
  padding: 20px;
  background: var(--bg-secondary);
  border-radius: 12px;
}

.app-logo {
  font-size: 48px;
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-tertiary);
  border-radius: 16px;
}

.app-info h3 {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 4px 0;
  color: var(--text-primary);
}

.app-info p {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.app-info .version {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.shortcut-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.shortcut-item {
  display: flex;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--bg-secondary);
  border-radius: 8px;
}

.shortcut-key {
  font-family: monospace;
  font-size: 12px;
  padding: 4px 8px;
  background: var(--bg-tertiary);
  border-radius: 4px;
  color: var(--text-primary);
}

.shortcut-action {
  font-size: 14px;
  color: var(--text-secondary);
}

.link-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.link-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--bg-secondary);
  border-radius: 8px;
  text-decoration: none;
  transition: background 0.15s;
}

.link-item:hover {
  background: var(--bg-hover);
}

.link-icon {
  font-size: 20px;
}

.link-text {
  flex: 1;
  font-size: 14px;
  color: var(--text-primary);
}

.link-arrow {
  color: var(--text-tertiary);
}

.data-actions {
  display: flex;
  gap: 12px;
}

.data-btn {
  padding: 10px 16px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}

.data-btn:hover {
  background: var(--bg-hover);
}

/* 外观设置 */
.appearance-settings {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.setting-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--bg-secondary);
  border-radius: 8px;
}

.setting-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.setting-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.setting-desc {
  font-size: 12px;
  color: var(--text-tertiary);
}

.theme-toggle {
  display: flex;
  gap: 8px;
  padding: 4px;
  background: var(--bg-tertiary);
  border-radius: 8px;
}

.theme-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--text-secondary);
  transition: all 0.2s ease;
}

.theme-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.theme-btn.active {
  background: var(--accent-color);
  color: white;
}

.theme-btn svg {
  width: 18px;
  height: 18px;
}
</style>
