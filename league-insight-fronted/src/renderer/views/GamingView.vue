<template>
  <div class="gaming-view">
    <!-- 未在游戏中 -->
    <div v-if="!sessionData.phase" class="not-in-game">
      <div class="not-in-game-icon">🎮</div>
      <h2>等待加入游戏</h2>
      <p>当您进入选人阶段或游戏开始后，这里将显示双方玩家信息</p>
      <button class="refresh-btn" @click="fetchSessionData">刷新状态</button>
    </div>

    <!-- 游戏中 -->
    <div v-else class="gaming-content">
      <!-- 头部信息 -->
      <div class="gaming-header">
        <div class="phase-info">
          <span class="phase-badge" :class="phaseClass">{{ phaseCn }}</span>
          <span class="queue-name">{{ sessionData.typeCn || '未知模式' }}</span>
        </div>
        <button class="refresh-btn-small" @click="fetchSessionData" :disabled="loading">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>

      <!-- 双方队伍 -->
      <div class="teams-container">
        <!-- 我方 -->
        <div class="team-column team-blue">
          <div class="team-header team-header-blue">我方</div>
          <div class="team-players">
            <PlayerCard
              v-for="(player, idx) in sessionData.teamOne"
              :key="'blue-' + idx"
              :session-summoner="player"
              team="blue"
              @navigate-to-player="handleNavigateToPlayer"
            />
          </div>
        </div>

        <!-- 敌方 -->
        <div class="team-column team-red">
          <div class="team-header team-header-red">敌方</div>
          <!-- 选人阶段不显示敌方 -->
          <template v-if="sessionData.phase === 'ChampSelect'">
            <div class="enemy-placeholder">
              <span>选择中...</span>
            </div>
          </template>
          <template v-else>
            <div class="team-players">
              <PlayerCard
                v-for="(player, idx) in sessionData.teamTwo"
                :key="'red-' + idx"
                :session-summoner="player"
                team="red"
                @navigate-to-player="handleNavigateToPlayer"
              />
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { apiClient } from '@/api/httpClient'
import type { SessionData } from '@/types/api'
import PlayerCard from '@/components/gaming/PlayerCard.vue'

const router = useRouter()

// 数据
const sessionData = ref<SessionData>({
  phase: '',
  queueType: '',
  typeCn: '',
  queueId: 0,
  teamOne: [],
  teamTwo: []
})

const loading = ref(false)
let refreshInterval: ReturnType<typeof setInterval> | null = null

// 阶段中文
const phaseCn = computed(() => {
  const phaseMap: Record<string, string> = {
    'ChampSelect': '选人阶段',
    'GameStart': '游戏开始',
    'InProgress': '游戏进行中',
    'PreEndOfGame': '即将结束',
    'EndOfGame': '游戏结束',
    'Lobby': '大厅',
    'Matchmaking': '匹配中',
    'ReadyCheck': '确认阶段'
  }
  return phaseMap[sessionData.value.phase] || sessionData.value.phase
})

// 阶段样式类
const phaseClass = computed(() => {
  const phase = sessionData.value.phase
  if (phase === 'InProgress' || phase === 'GameStart') return 'phase-playing'
  if (phase === 'ChampSelect') return 'phase-select'
  if (phase === 'EndOfGame') return 'phase-ended'
  return ''
})

// 获取会话数据
async function fetchSessionData() {
  loading.value = true
  try {
    const data = await apiClient.getSessionData()
    sessionData.value = data
  } catch (e) {
    console.error('获取会话数据失败', e)
  } finally {
    loading.value = false
  }
}

// 跳转到玩家详情
function handleNavigateToPlayer(gameName: string, tagLine: string) {
  router.push({
    path: '/summoner',
    query: { name: `${gameName}#${tagLine}` }
  })
}

onMounted(() => {
  fetchSessionData()
  // 每5秒自动刷新
  refreshInterval = setInterval(fetchSessionData, 5000)
})

onUnmounted(() => {
  if (refreshInterval) {
    clearInterval(refreshInterval)
  }
})
</script>

<style scoped>
.gaming-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* 未在游戏中 */
.not-in-game {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 16px;
  text-align: center;
}

.not-in-game-icon {
  font-size: 64px;
  opacity: 0.5;
}

.not-in-game h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}

.not-in-game p {
  margin: 0;
  color: var(--text-secondary);
  max-width: 300px;
}

.refresh-btn {
  padding: 10px 24px;
  background: var(--accent-color);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.refresh-btn:hover {
  opacity: 0.9;
}

/* 游戏中 */
.gaming-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 16px;
}

.gaming-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--bg-secondary);
  border-radius: 10px;
  border: 1px solid var(--border-color);
}

.phase-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.phase-badge {
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.phase-badge.phase-playing {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.phase-badge.phase-select {
  background: rgba(92, 163, 234, 0.2);
  color: #5ca3ea;
}

.phase-badge.phase-ended {
  background: rgba(128, 128, 128, 0.2);
  color: var(--text-secondary);
}

.queue-name {
  font-size: 14px;
  color: var(--text-secondary);
}

.refresh-btn-small {
  padding: 6px 16px;
  background: var(--bg-tertiary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.refresh-btn-small:hover:not(:disabled) {
  background: var(--bg-elevated);
}

.refresh-btn-small:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 队伍 */
.teams-container {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.team-column {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.team-header {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  text-align: center;
}

.team-header-blue {
  background: linear-gradient(90deg, rgba(59, 130, 246, 0.3), rgba(59, 130, 246, 0.1));
  color: #93c5fd;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.team-header-red {
  background: linear-gradient(90deg, rgba(239, 68, 68, 0.3), rgba(239, 68, 68, 0.1));
  color: #fca5a5;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.team-players {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
  overflow-y: auto;
}

.enemy-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-secondary);
  border-radius: 10px;
  border: 1px dashed var(--border-color);
  color: var(--text-tertiary);
  font-size: 14px;
}
</style>
