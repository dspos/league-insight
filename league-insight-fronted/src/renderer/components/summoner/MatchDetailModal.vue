<template>
  <div v-if="visible && gameDetail && matchHistory" class="match-detail-overlay" @click.self="close">
    <div class="match-detail-modal">
      <div class="match-detail-shell">
        <!-- 头部 -->
        <div class="match-detail-header">
          <div class="match-detail-header-left">
            <div class="match-detail-title-row">
              <span class="match-detail-result" :class="{ win: myPlayer?.stats?.win }">
                {{ myPlayer?.stats?.win ? '胜利' : '失败' }}
              </span>
              <span class="match-detail-queue">{{ matchHistory.queueName || gameDetail.gameMode }}</span>
              <span class="match-detail-meta">{{ formatDate(gameDetail.gameCreation) }} · {{ formatDuration(gameDetail.gameDuration) }}</span>
            </div>
            <div class="match-detail-player-row">
              <img
                class="match-detail-hero"
                :src="getChampionUrl(myPlayer?.championId)"
                alt="champion"
                @error="handleImageError"
              />
              <div class="match-detail-player-copy">
                <div class="match-detail-player-name">{{ currentSummonerName }}</div>
                <div class="match-detail-player-kda">
                  <span class="kda-value" :style="{ color: getKillsColor(myPlayer?.stats?.kills || 0) }">{{ myPlayer?.stats?.kills }}</span>
                  <span>/</span>
                  <span class="kda-value" :style="{ color: getDeathsColor(myPlayer?.stats?.deaths || 0) }">{{ myPlayer?.stats?.deaths }}</span>
                  <span>/</span>
                  <span class="kda-value" :style="{ color: getAssistsColor(myPlayer?.stats?.assists || 0) }">{{ myPlayer?.stats?.assists }}</span>
                  <span class="kda-ratio" :style="{ color: getKdaColor(calculateKda(myPlayer?.stats?.kills || 0, myPlayer?.stats?.deaths || 1, myPlayer?.stats?.assists || 0)) }">
                    {{ calculateKda(myPlayer?.stats?.kills || 0, myPlayer?.stats?.deaths || 1, myPlayer?.stats?.assists || 0).toFixed(1) }} KDA
                  </span>
                  <span class="match-detail-meta">{{ formatNumber(myPlayer?.stats?.goldEarned) }} 金币</span>
                  <span class="match-detail-meta">{{ totalCs(myPlayer?.stats) }} 补兵</span>
                </div>
              </div>
            </div>
          </div>

          <div class="match-detail-summary-side">
            <div class="match-detail-summary-grid">
              <div class="match-detail-summary-card">
                <div class="match-detail-summary-label">输出</div>
                <div class="match-detail-summary-value">{{ formatNumber(myPlayer?.stats?.totalDamageDealtToChampions) }}</div>
              </div>
              <div class="match-detail-summary-card">
                <div class="match-detail-summary-label">承伤</div>
                <div class="match-detail-summary-value">{{ formatNumber(myPlayer?.stats?.totalDamageTaken) }}</div>
              </div>
              <div class="match-detail-summary-card">
                <div class="match-detail-summary-label">推塔</div>
                <div class="match-detail-summary-value">{{ formatNumber(myPlayer?.stats?.damageDealtToTurrets) }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 内容区域 -->
        <div class="match-detail-body">
          <!-- 蓝方 -->
          <section class="match-detail-team-section">
            <div class="match-detail-team-header" :class="{ win: blueTeamWin }">
              <div class="match-detail-team-title-wrap">
                <span class="match-detail-team-title">蓝方</span>
                <span class="match-detail-team-subtitle">
                  {{ blueTeamKda }} · {{ formatNumber(blueTeamGold) }} 金币
                </span>
              </div>
              <div class="match-detail-team-subtitle">
                输出 {{ formatNumber(blueTeamDamage) }} · 承伤 {{ formatNumber(blueTeamTaken) }}
              </div>
            </div>

            <div class="match-detail-column-header">
              <span>玩家</span>
              <span>装备 / 技能</span>
              <span>KDA</span>
              <span>金币</span>
              <span>补兵</span>
              <span>推塔</span>
              <span>输出/承伤/治疗</span>
            </div>

            <div class="match-detail-team-rows">
              <div
                v-for="player in blueTeamPlayers"
                :key="player.participantId"
                class="match-detail-row"
                :class="{ 'is-me': player.puuid === currentPuuid }"
                @click="handlePlayerClick(player)"
              >
                <div class="match-detail-player-cell">
                  <div class="match-detail-player-main">
                    <img
                      class="match-detail-player-avatar"
                      :src="getChampionUrl(player.championId)"
                      alt="champion"
                      @error="handleImageError"
                    />
                    <div class="match-detail-player-text">
                      <div class="match-detail-player-text-row">
                        <span class="match-detail-player-display">{{ getPlayerName(player) }}</span>
                        <span v-if="player.puuid === currentPuuid" class="me-tag">我</span>
                      </div>
                      <div class="match-detail-badge-row">
                        <span v-if="player.stats?.pentaKills" class="badge penta">五杀</span>
                        <span v-else-if="player.stats?.quadraKills" class="badge quadra">四杀</span>
                        <span v-else-if="player.stats?.tripleKills" class="badge triple">三杀</span>
                        <span v-if="isMvp(player)" class="badge mvp">MVP</span>
                        <span v-if="isSvp(player)" class="badge svp">SVP</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="match-detail-build-cell">
                  <div class="match-detail-build-topline">
                    <div class="match-detail-spells">
                      <img :src="getSpellUrl(player.spell1Id)" class="match-detail-spell-icon" alt="spell" @error="handleImageError" />
                      <img :src="getSpellUrl(player.spell2Id)" class="match-detail-spell-icon" alt="spell" @error="handleImageError" />
                    </div>
                    <!-- 海克斯强化显示 -->
                    <div v-if="isAugmentMode" class="match-detail-augments">
                      <span
                        v-for="augId in getAugmentIds(player.stats)"
                        :key="augId"
                        :class="['augment-icon-shell', getAugmentRarityClass(augId)]"
                      >
                        <img :src="getAugmentUrl(augId)" class="augment-icon" alt="augment" @error="handleImageError" />
                      </span>
                    </div>
                  </div>
                  <div class="match-detail-items">
                    <img
                      v-for="(itemId, idx) in getItemIds(player.stats)"
                      :key="idx"
                      :src="getItemUrl(itemId)"
                      class="match-detail-item-icon"
                      alt="item"
                      @error="handleImageError"
                    />
                  </div>
                </div>

                <div class="match-detail-value-cell kda-cell">
                  <span :style="{ color: getKillsColor(player.stats?.kills || 0) }">{{ player.stats?.kills }}</span>
                  <span class="sep">/</span>
                  <span :style="{ color: getDeathsColor(player.stats?.deaths || 0) }">{{ player.stats?.deaths }}</span>
                  <span class="sep">/</span>
                  <span :style="{ color: getAssistsColor(player.stats?.assists || 0) }">{{ player.stats?.assists }}</span>
                </div>
                <div class="match-detail-value-cell">{{ formatNumber(player.stats?.goldEarned) }}</div>
                <div class="match-detail-value-cell">{{ totalCs(player.stats) }}</div>
                <div class="match-detail-value-cell">{{ formatNumber(player.stats?.damageDealtToTurrets) }}</div>

                <div class="match-detail-dots-cell">
                  <StatDots
                    tooltip="对英雄伤害占比"
                    short-label="🔥"
                    :color="getOtherColor(getDamageRate(player))"
                    :icon-background="'rgba(229, 167, 50, 0.18)'"
                    :value="formatNumber(player.stats?.totalDamageDealtToChampions)"
                    :percent="getDamageRate(player)"
                  />
                  <StatDots
                    tooltip="承伤占比"
                    short-label="🛡️"
                    :color="getHealColor(getTakenRate(player))"
                    :icon-background="'rgba(92, 163, 234, 0.2)'"
                    :value="formatNumber(player.stats?.totalDamageTaken)"
                    :percent="getTakenRate(player)"
                  />
                  <StatDots
                    tooltip="治疗占比"
                    short-label="💚"
                    :color="getHealColor(getHealRate(player))"
                    :icon-background="'rgba(88, 182, 109, 0.2)'"
                    :value="formatNumber(player.stats?.totalHeal)"
                    :percent="getHealRate(player)"
                  />
                </div>
              </div>
            </div>
          </section>

          <!-- 红方 -->
          <section class="match-detail-team-section">
            <div class="match-detail-team-header" :class="{ win: !blueTeamWin }">
              <div class="match-detail-team-title-wrap">
                <span class="match-detail-team-title">红方</span>
                <span class="match-detail-team-subtitle">
                  {{ redTeamKda }} · {{ formatNumber(redTeamGold) }} 金币
                </span>
              </div>
              <div class="match-detail-team-subtitle">
                输出 {{ formatNumber(redTeamDamage) }} · 承伤 {{ formatNumber(redTeamTaken) }}
              </div>
            </div>

            <div class="match-detail-column-header">
              <span>玩家</span>
              <span>装备 / 技能</span>
              <span>KDA</span>
              <span>金币</span>
              <span>补兵</span>
              <span>推塔</span>
              <span>输出/承伤/治疗</span>
            </div>

            <div class="match-detail-team-rows">
              <div
                v-for="player in redTeamPlayers"
                :key="player.participantId"
                class="match-detail-row"
                :class="{ 'is-me': player.puuid === currentPuuid }"
                @click="handlePlayerClick(player)"
              >
                <div class="match-detail-player-cell">
                  <div class="match-detail-player-main">
                    <img
                      class="match-detail-player-avatar"
                      :src="getChampionUrl(player.championId)"
                      alt="champion"
                      @error="handleImageError"
                    />
                    <div class="match-detail-player-text">
                      <div class="match-detail-player-text-row">
                        <span class="match-detail-player-display">{{ getPlayerName(player) }}</span>
                        <span v-if="player.puuid === currentPuuid" class="me-tag">我</span>
                      </div>
                      <div class="match-detail-badge-row">
                        <span v-if="player.stats?.pentaKills" class="badge penta">五杀</span>
                        <span v-else-if="player.stats?.quadraKills" class="badge quadra">四杀</span>
                        <span v-else-if="player.stats?.tripleKills" class="badge triple">三杀</span>
                        <span v-if="isMvp(player)" class="badge mvp">MVP</span>
                        <span v-if="isSvp(player)" class="badge svp">SVP</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="match-detail-build-cell">
                  <div class="match-detail-build-topline">
                    <div class="match-detail-spells">
                      <img :src="getSpellUrl(player.spell1Id)" class="match-detail-spell-icon" alt="spell" @error="handleImageError" />
                      <img :src="getSpellUrl(player.spell2Id)" class="match-detail-spell-icon" alt="spell" @error="handleImageError" />
                    </div>
                    <!-- 海克斯强化显示 -->
                    <div v-if="isAugmentMode" class="match-detail-augments">
                      <span
                        v-for="augId in getAugmentIds(player.stats)"
                        :key="augId"
                        :class="['augment-icon-shell', getAugmentRarityClass(augId)]"
                      >
                        <img :src="getAugmentUrl(augId)" class="augment-icon" alt="augment" @error="handleImageError" />
                      </span>
                    </div>
                  </div>
                  <div class="match-detail-items">
                    <img
                      v-for="(itemId, idx) in getItemIds(player.stats)"
                      :key="idx"
                      :src="getItemUrl(itemId)"
                      class="match-detail-item-icon"
                      alt="item"
                      @error="handleImageError"
                    />
                  </div>
                </div>

                <div class="match-detail-value-cell kda-cell">
                  <span :style="{ color: getKillsColor(player.stats?.kills || 0) }">{{ player.stats?.kills }}</span>
                  <span class="sep">/</span>
                  <span :style="{ color: getDeathsColor(player.stats?.deaths || 0) }">{{ player.stats?.deaths }}</span>
                  <span class="sep">/</span>
                  <span :style="{ color: getAssistsColor(player.stats?.assists || 0) }">{{ player.stats?.assists }}</span>
                </div>
                <div class="match-detail-value-cell">{{ formatNumber(player.stats?.goldEarned) }}</div>
                <div class="match-detail-value-cell">{{ totalCs(player.stats) }}</div>
                <div class="match-detail-value-cell">{{ formatNumber(player.stats?.damageDealtToTurrets) }}</div>

                <div class="match-detail-dots-cell">
                  <StatDots
                    tooltip="对英雄伤害占比"
                    short-label="🔥"
                    :color="getOtherColor(getDamageRate(player))"
                    :icon-background="'rgba(229, 167, 50, 0.18)'"
                    :value="formatNumber(player.stats?.totalDamageDealtToChampions)"
                    :percent="getDamageRate(player)"
                  />
                  <StatDots
                    tooltip="承伤占比"
                    short-label="🛡️"
                    :color="getHealColor(getTakenRate(player))"
                    :icon-background="'rgba(92, 163, 234, 0.2)'"
                    :value="formatNumber(player.stats?.totalDamageTaken)"
                    :percent="getTakenRate(player)"
                  />
                  <StatDots
                    tooltip="治疗占比"
                    short-label="💚"
                    :color="getHealColor(getHealRate(player))"
                    :icon-background="'rgba(88, 182, 109, 0.2)'"
                    :value="formatNumber(player.stats?.totalHeal)"
                    :percent="getHealRate(player)"
                  />
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>

      <button class="close-btn" @click="close">&times;</button>

      <!-- AI 分析按钮 -->
      <button class="ai-btn" @click="openAiModal" title="AI 复盘">
        🤖
      </button>
    </div>

    <!-- AI 分析弹窗 -->
    <div v-if="showAiModal" class="ai-modal-overlay" @click.self="showAiModal = false">
      <div class="ai-modal">
        <div class="ai-modal-header">
          <h3>AI 复盘分析</h3>
          <button class="ai-modal-close" @click="showAiModal = false">&times;</button>
        </div>

        <div class="ai-modal-controls">
          <label>
            <input type="radio" value="overview" v-model="aiMode" />
            整局总览
          </label>
          <label>
            <input type="radio" value="player" v-model="aiMode" />
            单人复盘
          </label>
          <select v-if="aiMode === 'player'" v-model="aiTargetParticipantId">
            <option v-for="opt in aiPlayerOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
          <button class="ai-run-btn" @click="runAiAnalysis" :disabled="aiLoading">
            {{ aiLoading ? '分析中...' : '开始分析' }}
          </button>
        </div>

        <div class="ai-modal-content">
          <div v-if="aiLoading" class="ai-loading">
            <span class="loading-spinner"></span>
            AI 正在分析中...
          </div>
          <div v-else-if="aiError" class="ai-error">
            {{ aiError }}
          </div>
          <div v-else-if="aiResult" class="ai-result" v-html="renderedAiResult"></div>
          <div v-else class="ai-empty">
            选择分析类型后点击"开始分析"
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GameDetail, GameParticipant, MatchHistory, AIAnalysisResult } from '@/types/api'
import StatDots from '@/components/summoner/StatDots.vue'
import { apiClient } from '@/api/httpClient'

