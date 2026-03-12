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
        </div>
      </div>

      <!-- 中间：近期战绩 -->
      <div class="player-history">
        <div
          v-for="(game, idx) in recentMatches"
          :key="idx"
          class="history-item"
          :class="{ win: isWin(game) }"
        >
          <span class="win-status" :class="{ win: isWin(game) }">{{ isWin(game) ? '胜' : '负' }}</span>
          <img :src="getChampionUrl(game.championId)" class="history-champ" alt="" />
          <span class="history-kda">{{ game.stats?.kills || 0 }}/{{ game.stats?.deaths || 0 }}/{{ game.stats?.assists || 0 }}</span>
        </div>
      </div>

      <!-- 右侧：标签和数据 -->
      <div class="player-right">
        <!-- 预组队标记 -->
        <div class="tags-row">
          <span
            v-if="sessionSummoner.preGroupMarkers?.name"
            class="pre-group-tag"
            :class="sessionSummoner.preGroupMarkers.type"
          >
            {{ sessionSummoner.preGroupMarkers.name }}
          </span>

          <!-- 遇到过 -->
          <span v-if="sessionSummoner.meetGames?.length" class="meet-tag">
            遇见过
          </span>

          <!-- 用户标签 -->
          <span
            v-for="tag in sessionSummoner.userTag?.tag || []"
            :key="tag.tagName"
            class="user-tag"
            :class="tag.good ? 'good' : 'bad'"
            :title="tag.tagDesc"
          >
            {{ tag.tagName }}
          </span>
        </div>

        <!-- 近期数据 -->
        <div class="recent-stats">
          <div class="stat-row">
            <span class="stat-label">KDA</span>
            <span class="stat-value" :style="{ color: getKdaColor(sessionSummoner.userTag?.recentData?.kda) }">
              {{ sessionSummoner.userTag?.recentData?.kda?.toFixed(1) || '0.0' }}
            </span>
          </div>
          <div class="stat-row">
            <span class="stat-label">胜率</span>
            <span class="stat-value" :style="{ color: getWinRateColor(calcWinRate) }">
              {{ calcWinRate }}%
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <span>等待数据...</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SessionSummoner, MatchHistory } from '@/types/api'

const props = defineProps<{
  sessionSummoner: SessionSummoner
  team?: 'blue' | 'red'
}>()

const emit = defineEmits<{
  navigateToPlayer: [gameName: string, tagLine: string]
}>()

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

// 近期战绩（取前4场）
const recentMatches = computed(() => {
  return (props.sessionSummoner.matchHistory || []).slice(0, 4)
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

// 计算胜率
const calcWinRate = computed(() => {
  const wins = props.sessionSummoner.userTag?.recentData?.selectWins || 0
  const losses = props.sessionSummoner.userTag?.recentData?.selectLosses || 0
  const total = wins + losses
  if (total === 0) return 0
  return Math.round((wins / total) * 100)
})

// 是否获胜
function isWin(game: MatchHistory): boolean {
  return game.participants?.[0]?.stats?.win || false
}

// 点击名字
function onNameClick() {
  const name = props.sessionSummoner.summoner?.gameName
  const tag = props.sessionSummoner.summoner?.tagLine
  if (name && tag) {
    emit('navigateToPlayer', name, tag)
  }
}

// 颜色函数
function getKdaColor(kda: number | undefined): string {
  if (!kda) return 'var(--text-primary)'
  if (kda >= 5) return '#f2bf63'
  if (kda >= 3) return '#63d8b4'
  if (kda >= 1) return 'var(--text-primary)'
  return '#c45c5c'
}

function getWinRateColor(rate: number): string {
  if (rate >= 60) return '#63d8b4'
  if (rate >= 50) return 'var(--text-primary)'
  return '#c45c5c'
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
  border-radius: 10px;
  border: 1px solid var(--border-color);
  padding: 12px;
  min-height: 120px;
  display: flex;
  flex-direction: column;
}

.player-card.team-blue {
  border-left: 3px solid rgba(59, 130, 246, 0.6);
}

.player-card.team-red {
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
  gap: 12px;
  flex: 1;
}

.player-left {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.avatar-wrapper {
  position: relative;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
}

.champion-img {
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
}

.level-badge {
  position: absolute;
  bottom: -4px;
  right: -4px;
  font-size: 10px;
  background: rgba(0, 0, 0, 0.8);
  padding: 0 4px;
  border-radius: 4px;
  color: white;
}

.player-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.player-name-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.player-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
}

.player-name:hover {
  color: var(--accent-color);
}

.player-tag {
  font-size: 11px;
  color: var(--text-tertiary);
}

.player-tier-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.tier-icon {
  width: 16px;
  height: 16px;
}

.tier-text {
  font-size: 11px;
  color: var(--text-secondary);
}

/* 近期战绩 */
.player-history {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: var(--bg-tertiary);
  border-radius: 6px;
  font-size: 11px;
  border-left: 2px solid #c45c5c;
}

.history-item.win {
  border-left-color: #3d9b7a;
}

.win-status {
  width: 14px;
  color: #c45c5c;
  font-weight: 600;
}

.win-status.win {
  color: #3d9b7a;
}

.history-champ {
  width: 20px;
  height: 20px;
  border-radius: 50%;
}

.history-kda {
  color: var(--text-secondary);
  font-size: 10px;
}

/* 右侧标签 */
.player-right {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100px;
  flex-shrink: 0;
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.pre-group-tag {
  padding: 2px 6px;
  font-size: 10px;
  border-radius: 4px;
  font-weight: 600;
}

.pre-group-tag.success {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.pre-group-tag.warning {
  background: rgba(242, 191, 99, 0.2);
  color: #f2bf63;
}

.pre-group-tag.error {
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
}

.pre-group-tag.info {
  background: rgba(92, 163, 234, 0.2);
  color: #5ca3ea;
}

.meet-tag {
  padding: 2px 6px;
  font-size: 10px;
  border-radius: 4px;
  background: rgba(242, 191, 99, 0.2);
  color: #f2bf63;
}

.user-tag {
  padding: 2px 6px;
  font-size: 10px;
  border-radius: 4px;
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

/* 近期数据 */
.recent-stats {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  background: var(--bg-tertiary);
  border-radius: 6px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
}

.stat-label {
  color: var(--text-tertiary);
}

.stat-value {
  font-weight: 600;
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
