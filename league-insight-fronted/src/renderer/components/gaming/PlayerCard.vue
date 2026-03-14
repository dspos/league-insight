<template>
  <div class="player-card" :class="teamClass">
    <!-- 加载中 -->
    <div v-if="sessionSummoner.isLoading" class="loading-state">
      <span class="loading-spinner"></span>
      <span v-if="sessionSummoner.summoner?.gameName" class="loading-name">
        {{ sessionSummoner.summoner.gameName }}
      </span>
    </div>

    <!-- 战绩隐藏 -->
    <div v-else-if="isHiddenRecord" class="hidden-record">
      <img :src="getChampionUrl(sessionSummoner.championId)" class="hidden-avatar" alt="" />
      <span class="hidden-text">战绩已隐藏</span>
    </div>

    <!-- 正常显示 -->
    <div v-else-if="sessionSummoner.summoner?.gameName" class="player-content">
      <!-- 左侧：英雄和基本信息 -->
      <div class="player-left">
        <div class="avatar-wrapper">
          <img :src="getChampionUrl(sessionSummoner.championId)" class="champion-img" alt="" />
          <span class="level-badge">{{ sessionSummoner.summoner?.summonerLevel || '?' }}</span>
          <!-- 预组队徽章 - 放在头像右下角 -->
          <span
            v-if="sessionSummoner.preGroupMarkers?.name"
            class="pre-group-badge"
            :class="sessionSummoner.preGroupMarkers.type"
          >
            {{ sessionSummoner.preGroupMarkers.name }}
          </span>
        </div>

        <div class="player-info">
          <div class="player-name-row">
            <span class="player-name" @click="onNameClick">{{ sessionSummoner.summoner.gameName }}</span>
            <span class="player-tag">#{{ sessionSummoner.summoner.tagLine }}</span>
          </div>
          <div class="player-tier-row">
            <img :src="tierImgUrl" class="tier-icon" alt="" />
            <span class="tier-text">{{ tierCn }}</span>
          </div>
          <!-- 用户标签 -->
          <div v-if="userTags.length" class="user-tags-row">
            <span
              v-for="tag in userTags.slice(0, 2)"
              :key="tag.tagName"
              class="user-tag"
              :class="tag.good ? 'good' : 'bad'"
              :title="tag.tagDesc"
            >
              {{ tag.tagName }}
            </span>
          </div>
        </div>
      </div>

      <!-- 中间：核心数据 -->
      <div class="player-stats">
        <!-- 最近战绩结果图标 -->
        <div class="recent-result">
          <span class="result-icon" :class="lastGameWin ? 'win' : 'lose'">
            {{ lastGameWin ? '✓' : '✗' }}
          </span>
          <span class="result-label">{{ lastGameWin ? '胜' : '负' }}</span>
        </div>

        <!-- KDA -->
        <div class="stat-item">
          <span class="stat-value kda" :style="{ color: kdaColor }">
            {{ kdaValue }}
          </span>
          <span class="stat-label">KDA</span>
        </div>

        <!-- 胜率带趋势箭头 -->
        <div class="stat-item">
          <span class="stat-value winrate" :style="{ color: winRateColor }">
            <span class="trend-arrow" :class="winRateTrend">{{ winRateTrend === 'up' ? '↑' : '↓' }}</span>
            {{ calcWinRate }}%
          </span>
          <span class="stat-label">胜率</span>
        </div>
      </div>

      <!-- 右侧：遇见过提示 -->
      <div v-if="sessionSummoner.meetGames?.length" class="meet-indicator">
        <span class="meet-icon" :title="`曾经遇见过 ${sessionSummoner.meetGames.length} 次`">⚠</span>
      </div>

      <!-- AI 分析按钮 -->
      <div class="ai-action">
        <button
          class="ai-analyze-btn"
          :class="{ 'has-result': analysisResult }"
          :disabled="isAnalyzing"
          @click="handleAnalyzeSession"
          :title="analysisResult ? '点击查看 AI 分析结果' : 'AI 分析此玩家'"
        >
          <span v-if="isAnalyzing" class="loading-spinner small"></span>
          <span v-else-if="analysisResult" class="ai-result-icon">📝</span>
          <span v-else>🤖</span>
        </button>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <span>等待数据...</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { apiClient } from '@/api/httpClient'
import type { SessionSummoner, MatchHistory, AIAnalysisResult } from '@/types/api'

const props = defineProps<{
  sessionSummoner: SessionSummoner
  team?: 'blue' | 'red'
}>()

const emit = defineEmits<{
  navigateToPlayer: [gameName: string, tagLine: string]
  analyzeResult: [result: AIAnalysisResult, playerGameName: string]
}>()

// AI 分析状态
const isAnalyzing = ref(false)
// AI 分析结果（存储在本地，点击后再显示）
const analysisResult = ref<AIAnalysisResult | null>(null)

// 队伍样式类
const teamClass = computed(() => ({
  'team-blue': props.team === 'blue',
  'team-red': props.team === 'red'
}))

// 是否隐藏战绩
const isHiddenRecord = computed(() => {
  return props.sessionSummoner.championId &&
    (!props.sessionSummoner.summoner?.gameName || !props.sessionSummoner.summoner?.puuid)
})