interface PlayerWithIdentity extends GameParticipant {
  puuid: string
  gameName: string
  tagLine: string
}

const props = defineProps<{
  visible: boolean
  gameDetail: GameDetail | null
  matchHistory: MatchHistory | null
  currentPuuid: string
  currentSummonerName: string
}>()

const emit = defineEmits<{
  close: []
  navigateToPlayer: [gameName: string, tagLine: string]
}>()

// AI 分析相关
const showAiModal = ref(false)
const aiMode = ref<'overview' | 'player'>('overview')
const aiTargetParticipantId = ref<number | null>(null)
const aiLoading = ref(false)
const aiResult = ref<string | null>(null)
const aiError = ref<string | null>(null)

// 是否是强化模式（竞技场/海克斯乱斗）
const isAugmentMode = computed(() => {
  const queueId = props.gameDetail?.queueId
  if (!queueId) return false
  // 1700: 斗魂竞技场, 2400: 海克斯大乱斗
  return queueId === 1700 || queueId === 2400
})

// 获取强化 ID 列表
function getAugmentIds(stats: any): number[] {
  const ids = [
    stats?.playerAugment1,
    stats?.playerAugment2,
    stats?.playerAugment3,
    stats?.playerAugment4
  ]
  return ids.filter((id): id is number => id != null && id > 0)
}

