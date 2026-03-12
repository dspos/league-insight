<script setup lang="ts">
import { useGameStore } from '@/stores/game'

const gameStore = useGameStore()

// 窗口控制
const minimize = () => window.electronAPI?.minimizeWindow()
const maximize = () => window.electronAPI?.maximizeWindow()
const close = () => window.electronAPI?.closeWindow()
</script>

<template>
  <header class="title-bar">
    <div class="title-bar-drag">
      <div class="app-title">
        <span class="logo">🎮</span>
        <span class="title">League Insight</span>
      </div>

      <div class="connection-status">
        <span
          class="status-dot"
          :class="{
            connected: gameStore.connected,
            disconnected: !gameStore.connected
          }"
        ></span>
        <span class="status-text">
          {{ gameStore.connected ? '已连接' : '未连接' }}
        </span>
      </div>

      <div v-if="gameStore.summonerName" class="summoner-info">
        {{ gameStore.summonerName }}
      </div>
    </div>

    <div class="window-controls">
      <button class="control-btn minimize" @click="minimize" title="最小化">
        <svg viewBox="0 0 12 12">
          <rect y="5" width="12" height="2" />
        </svg>
      </button>
      <button class="control-btn maximize" @click="maximize" title="最大化">
        <svg viewBox="0 0 12 12">
          <rect x="1" y="1" width="10" height="10" fill="none" stroke="currentColor" stroke-width="2"/>
        </svg>
      </button>
      <button class="control-btn close" @click="close" title="关闭">
        <svg viewBox="0 0 12 12">
          <line x1="1" y1="1" x2="11" y2="11" stroke="currentColor" stroke-width="2"/>
          <line x1="11" y1="1" x2="1" y2="11" stroke="currentColor" stroke-width="2"/>
        </svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
.title-bar {
  display: flex;
  height: 32px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  -webkit-app-region: drag;
}

.title-bar-drag {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 0 12px;
  gap: 16px;
}

.app-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo {
  font-size: 16px;
}

.title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.connection-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  background: var(--bg-tertiary);
  border-radius: 4px;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.connected {
  background: var(--success-color);
  box-shadow: 0 0 6px var(--success-color);
}

.status-dot.disconnected {
  background: var(--error-color);
}

.status-text {
  color: var(--text-secondary);
}

.summoner-info {
  padding: 2px 8px;
  background: var(--accent-color);
  border-radius: 4px;
  font-size: 12px;
  color: white;
}

.window-controls {
  display: flex;
  -webkit-app-region: no-drag;
}

.control-btn {
  width: 46px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.15s;
}

.control-btn:hover {
  background: var(--bg-hover);
}

.control-btn.close:hover {
  background: var(--error-color);
  color: white;
}

.control-btn svg {
  width: 12px;
  height: 12px;
}
</style>