// 用户标签
const userTags = computed(() => {
  return props.sessionSummoner.userTag?.tag || []
})

// 最近一场是否获胜
const lastGameWin = computed(() => {
  const matches = props.sessionSummoner.matchHistory || []
  if (matches.length === 0) return true
  return matches[0].participants?.[0]?.stats?.win || false
})

// KDA 值
const kdaValue = computed(() => {
  const kda = props.sessionSummoner.userTag?.recentData?.kda
  if (!kda) return '0.0'
  return kda.toFixed(1)
})

// KDA 颜色
const kdaColor = computed(() => {
  const kda = props.sessionSummoner.userTag?.recentData?.kda
  if (!kda) return 'var(--text-primary)'
  if (kda >= 5) return '#f2bf63'
  if (kda >= 3) return '#3d9b7a'
  if (kda >= 1) return 'var(--text-primary)'
  return '#c45c5c'
})

// 计算胜率
const calcWinRate = computed(() => {
  const wins = props.sessionSummoner.userTag?.recentData?.selectWins || 0
  const losses = props.sessionSummoner.userTag?.recentData?.selectLosses || 0
  const total = wins + losses
  if (total === 0) return 50
  return Math.round((wins / total) * 100)
})

// 胜率颜色
const winRateColor = computed(() => {
  const rate = calcWinRate.value
  if (rate >= 60) return '#3d9b7a'
  if (rate >= 50) return 'var(--text-primary)'
  return '#c45c5c'
})

// 胜率趋势
const winRateTrend = computed(() => {
  return calcWinRate.value >= 50 ? 'up' : 'down'
})

// 段位信息
const tierImgUrl = computed(() => {
  const rank = props.sessionSummoner.rank?.queueMap?.RANKED_SOLO_5x5
  if (!rank?.tier) return getTierUrl('UNRANKED')
  return getTierUrl(rank.tier)
})

const tierCn = computed(() => {
  const rank = props.sessionSummoner.rank?.queueMap?.RANKED_SOLO_5x5
  if (!rank?.tier) return '无'
  return getTierCn(rank.tier) + (rank.division ? ' ' + rank.division : '')
})

// 点击名字
function onNameClick() {
  const name = props.sessionSummoner.summoner?.gameName
  const tag = props.sessionSummoner.summoner?.tagLine
  if (name && tag) {
    emit('navigateToPlayer', name, tag)
  }
}

// AI 分析会话数据
async function handleAnalyzeSession() {
  // 如果正在分析中，直接返回
  if (isAnalyzing.value) return

  // 如果已有分析结果，直接显示弹窗（通过 emit）
  if (analysisResult.value) {
    const gameName = props.sessionSummoner.summoner?.gameName || '未知玩家'
    emit('analyzeResult', analysisResult.value, gameName)
    return
  }

  // 没有结果，开始后台分析
  isAnalyzing.value = true
  try {
    // 使用 player 模式分析单个玩家
    const result = await apiClient.analyzeSession('player')
    // 存储结果，不立即显示
    analysisResult.value = result
  } catch (error) {
    console.error('AI 分析失败:', error)
  } finally {
    isAnalyzing.value = false
  }
}

// URL 函数
function getChampionUrl(championId: number | undefined): string {
  if (!championId || championId <= 0) return ''
  return `http://127.0.0.1:8080/api/v1/asset/champion/${championId}`
}

function getTierUrl(tier: string): string {
  const tierMap: Record<string, string> = {
    UNRANKED: 'unranked',
    IRON: 'iron',
    BRONZE: 'bronze',
    SILVER: 'silver',
    GOLD: 'gold',
    PLATINUM: 'platinum',
    EMERALD: 'emerald',
    DIAMOND: 'diamond',
    MASTER: 'master',
    GRANDMASTER: 'grandmaster',
    CHALLENGER: 'challenger'
  }
  const key = tierMap[tier.toUpperCase()] || 'unranked'
  return `/src/assets/imgs/tier/${key}.png`
}

function getTierCn(tier: string): string {
  const tierMap: Record<string, string> = {
    UNRANKED: '无',
    IRON: '坚韧黑铁',
    BRONZE: '英勇黄铜',
    SILVER: '不屈白银',
    GOLD: '荣耀黄金',
    PLATINUM: '华贵铂金',
    EMERALD: '流光翡翠',
    DIAMOND: '璀璨钻石',
    MASTER: '超凡大师',
    GRANDMASTER: '傲世宗师',
    CHALLENGER: '最强王者'
  }
  return tierMap[tier.toUpperCase()] || '无'
}
</script>

<style scoped>
.player-card {
  background: var(--bg-secondary);
  border-radius: 12px;
  border: 1px solid var(--border-color);
  padding: 12px 14px;
  min-height: 80px;
  display: flex;
  flex-direction: column;
  transition: all 0.2s;
}

.player-card:hover {
  border-color: var(--border-color-hover, rgba(255,255,255,0.1));
}