// 获取强化稀有度类名
function getAugmentRarityClass(augmentId: number): string {
  // 根据强化 ID 判断稀有度（简化判断）
  if (!augmentId) return ''
  // 实际项目中应该查询强化数据获取真实稀有度
  if (augmentId >= 10000) return 'augment-legendary'
  if (augmentId >= 5000) return 'augment-epic'
  return 'augment-common'
}

// AI 分析玩家选项
const aiPlayerOptions = computed(() => {
  return allPlayers.value.map(p => ({
    label: getPlayerName(p),
    value: p.participantId
  }))
})

// 运行 AI 分析
async function runAiAnalysis() {
  if (!props.gameDetail) return

  aiLoading.value = true
  aiError.value = null
  aiResult.value = null

  try {
    const result: AIAnalysisResult = await apiClient.analyzeGameDetail({
      gameId: props.gameDetail.gameId,
      mode: aiMode.value,
      participantId: aiTargetParticipantId.value ?? undefined
    })

    if (result.success && result.content) {
      aiResult.value = result.content
    } else {
      aiError.value = result.error || '分析失败'
    }
  } catch (e: any) {
    aiError.value = e.message || '网络请求失败'
  } finally {
    aiLoading.value = false
  }
}

// 渲染 AI 结果（简单的 Markdown 转 HTML）
const renderedAiResult = computed(() => {
  if (!aiResult.value) return ''
  return aiResult.value
    .replace(/## (.*)/g, '<h3>$1</h3>')
    .replace(/### (.*)/g, '<h4>$1</h4>')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/- (.*)/g, '<li>$1</li>')
    .replace(/\n\n/g, '</p><p>')
    .replace(/\n/g, '<br>')
})

// 打开 AI 分析弹窗
function openAiModal() {
  showAiModal.value = true
  aiResult.value = null
  aiError.value = null
}

// 获取当前玩家
const myPlayer = computed(() => {
  if (!props.gameDetail || !props.currentPuuid) return null
  return allPlayers.value.find(p => p.puuid === props.currentPuuid)
})

// 所有玩家（带身份信息）
const allPlayers = computed((): PlayerWithIdentity[] => {
  if (!props.gameDetail) return []
  return props.gameDetail.participants.map(p => {
    const identity = props.gameDetail!.participantIdentities.find(i => i.participantId === p.participantId)
    return {
      ...p,
      puuid: identity?.player?.puuid || '',
      gameName: identity?.player?.gameName || '',
      tagLine: identity?.player?.tagLine || ''
    }
  })
})

// 蓝方玩家
const blueTeamPlayers = computed(() => allPlayers.value.filter(p => p.teamId === 100))

// 红方玩家
const redTeamPlayers = computed(() => allPlayers.value.filter(p => p.teamId === 200))

// 蓝方是否获胜
const blueTeamWin = computed(() => {
  const bluePlayer = blueTeamPlayers.value[0]
  return bluePlayer?.stats?.win ?? false
})

// 队伍统计
const blueTeamKda = computed(() => {
  let k = 0, d = 0, a = 0
  blueTeamPlayers.value.forEach(p => {
    k += p.stats?.kills || 0
    d += p.stats?.deaths || 0
    a += p.stats?.assists || 0
  })
  return `${k}/${d}/${a}`
})

const redTeamKda = computed(() => {
  let k = 0, d = 0, a = 0
  redTeamPlayers.value.forEach(p => {
    k += p.stats?.kills || 0
    d += p.stats?.deaths || 0
    a += p.stats?.assists || 0
  })
  return `${k}/${d}/${a}`
})

const blueTeamGold = computed(() => {
  return blueTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.goldEarned || 0), 0)
})

const redTeamGold = computed(() => {
  return redTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.goldEarned || 0), 0)
})

const blueTeamDamage = computed(() => {
  return blueTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.totalDamageDealtToChampions || 0), 0)
})

const redTeamDamage = computed(() => {
  return redTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.totalDamageDealtToChampions || 0), 0)
})

const blueTeamTaken = computed(() => {
  return blueTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.totalDamageTaken || 0), 0)
})

const redTeamTaken = computed(() => {
  return redTeamPlayers.value.reduce((sum, p) => sum + (p.stats?.totalDamageTaken || 0), 0)
})

// 辅助函数
function totalCs(stats: any): number {
  return (stats?.totalMinionsKilled || 0) + (stats?.neutralMinionsKilled || 0)
}

function calculateKda(kills: number, deaths: number, assists: number): number {
  if (deaths === 0) return kills + assists
  return (kills + assists) / deaths
}

function formatNumber(num: any): string {
  if (!num) return '0'
  const n = Number(num)
  if (n >= 10000) return (n / 10000).toFixed(1) + '万'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return n.toString()
}

function formatDate(timestamp: number): string {
  const date = new Date(timestamp)
  return `${date.getMonth() + 1}/${date.getDate()} ${date.getHours()}:${String(date.getMinutes()).padStart(2, '0')}`
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function getPlayerName(player: PlayerWithIdentity): string {
  if (player.gameName) {
    return player.tagLine ? `${player.gameName}#${player.tagLine}` : player.gameName
  }
  const identity = props.gameDetail?.participantIdentities.find(i => i.participantId === player.participantId)
  return identity?.player?.summonerName || '未知'
}

function getItemIds(stats: any): number[] {
  return [
    stats?.item0 || 0,
    stats?.item1 || 0,
    stats?.item2 || 0,
    stats?.item3 || 0,
    stats?.item4 || 0,
    stats?.item5 || 0,
    stats?.item6 || 0
  ]
}

function isMvp(player: PlayerWithIdentity): boolean {
  return player.stats?.mvp === 'MVP'
}

function isSvp(player: PlayerWithIdentity): boolean {
  return player.stats?.mvp === 'SVP'
}

// 队伍伤害占比
function getDamageRate(player: PlayerWithIdentity): number {
  const teamTotal = player.teamId === 100 ? blueTeamDamage.value : redTeamDamage.value
  if (!teamTotal) return 0
  return ((player.stats?.totalDamageDealtToChampions || 0) / teamTotal) * 100
}

function getTakenRate(player: PlayerWithIdentity): number {
  const teamTotal = player.teamId === 100 ? blueTeamTaken.value : redTeamTaken.value
  if (!teamTotal) return 0
  return ((player.stats?.totalDamageTaken || 0) / teamTotal) * 100
}

function getHealRate(player: PlayerWithIdentity): number {
  const teamHeal = (player.teamId === 100 ? blueTeamPlayers.value : redTeamPlayers.value)
    .reduce((sum, p) => sum + (p.stats?.totalHeal || 0), 0)
  if (!teamHeal) return 0
  return ((player.stats?.totalHeal || 0) / teamHeal) * 100
}

// 颜色函数
function getKillsColor(kills: number): string {
  if (kills >= 10) return '#f2bf63'
  if (kills >= 5) return '#d4a84b'
  return 'var(--text-primary)'
}

function getDeathsColor(deaths: number): string {
  if (deaths === 0) return '#3d9b7a'
  if (deaths >= 5) return '#c45c5c'
  return 'var(--text-primary)'
}

function getAssistsColor(assists: number): string {
  if (assists >= 10) return '#63d8b4'
  if (assists >= 5) return '#5bc4a8'
  return 'var(--text-primary)'
}

function getKdaColor(kda: number): string {
  if (kda >= 5) return '#f2bf63'
  if (kda >= 3) return '#63d8b4'
  if (kda >= 1) return 'var(--text-primary)'
  return '#c45c5c'
}

function getOtherColor(rate: number): string {
  if (rate >= 30) return '#f2bf63'
  if (rate >= 20) return '#d4a84b'
  return 'var(--text-secondary)'
}

function getHealColor(rate: number): string {
  if (rate >= 30) return '#63d8b4'
  if (rate >= 20) return '#5bc4a8'
  return 'var(--text-secondary)'
}

// URL 辅助函数
function getChampionUrl(championId: number | undefined): string {
  if (!championId || championId <= 0) return ''
  return `http://127.0.0.1:8080/api/v1/asset/champion/${championId}`
}

function getSpellUrl(spellId: number | undefined): string {
  if (!spellId || spellId <= 0) return ''
  return `http://127.0.0.1:8080/api/v1/asset/spell/${spellId}`
}

function getItemUrl(itemId: number | undefined): string {
  if (!itemId || itemId <= 0) return ''
  return `http://127.0.0.1:8080/api/v1/asset/item/${itemId}`
}

function getAugmentUrl(augmentId: number | undefined): string {
  if (!augmentId || augmentId <= 0) return ''
  return `http://127.0.0.1:8080/api/v1/asset/augment/${augmentId}`
}

function handleImageError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

function close() {
  emit('close')
}

function handlePlayerClick(player: PlayerWithIdentity) {
  if (player.puuid === props.currentPuuid) return
  if (player.gameName) {
    emit('navigateToPlayer', player.gameName, player.tagLine)
    close()
  }
}
</script>

<style scoped>
.match-detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  z-index: 1000;
  padding: 40px 20px;
  overflow-y: auto;
}