/* 蓝方样式 */
.player-card.team-blue {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.08), var(--bg-secondary));
  border-left: 3px solid rgba(59, 130, 246, 0.6);
}

/* 红方样式 */
.player-card.team-red {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.08), var(--bg-secondary));
  border-left: 3px solid rgba(239, 68, 68, 0.6);
}

/* 加载状态 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
}

.loading-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-color);
  border-top-color: var(--accent-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-name {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* 隐藏战绩 */
.hidden-record {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
}

.hidden-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  opacity: 0.5;
}

.hidden-text {
  font-size: 13px;
  color: var(--text-tertiary);
}

/* 正常内容 */
.player-content {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
  min-height: 64px;
}

.player-left {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  align-items: center;
  width: 180px;
}

.avatar-wrapper {
  position: relative;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
}

.champion-img {
  width: 100%;
  height: 100%;
  border-radius: 10px;
  object-fit: cover;
  border: 2px solid rgba(255,255,255,0.1);
}

.level-badge {
  position: absolute;
  bottom: -6px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 9px;
  background: rgba(0, 0, 0, 0.85);
  padding: 1px 6px;
  border-radius: 4px;
  color: white;
  font-weight: 600;
}

/* 预组队徽章 - 头像右下角 */
.pre-group-badge {
  position: absolute;
  bottom: -2px;
  right: -2px;
  font-size: 8px;
  padding: 2px 5px;
  border-radius: 4px;
  font-weight: 700;
  white-space: nowrap;
}

.pre-group-badge.success {
  background: rgba(61, 155, 122, 0.9);
  color: white;
}

.pre-group-badge.warning {
  background: rgba(242, 191, 99, 0.9);
  color: #1a1a2e;
}

.pre-group-badge.error {
  background: rgba(196, 92, 92, 0.9);
  color: white;
}

.pre-group-badge.info {
  background: rgba(92, 163, 234, 0.9);
  color: white;
}

.player-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  justify-content: center;
  height: 48px;
}

.player-name-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.player-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100px;
}

.player-name:hover {
  color: var(--accent-color);
}

.player-tag {
  font-size: 10px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.player-tier-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.tier-icon {
  width: 14px;
  height: 14px;
}

.tier-text {
  font-size: 11px;
  color: var(--text-secondary);
  font-weight: 500;
}

/* 用户标签 */
.user-tags-row {
  display: flex;
  gap: 4px;
  flex-wrap: nowrap;
  overflow: hidden;
  height: 16px;
}

.user-tag {
  padding: 1px 6px;
  font-size: 9px;
  border-radius: 3px;
  font-weight: 600;
  cursor: help;
}

.user-tag.good {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.user-tag.bad {
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
}

/* 中间核心数据 */
.player-stats {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  justify-content: center;
  min-width: 160px;
}

/* 最近战绩结果 */
.recent-result {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 36px;
}

.result-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
}

.result-icon.win {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
  border: 2px solid rgba(61, 155, 122, 0.4);
}

.result-icon.lose {
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
  border: 2px solid rgba(196, 92, 92, 0.4);
}

.result-label {
  font-size: 9px;
  color: var(--text-tertiary);
  font-weight: 500;
  line-height: 1;
}

/* 数据项 */
.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 50px;
}

.stat-value {
  font-size: 16px;
  font-weight: 700;
  line-height: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.stat-label {
  font-size: 9px;
  color: var(--text-tertiary);
  font-weight: 500;
  line-height: 1;
}

/* KDA 样式 */
.stat-value.kda {
  font-size: 18px;
}

/* 胜率趋势箭头 */
.trend-arrow {
  font-size: 14px;
  font-weight: 700;
}

.trend-arrow.up {
  color: #3d9b7a;
}

.trend-arrow.down {
  color: #c45c5c;
}

/* 遇见过提示 */
.meet-indicator {
  flex-shrink: 0;
}

.meet-icon {
  font-size: 18px;
  cursor: help;
  filter: drop-shadow(0 0 4px rgba(242, 191, 99, 0.5));
}

/* AI 分析按钮 */
.ai-action {
  flex-shrink: 0;
  margin-left: 4px;
}

.ai-analyze-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  background: var(--bg-tertiary, rgba(255,255,255,0.05));
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  font-size: 14px;
}

.ai-analyze-btn:hover:not(:disabled) {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: white;
}

.ai-analyze-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ai-analyze-btn.has-result {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.3), rgba(139, 92, 246, 0.15));
  border-color: rgba(139, 92, 246, 0.5);
  color: #a78bfa;
  animation: pulse-glow 2s ease-in-out infinite;
}

.ai-analyze-btn.has-result:hover {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.5), rgba(139, 92, 246, 0.3));
  border-color: rgba(139, 92, 246, 0.7);
  transform: scale(1.05);
}

@keyframes pulse-glow {
  0%, 100% {
    box-shadow: 0 0 5px rgba(139, 92, 246, 0.3);
  }
  50% {
    box-shadow: 0 0 15px rgba(139, 92, 246, 0.5);
  }
}

.ai-result-icon {
  font-size: 14px;
}

.loading-spinner.small {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

/* 空状态 */
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-tertiary);
  font-size: 12px;
}
</style>