.match-detail-modal {
  width: 100%;
  max-width: 1000px;
  background: var(--bg-secondary);
  border-radius: 12px;
  position: relative;
  display: flex;
  flex-direction: column;
  margin: auto 0;
}

.close-btn {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
  border-radius: 50%;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.match-detail-shell {
  display: flex;
  flex-direction: column;
}

/* 头部 */
.match-detail-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-tertiary);
}

.match-detail-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.match-detail-result {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
}

.match-detail-result.win {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.match-detail-queue {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.match-detail-meta {
  color: var(--text-tertiary);
  font-size: 12px;
}

.match-detail-player-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.match-detail-hero {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  border: 2px solid var(--border-color);
}

.match-detail-player-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.match-detail-player-kda {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-primary);
}

.kda-value {
  font-weight: 600;
}

.kda-ratio {
  margin-left: 4px;
  font-weight: 600;
}

.match-detail-summary-side {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.match-detail-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.match-detail-summary-card {
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.match-detail-summary-label {
  color: var(--text-tertiary);
  font-size: 11px;
  margin-bottom: 2px;
}

.match-detail-summary-value {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

/* 内容区域 */
.match-detail-body {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.match-detail-team-section {
  border: 1px solid var(--border-color);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-tertiary);
}

.match-detail-team-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  color: #fff;
  background: linear-gradient(90deg, rgba(184, 66, 66, 0.88), rgba(184, 66, 66, 0.52));
}

.match-detail-team-header.win {
  background: linear-gradient(90deg, rgba(45, 138, 108, 0.88), rgba(45, 138, 108, 0.52));
}

.match-detail-team-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.match-detail-team-title {
  font-size: 14px;
  font-weight: 700;
}

.match-detail-team-subtitle {
  font-size: 11px;
  opacity: 0.9;
}

.match-detail-column-header,
.match-detail-row {
  display: grid;
  grid-template-columns: 1.2fr 1.3fr 70px 60px 50px 60px 1.5fr;
  gap: 8px;
  align-items: center;
}

.match-detail-column-header {
  padding: 6px 12px;
  font-size: 11px;
  color: var(--text-tertiary);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.match-detail-team-rows {
  display: flex;
  flex-direction: column;
}

.match-detail-row {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  transition: background 0.15s;
}

.match-detail-row:last-child {
  border-bottom: none;
}

.match-detail-row:hover {
  background: var(--bg-elevated, rgba(255, 255, 255, 0.05));
}

.match-detail-row.is-me {
  background: rgba(92, 163, 234, 0.08);
}

.match-detail-player-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.match-detail-player-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  flex-shrink: 0;
}

.match-detail-player-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.match-detail-player-text-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.match-detail-player-display {
  font-weight: 600;
  font-size: 12px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.me-tag {
  padding: 1px 6px;
  font-size: 10px;
  background: rgba(92, 163, 234, 0.2);
  color: #5ca3ea;
  border-radius: 4px;
}

.match-detail-badge-row {
  display: flex;
  gap: 4px;
}

.badge {
  padding: 1px 5px;
  font-size: 10px;
  font-weight: 600;
  border-radius: 4px;
}

.badge.penta {
  background: rgba(242, 191, 99, 0.2);
  color: #f2bf63;
}

.badge.quadra {
  background: rgba(212, 168, 75, 0.2);
  color: #d4a84b;
}

.badge.triple {
  background: rgba(99, 216, 180, 0.2);
  color: #63d8b4;
}

.badge.mvp {
  background: rgba(242, 191, 99, 0.2);
  color: #f2bf63;
}

.badge.svp {
  background: rgba(92, 163, 234, 0.2);
  color: #5ca3ea;
}

/* 装备/技能 */
.match-detail-build-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.match-detail-build-topline {
  display: flex;
  gap: 4px;
}

.match-detail-spells {
  display: flex;
  gap: 2px;
}

.match-detail-spell-icon {
  width: 16px;
  height: 16px;
  border-radius: 3px;
}

.match-detail-items {
  display: flex;
  gap: 2px;
  flex-wrap: wrap;
}

.match-detail-item-icon {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  background: var(--bg-secondary);
}

/* 数值单元格 */
.match-detail-value-cell {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-primary);
  text-align: center;
}

.kda-cell .sep {
  color: var(--text-tertiary);
  margin: 0 1px;
}

/* StatDots */
.match-detail-dots-cell {
  display: flex;
  flex-direction: row;
  gap: 6px;
  min-width: 180px;
}

.match-detail-dots-cell :deep(.stat-dots-row) {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.match-detail-dots-cell :deep(.stat-dots-track) {
  display: none;
}

.match-detail-dots-cell :deep(.stat-dots-values) {
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
}

.match-detail-dots-cell :deep(.stat-dots-value-main) {
  width: auto;
  font-size: 11px;
}

.match-detail-dots-cell :deep(.stat-dots-value-percent) {
  width: auto;
  font-size: 10px;
}

/* 海克斯强化 */
.match-detail-augments {
  display: flex;
  gap: 2px;
  margin-left: 4px;
}

.augment-icon-shell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  background: rgba(128, 128, 128, 0.3);
}

.augment-icon-shell.augment-common {
  background: rgba(128, 128, 128, 0.3);
  border: 1px solid rgba(128, 128, 128, 0.5);
}

.augment-icon-shell.augment-epic {
  background: rgba(163, 53, 238, 0.3);
  border: 1px solid rgba(163, 53, 238, 0.5);
}

.augment-icon-shell.augment-legendary {
  background: rgba(242, 191, 99, 0.3);
  border: 1px solid rgba(242, 191, 99, 0.5);
}

.augment-icon {
  width: 14px;
  height: 14px;
  border-radius: 2px;
}

/* AI 分析按钮 */
.ai-btn {
  position: absolute;
  top: 12px;
  right: 52px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(99, 216, 180, 0.2);
  color: var(--text-primary);
  border-radius: 50%;
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}

.ai-btn:hover {
  background: rgba(99, 216, 180, 0.4);
}

/* AI 分析弹窗 */
.ai-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.ai-modal {
  width: 90%;
  max-width: 700px;
  max-height: 80vh;
  background: var(--bg-secondary);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-tertiary);
}

.ai-modal-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary);
}

.ai-modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
  border-radius: 50%;
  font-size: 18px;
  cursor: pointer;
}

.ai-modal-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border-color);
  flex-wrap: wrap;
}

.ai-modal-controls label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
}

.ai-modal-controls select {
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 13px;
}

.ai-run-btn {
  padding: 6px 16px;
  border: none;
  background: rgba(99, 216, 180, 0.2);
  color: #63d8b4;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.ai-run-btn:hover:not(:disabled) {
  background: rgba(99, 216, 180, 0.3);
}

.ai-run-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-modal-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.ai-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px;
  color: var(--text-secondary);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-color);
  border-top-color: #63d8b4;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.ai-error {
  color: #c45c5c;
  text-align: center;
  padding: 20px;
}

.ai-empty {
  color: var(--text-tertiary);
  text-align: center;
  padding: 40px;
}

.ai-result {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
}

.ai-result h3 {
  font-size: 15px;
  margin: 16px 0 8px 0;
  color: #63d8b4;
}

.ai-result h4 {
  font-size: 14px;
  margin: 12px 0 6px 0;
  color: var(--text-primary);
}

.ai-result li {
  margin-left: 16px;
  margin-bottom: 4px;
}

.ai-result strong {
  color: #f2bf63;
}
</style>
