<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useGameStore } from '@/stores/game'
import { apiClient } from '@/api/httpClient'
import type { Summoner, Rank, QueueInfo, MatchHistory, Participant, GameDetail, UserTag, ChampionOption, GameModeOption, WinRate } from '@/types/api'
import MatchDetailModal from '@/components/summoner/MatchDetailModal.vue'

// 导入段位图标
import unranked from '@/assets/imgs/tier/unranked.png'
import iron from '@/assets/imgs/tier/iron.png'
import bronze from '@/assets/imgs/tier/bronze.png'
import silver from '@/assets/imgs/tier/silver.png'
import gold from '@/assets/imgs/tier/gold.png'
import platinum from '@/assets/imgs/tier/platinum.png'
import emerald from '@/assets/imgs/tier/emerald.png'
import diamond from '@/assets/imgs/tier/diamond.png'
import master from '@/assets/imgs/tier/master.png'
import grandmaster from '@/assets/imgs/tier/grandmaster.png'
import challenger from '@/assets/imgs/tier/challenger.png'

// 段位图标映射
const tierIconMap: Record<string, string> = {
  unranked,
  iron,
  bronze,
  silver,
  gold,
  platinum,
  emerald,
  diamond,
  master,
  grandmaster,
  challenger
}

// 段位中文名称映射
const tierCnMap: Record<string, string> = {
  unranked: '无',
  iron: '坚韧黑铁',
  bronze: '英勇黄铜',
  silver: '不屈白银',
  gold: '荣耀黄金',
  platinum: '华贵铂金',
  emerald: '流光翡翠',
  diamond: '璀璨钻石',
  master: '超凡大师',
  grandmaster: '傲世宗师',
  challenger: '最强王者'
}

const gameStore = useGameStore()
const route = useRoute()

const searchName = ref('')
const searchResult = ref<Summoner | null>(null)
const searchRank = ref<Rank | null>(null)
const searchMatchHistory = ref<MatchHistory[]>([])
const searchUserTag = ref<UserTag | null>(null)
const searchRankedWinRates = ref<Record<string, WinRate> | null>(null)
const loading = ref(false)
const error = ref('')

// 筛选选项
const championOptions = ref<ChampionOption[]>([])
const modeOptions = ref<GameModeOption[]>([])
const filterChampionId = ref(-1)
const filterQueueId = ref(0)

// 分页
const currentPage = ref(1)
const pageSize = 10
const maxTotalRecords = 50 // LCU API 最多支持获取50条记录
const totalPages = computed(() => Math.ceil(maxTotalRecords / pageSize))

// 对局详情缓存（用于队伍头像展示）
const gameDetailsCache = ref<Record<number, GameDetail>>({})

// 对局详情弹窗
const showDetailModal = ref(false)
const selectedGameDetail = ref<GameDetail | null>(null)
const selectedMatchHistory = ref<MatchHistory | null>(null)
const loadingDetail = ref(false)

// 搜索结果的段位
const searchSoloRank = computed((): QueueInfo | null =>
  searchRank.value?.queueMap?.RANKED_SOLO_5x5 || null
)
const searchFlexRank = computed((): QueueInfo | null =>
  searchRank.value?.queueMap?.RANKED_FLEX_SR || null
)

async function searchSummoner() {
  if (!searchName.value.trim()) return

  loading.value = true
  error.value = ''
  searchResult.value = null
  searchRank.value = null
  searchMatchHistory.value = []
  searchUserTag.value = null
  searchRankedWinRates.value = null
  currentPage.value = 1
  gameDetailsCache.value = {} // 清空缓存

  try {
    const summoner = await gameStore.fetchSummonerByName(searchName.value)
    if (summoner) {
      searchResult.value = summoner
      // 并行获取所有数据
      const [rank, matches, userTag, rankedWinRates] = await Promise.all([
        gameStore.fetchRank(summoner.puuid),
        apiClient.getMatchHistory(summoner.puuid, 0, pageSize - 1),
        apiClient.getUserTagByPuuid(summoner.puuid, 0),
        apiClient.getRankedWinRates(summoner.puuid)
      ])
      searchRank.value = rank
      searchMatchHistory.value = matches
      searchUserTag.value = userTag
      searchRankedWinRates.value = rankedWinRates

      // 异步加载每场比赛的详情（用于队伍头像）
      loadGameDetailsAsync()
    } else {
      error.value = '未找到召唤师'
    }
  } catch (e) {
    error.value = '查询失败，请检查名称是否正确'
  } finally {
    loading.value = false
  }
}

// 筛选变化时重新加载
async function handleFilterChange() {
  if (!searchResult.value) return
  currentPage.value = 1
  await loadMatchHistory()
}

// 重置筛选
function resetFilter() {
  filterChampionId.value = -1
  filterQueueId.value = 0
  currentPage.value = 1
  if (searchResult.value) {
    loadMatchHistory()
  }
}

// 加载战绩
async function loadMatchHistory() {
  if (!searchResult.value) return

  loading.value = true
  gameDetailsCache.value = {} // 清空缓存
  try {
    const begIndex = (currentPage.value - 1) * pageSize
    const endIndex = Math.min(begIndex + pageSize - 1, maxTotalRecords - 1)

    if (filterChampionId.value > 0 || filterQueueId.value > 0) {
      searchMatchHistory.value = await apiClient.getFilteredMatchHistory(
        searchResult.value.puuid,
        {
          begIndex,
          endIndex,
          championId: filterChampionId.value > 0 ? filterChampionId.value : undefined,
          queueId: filterQueueId.value > 0 ? filterQueueId.value : undefined
        }
      )
    } else {
      searchMatchHistory.value = await apiClient.getMatchHistory(
        searchResult.value.puuid,
        begIndex,
        endIndex
      )
    }

    // 异步加载每场比赛的详情（用于队伍头像）
    loadGameDetailsAsync()
  } catch (e) {
    console.error('加载战绩失败', e)
  } finally {
    loading.value = false
  }
}

// 异步加载对局详情（不阻塞UI）
function loadGameDetailsAsync() {
  for (const match of searchMatchHistory.value) {
    if (!gameDetailsCache.value[match.gameId]) {
      apiClient.getGameDetail(match.gameId)
        .then(detail => {
          // 使用展开运算符触发 Vue 响应式更新
          gameDetailsCache.value = {
            ...gameDetailsCache.value,
            [match.gameId]: detail
          }
        })
        .catch(e => {
          console.warn('加载对局详情失败', match.gameId, e)
        })
    }
  }
}

// 获取队伍英雄列表
function getTeamChampions(gameId: number, teamId: number): { championId: number; puuid: string; gameName: string; tagLine: string }[] {
  const detail = gameDetailsCache.value[gameId]
  if (!detail) return []

  return detail.participants
    .filter(p => p.teamId === teamId)
    .map(p => {
      const identity = detail.participantIdentities.find(i => i.participantId === p.participantId)
      return {
        championId: p.championId,
        puuid: identity?.player?.puuid || '',
        gameName: identity?.player?.gameName || '',
        tagLine: identity?.player?.tagLine || ''
      }
    })
}

// 上一页
async function prevPage() {
  if (currentPage.value <= 1) return
  currentPage.value--
  await loadMatchHistory()
}

// 下一页
async function nextPage() {
  if (currentPage.value >= totalPages.value) return
  currentPage.value++
  await loadMatchHistory()
}

// 获取召唤师显示名称
function getSummonerName(summoner: Summoner | null): string {
  if (!summoner) return ''
  return summoner.tagLine
    ? `${summoner.gameName}#${summoner.tagLine}`
    : summoner.gameName
}

// 计算胜率
function getWinRate(wins: number, losses: number): number {
  const total = wins + losses
  if (total === 0) return 0
  return Math.round((wins / total) * 100)
}

// 格式化时间
function formatDuration(seconds: number): string {
  const min = Math.floor(seconds / 60)
  const sec = seconds % 60
  return `${min}:${sec.toString().padStart(2, '0')}`
}

function formatShortDate(timestamp: number): string {
  const date = new Date(timestamp)
  return `${date.getMonth() + 1}/${date.getDate()}`
}

// 颜色计算函数
function getKdaColor(kda: number): string {
  if (kda >= 2.6) return '#3d9b7a'
  if (kda <= 1.3) return '#c45c5c'
  return '#888888'
}

function getKillsColor(kills: number): string {
  if (kills >= 8) return '#3d9b7a'
  if (kills <= 3) return '#c45c5c'
  return '#888888'
}

function getDeathsColor(deaths: number): string {
  if (deaths >= 8) return '#c45c5c'
  if (deaths <= 3) return '#3d9b7a'
  return '#888888'
}

function getAssistsColor(assists: number): string {
  if (assists >= 10) return '#3d9b7a'
  if (assists <= 3) return '#c45c5c'
  return '#888888'
}

function getWinRateColor(rate: number): string {
  if (rate >= 58) return '#3d9b7a'
  if (rate <= 49) return '#c45c5c'
  return '#888888'
}

function getOtherColor(rate: number): string {
  if (rate >= 25) return '#3d9b7a'
  if (rate <= 15) return '#c45c5c'
  return '#888888'
}

function getHealColor(rate: number): string {
  if (rate >= 25) return '#3d9b7a'
  return '#888888'
}

// 获取英雄图片URL，处理无效ID
function getChampionUrl(championId: number | undefined): string {
  if (!championId || championId <= 0) {
    return '' // 返回空字符串，使用CSS背景占位
  }
  return `http://127.0.0.1:8080/api/v1/asset/champion/${championId}`
}

// 获取召唤师技能图片URL
function getSpellUrl(spellId: number | undefined): string {
  if (!spellId || spellId <= 0) {
    return ''
  }
  return `http://127.0.0.1:8080/api/v1/asset/spell/${spellId}`
}

// 获取装备图片URL
function getItemUrl(itemId: number | undefined): string {
  if (!itemId || itemId <= 0) {
    return ''
  }
  return `http://127.0.0.1:8080/api/v1/asset/item/${itemId}`
}

// 图片加载失败处理
function handleImageError(event: Event): void {
  const target = event.target as HTMLImageElement
  target.style.display = 'none'
}

// 格式化数字
function formatNumber(num: number | undefined): string {
  if (num === undefined || num === null) return '0'
  if (num >= 1000000) return (num / 1000000).toFixed(1) + 'm'
  if (num >= 1000) return (num / 1000).toFixed(num >= 10000 ? 1 : 2).replace(/\.0$/, '') + 'k'
  return num.toString()
}

// 获取该召唤师在对局中的数据
function getPlayerInMatch(match: MatchHistory, puuid: string): (Participant & { playerName: string }) | null {
  const identity = match.participantIdentities?.find(p => p.player?.puuid === puuid)
  if (!identity) return null

  const participant = match.participants?.find(p => p.participantId === identity.participantId)
  if (!participant) return null

  const playerName = identity.player?.gameName
    ? (identity.player.tagLine ? `${identity.player.gameName}#${identity.player.tagLine}` : identity.player.gameName)
    : identity.player?.summonerName || '未知'

  return { ...participant, playerName }
}

// 判断对局是否获胜
function isMatchWin(match: MatchHistory, puuid: string): boolean {
  const player = getPlayerInMatch(match, puuid)
  return player?.stats?.win ?? false
}

// 计算KDA
function calculateKda(kills: number, deaths: number, assists: number): number {
  if (deaths === 0) return kills + assists
  return (kills + assists) / deaths
}

// 点击查看对局详情
async function showMatchDetail(match: MatchHistory) {
  showDetailModal.value = true
  loadingDetail.value = true
  selectedGameDetail.value = null
  selectedMatchHistory.value = match

  try {
    const detail = await apiClient.getGameDetail(match.gameId)
    selectedGameDetail.value = detail
  } catch (e) {
    console.error('获取对局详情失败', e)
  } finally {
    loadingDetail.value = false
  }
}

// 关闭详情弹窗
function closeDetailModal() {
  showDetailModal.value = false
  selectedGameDetail.value = null
  selectedMatchHistory.value = null
}

// 计算KDA比率字符串
// 计算KDA数值
function getKdaValue(match: MatchHistory, puuid: string): number {
  const player = getPlayerInMatch(match, puuid)
  if (!player?.stats) return 0
  return calculateKda(player.stats.kills || 0, player.stats.deaths || 1, player.stats.assists || 0)
}

// 获取装备ID列表
function getItemIds(stats?: Participant['stats']): number[] {
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

// 获取MVP标记
function getMvpText(stats?: Participant['stats']): string {
  if (!stats?.mvp) return ''
  return stats.mvp === 'MVP' ? 'MVP' : stats.mvp === 'SVP' ? 'SVP' : ''
}

// 复制名称
function copyName() {
  if (!searchResult.value) return
  const name = getSummonerName(searchResult.value)
  navigator.clipboard.writeText(name)
}

// 加载筛选选项
async function loadFilterOptions() {
  try {
    const [champions, modes] = await Promise.all([
      apiClient.getChampionOptions(),
      apiClient.getGameModes()
    ])
    championOptions.value = champions
    modeOptions.value = [{ id: 0, name: '全部' }, ...modes]
  } catch (e) {
    console.error('加载筛选选项失败', e)
  }
}

// 点击队伍头像跳转到其他玩家
function navigateToPlayer(gameName: string, tagLine: string) {
  if (!gameName) return
  const name = tagLine ? `${gameName}#${tagLine}` : gameName
  searchName.value = name
  searchSummoner()
}

// 段位图标映射
function getTierIcon(tier: string): string {
  const tierLower = tier?.toLowerCase() || 'unranked'
  return tierIconMap[tierLower] || tierIconMap['unranked']
}

// 段位中文名称
function getTierCn(tier: string): string {
  const tierLower = tier?.toLowerCase() || 'unranked'
  return tierCnMap[tierLower] || '无'
}

// 段位显示
function getDivisionOrPoint(queueInfo: QueueInfo | null): string {
  if (!queueInfo) return ''
  if (['MASTER', 'GRANDMASTER', 'CHALLENGER'].includes(queueInfo.tier)) {
    return `${queueInfo.leaguePoints}LP`
  }
  return `${queueInfo.division} ${queueInfo.leaguePoints}LP`
}

onMounted(async () => {
  gameStore.refreshSummoner()
  await loadFilterOptions()

  // 检查 URL 参数，自动搜索
  const queryName = route.query.name as string
  if (queryName) {
    searchName.value = queryName
    await searchSummoner()
  }
})

// 监听 URL 参数变化（用户已在页面时点击玩家名称）
watch(
  () => route.query.name,
  async (newName) => {
    if (newName && typeof newName === 'string') {
      searchName.value = newName
      await searchSummoner()
    }
  }
)
</script>

<template>
  <div class="summoner-view">
    <div class="page-header">
      <h1>战绩查询</h1>
    </div>

    <!-- 搜索框 -->
    <div class="search-section">
      <div class="search-box">
        <input
          v-model="searchName"
          type="text"
          placeholder="输入召唤师名称（如：游戏名#标签）"
          @keyup.enter="searchSummoner"
        />
        <button @click="searchSummoner" :disabled="loading">
          {{ loading ? '搜索中...' : '搜索' }}
        </button>
      </div>
      <p v-if="error" class="error-msg">{{ error }}</p>
    </div>

    <!-- 搜索结果 -->
    <div v-if="searchResult" class="result-container">
      <!-- 左侧：玩家信息 -->
      <div class="user-panel">
        <!-- 玩家卡片 -->
        <div class="user-card">
          <div class="user-card-header">
            <div class="avatar-wrapper">
              <img
                class="avatar-img"
                :src="`http://127.0.0.1:8080/api/v1/asset/profile/${searchResult.profileIconId}`"
                alt="avatar"
              />
              <div class="level-badge">{{ searchResult.summonerLevel }}</div>
            </div>
            <div class="user-info">
              <div class="user-name-row">
                <span class="user-name">{{ searchResult.gameName }}</span>
                <button class="copy-btn" @click="copyName" title="复制">📋</button>
              </div>
              <div class="user-tag">
                <span>#{{ searchResult.tagLine }}</span>
              </div>
            </div>
          </div>

          <!-- 标签 -->
          <div v-if="searchUserTag?.tag?.length" class="tags-row">
            <span
              v-for="tag in searchUserTag.tag"
              :key="tag.tagName"
              class="tag"
              :class="tag.good ? 'good' : tag.good === false ? 'bad' : 'neutral'"
              :title="tag.tagDesc"
            >
              {{ tag.tagName }}
            </span>
          </div>
        </div>

        <!-- 好友/宿敌 -->
        <div v-if="searchUserTag?.recentData?.friendAndDispute" class="relationship-section">
          <div class="relationship-col">
            <div class="section-header good">
              <span>👥 好友/胜率</span>
            </div>
            <div class="relationship-list">
              <div
                v-for="friend in searchUserTag.recentData.friendAndDispute.friendsSummoner.slice(0, 5)"
                :key="friend.summoner.puuid"
                class="relationship-item"
              >
                <img
                  class="relationship-avatar"
                  :src="`http://127.0.0.1:8080/api/v1/asset/profile/${friend.summoner.profileIconId}`"
                />
                <span class="relationship-name">{{ friend.summoner.gameName }}</span>
                <span class="relationship-rate" :style="{ color: getWinRateColor(friend.winRate) }">
                  {{ friend.winRate }}%
                </span>
              </div>
              <div v-if="searchUserTag.recentData.friendAndDispute.friendsSummoner.length === 0" class="empty-text">
                暂无数据
              </div>
            </div>
          </div>

          <div class="relationship-col">
            <div class="section-header bad">
              <span>⚡ 宿敌/胜率</span>
            </div>
            <div class="relationship-list">
              <div
                v-for="dispute in searchUserTag.recentData.friendAndDispute.disputeSummoner.slice(0, 5)"
                :key="dispute.summoner.puuid"
                class="relationship-item"
              >
                <img
                  class="relationship-avatar"
                  :src="`http://127.0.0.1:8080/api/v1/asset/profile/${dispute.summoner.profileIconId}`"
                />
                <span class="relationship-name">{{ dispute.summoner.gameName }}</span>
                <span class="relationship-rate" :style="{ color: getWinRateColor(dispute.winRate) }">
                  {{ dispute.winRate }}%
                </span>
              </div>
              <div v-if="searchUserTag.recentData.friendAndDispute.disputeSummoner.length === 0" class="empty-text">
                暂无数据
              </div>
            </div>
          </div>
        </div>

        <!-- 段位卡片 -->
        <div class="rank-cards">
          <div class="rank-card">
            <div class="rank-icon-area">
              <span class="rank-label">单双排</span>
              <img
                class="rank-img"
                :src="getTierIcon(searchSoloRank?.tier || '')"
                alt="tier"
              />
              <div class="rank-tier">{{ getTierCn(searchSoloRank?.tier || '') }}</div>
              <div class="rank-division" v-if="searchSoloRank">
                {{ getDivisionOrPoint(searchSoloRank) }}
              </div>
            </div>
            <div class="rank-stats">
              <div class="win-rate-badge" :class="{ 'good': getWinRate(searchRankedWinRates?.RANKED_SOLO_5x5?.wins || 0, searchRankedWinRates?.RANKED_SOLO_5x5?.losses || 0) >= 58, 'bad': getWinRate(searchRankedWinRates?.RANKED_SOLO_5x5?.wins || 0, searchRankedWinRates?.RANKED_SOLO_5x5?.losses || 0) <= 49 }">
                胜率 {{ getWinRate(searchRankedWinRates?.RANKED_SOLO_5x5?.wins || 0, searchRankedWinRates?.RANKED_SOLO_5x5?.losses || 0) }}%
              </div>
              <div class="rank-wl">
                <span>胜: {{ searchRankedWinRates?.RANKED_SOLO_5x5?.wins || 0 }}</span>
                <span>负: {{ searchRankedWinRates?.RANKED_SOLO_5x5?.losses || 0 }}</span>
              </div>
            </div>
          </div>

          <div class="rank-card">
            <div class="rank-icon-area">
              <span class="rank-label">灵活组排</span>
              <img
                class="rank-img"
                :src="getTierIcon(searchFlexRank?.tier || '')"
                alt="tier"
              />
              <div class="rank-tier">{{ getTierCn(searchFlexRank?.tier || '') }}</div>
              <div class="rank-division" v-if="searchFlexRank">
                {{ getDivisionOrPoint(searchFlexRank) }}
              </div>
            </div>
            <div class="rank-stats">
              <div class="win-rate-badge" :class="{ 'good': getWinRate(searchRankedWinRates?.RANKED_FLEX_SR?.wins || 0, searchRankedWinRates?.RANKED_FLEX_SR?.losses || 0) >= 58, 'bad': getWinRate(searchRankedWinRates?.RANKED_FLEX_SR?.wins || 0, searchRankedWinRates?.RANKED_FLEX_SR?.losses || 0) <= 49 }">
                胜率 {{ getWinRate(searchRankedWinRates?.RANKED_FLEX_SR?.wins || 0, searchRankedWinRates?.RANKED_FLEX_SR?.losses || 0) }}%
              </div>
              <div class="rank-wl">
                <span>胜: {{ searchRankedWinRates?.RANKED_FLEX_SR?.wins || 0 }}</span>
                <span>负: {{ searchRankedWinRates?.RANKED_FLEX_SR?.losses || 0 }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 最近表现 -->
        <div v-if="searchUserTag?.recentData" class="recent-stats-card">
          <div class="recent-stats-header">
            <span class="recent-stats-title">最近表现</span>
          </div>

          <div class="stat-row">
            <span class="stat-label">KDA</span>
            <div class="stat-value-group">
              <span class="stat-kda-main" :style="{ color: getKdaColor(searchUserTag.recentData.kda) }">
                {{ searchUserTag.recentData.kda.toFixed(1) }}
              </span>
              <span class="stat-detail">
                <span :style="{ color: getKillsColor(searchUserTag.recentData.kills) }">{{ searchUserTag.recentData.kills.toFixed(1) }}</span>
                /
                <span :style="{ color: getDeathsColor(searchUserTag.recentData.deaths) }">{{ searchUserTag.recentData.deaths.toFixed(1) }}</span>
                /
                <span :style="{ color: getAssistsColor(searchUserTag.recentData.assists) }">{{ searchUserTag.recentData.assists.toFixed(1) }}</span>
              </span>
            </div>
          </div>

          <div class="stat-row">
            <span class="stat-label">胜率</span>
            <div class="stat-value-group">
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: getWinRate(searchUserTag.recentData.selectWins, searchUserTag.recentData.selectLosses) + '%', backgroundColor: getWinRateColor(getWinRate(searchUserTag.recentData.selectWins, searchUserTag.recentData.selectLosses)) }"></div>
              </div>
              <span class="stat-value" :style="{ color: getWinRateColor(getWinRate(searchUserTag.recentData.selectWins, searchUserTag.recentData.selectLosses)) }">
                {{ getWinRate(searchUserTag.recentData.selectWins, searchUserTag.recentData.selectLosses) }}%
              </span>
            </div>
          </div>

          <div class="stat-row">
            <span class="stat-label">参团率</span>
            <div class="stat-value-group">
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: (searchUserTag.recentData.groupRate || 0) + '%' }"></div>
              </div>
              <span class="stat-value">{{ searchUserTag.recentData.groupRate || 0 }}%</span>
            </div>
          </div>

          <div class="stat-row">
            <span class="stat-label">伤害</span>
            <span class="stat-raw">{{ formatNumber(searchUserTag.recentData.averageDamageDealtToChampions) }}</span>
            <div class="progress-bar small">
              <div class="progress-fill" :style="{ width: (searchUserTag.recentData.damageDealtToChampionsRate || 0) + '%' }"></div>
            </div>
            <span class="stat-value">{{ searchUserTag.recentData.damageDealtToChampionsRate || 0 }}%</span>
          </div>

          <div class="stat-row">
            <span class="stat-label">经济</span>
            <span class="stat-raw">{{ formatNumber(searchUserTag.recentData.averageGold) }}</span>
            <div class="progress-bar small">
              <div class="progress-fill" :style="{ width: (searchUserTag.recentData.goldRate || 0) + '%' }"></div>
            </div>
            <span class="stat-value">{{ searchUserTag.recentData.goldRate || 0 }}%</span>
          </div>
        </div>
      </div>

      <!-- 右侧：对局记录 -->
      <div class="match-panel">
        <div class="match-panel-header">
          <h3>最近对局</h3>
          <span class="match-count">共 {{ searchMatchHistory.length }} 场</span>
        </div>

        <!-- 筛选工具栏 -->
        <div class="filter-toolbar">
          <select v-model="filterQueueId" @change="handleFilterChange" class="filter-select">
            <option v-for="mode in modeOptions" :key="mode.id" :value="mode.id">
              {{ mode.name }}
            </option>
          </select>
          <select v-model="filterChampionId" @change="handleFilterChange" class="filter-select">
            <option :value="-1">全部英雄</option>
            <option v-for="champ in championOptions" :key="champ.value" :value="champ.value">
              {{ champ.label }}
            </option>
          </select>
          <button class="reset-btn" @click="resetFilter" title="重置">🔄</button>
        </div>

        <div class="match-list">
          <div
            v-for="match in searchMatchHistory"
            :key="match.gameId"
            class="match-card"
            :class="{ win: isMatchWin(match, searchResult!.puuid), loss: !isMatchWin(match, searchResult!.puuid) }"
            @click="showMatchDetail(match)"
          >
            <!-- 卡片头部：胜负/模式/时间 -->
            <div class="match-card-header" :class="{ win: isMatchWin(match, searchResult!.puuid), loss: !isMatchWin(match, searchResult!.puuid) }">
              <div class="match-header-left">
                <span class="match-result-tag">{{ isMatchWin(match, searchResult!.puuid) ? '胜利' : '失败' }}</span>
                <span class="match-queue-name">{{ match.queueName || match.gameMode }}</span>
              </div>
              <div class="match-header-right">
                <span class="match-duration">{{ formatDuration(match.gameDuration) }}</span>
                <span class="match-date-text">{{ formatShortDate(match.gameCreation) }}</span>
              </div>
            </div>

            <!-- 卡片主体 -->
            <div class="match-card-body">
              <!-- 英雄信息区 -->
              <div class="match-hero-section">
                <div class="match-hero-avatar">
                  <img
                    class="hero-img"
                    :src="getChampionUrl(getPlayerInMatch(match, searchResult!.puuid)?.championId)"
                    alt="champion"
                    @error="handleImageError"
                  />
                  <div
                    v-if="getMvpText(getPlayerInMatch(match, searchResult!.puuid)?.stats)"
                    class="mvp-tag"
                    :class="{ mvp: getMvpText(getPlayerInMatch(match, searchResult!.puuid)?.stats) === 'MVP', svp: getMvpText(getPlayerInMatch(match, searchResult!.puuid)?.stats) === 'SVP' }"
                  >
                    {{ getMvpText(getPlayerInMatch(match, searchResult!.puuid)?.stats) }}
                  </div>
                </div>
                <div class="match-hero-stats">
                  <div class="kda-display">
                    <span class="kda-num" :style="{ color: getKillsColor(getPlayerInMatch(match, searchResult!.puuid)?.stats?.kills || 0) }">
                      {{ getPlayerInMatch(match, searchResult!.puuid)?.stats?.kills }}
                    </span>
                    <span class="kda-sep">/</span>
                    <span class="kda-num" :style="{ color: getDeathsColor(getPlayerInMatch(match, searchResult!.puuid)?.stats?.deaths || 0) }">
                      {{ getPlayerInMatch(match, searchResult!.puuid)?.stats?.deaths }}
                    </span>
                    <span class="kda-sep">/</span>
                    <span class="kda-num" :style="{ color: getAssistsColor(getPlayerInMatch(match, searchResult!.puuid)?.stats?.assists || 0) }">
                      {{ getPlayerInMatch(match, searchResult!.puuid)?.stats?.assists }}
                    </span>
                    <span class="kda-ratio-text" :style="{ color: getKdaColor(getKdaValue(match, searchResult!.puuid)) }">
                      {{ getKdaValue(match, searchResult!.puuid).toFixed(1) }}
                    </span>
                  </div>
                  <div class="match-gold-cs">
                    <span>{{ formatNumber(getPlayerInMatch(match, searchResult!.puuid)?.stats?.goldEarned) }} 金币</span>
                    <span>{{ (getPlayerInMatch(match, searchResult!.puuid)?.stats?.neutralMinionsKilled || 0) + (getPlayerInMatch(match, searchResult!.puuid)?.stats?.minionsKilled || 0) }} 补兵</span>
                  </div>
                </div>
              </div>

              <!-- 装备技能区 -->
              <div class="match-build-section">
                <div class="build-top-row">
                  <div class="spells-mini">
                    <img
                      v-if="getPlayerInMatch(match, searchResult!.puuid)?.spell1Id"
                      class="spell-mini-img"
                      :src="getSpellUrl(getPlayerInMatch(match, searchResult!.puuid)?.spell1Id)"
                      @error="handleImageError"
                    />
                    <img
                      v-if="getPlayerInMatch(match, searchResult!.puuid)?.spell2Id"
                      class="spell-mini-img"
                      :src="getSpellUrl(getPlayerInMatch(match, searchResult!.puuid)?.spell2Id)"
                      @error="handleImageError"
                    />
                  </div>
                  <div class="runes-mini">
                    <img
                      v-if="getPlayerInMatch(match, searchResult!.puuid)?.stats?.perk0"
                      class="rune-mini-img"
                      :src="`http://127.0.0.1:8080/api/v1/asset/perk/${getPlayerInMatch(match, searchResult!.puuid)?.stats?.perk0}`"
                      @error="handleImageError"
                    />
                  </div>
                </div>
                <div class="items-mini">
                  <img
                    v-for="(itemId, idx) in getItemIds(getPlayerInMatch(match, searchResult!.puuid)?.stats)"
                    :key="idx"
                    class="item-mini-img"
                    :src="getItemUrl(itemId)"
                    @error="handleImageError"
                  />
                </div>
              </div>

              <!-- 数据统计区 -->
              <div class="match-damage-section">
                <div class="damage-stat">
                  <span class="damage-label">输出</span>
                  <span class="damage-value" :style="{ color: getOtherColor(getPlayerInMatch(match, searchResult!.puuid)?.stats?.damageDealtToChampionsRate || 0) }">
                    {{ formatNumber(getPlayerInMatch(match, searchResult!.puuid)?.stats?.totalDamageDealtToChampions) }}
                  </span>
                  <div class="damage-bar">
                    <div class="damage-fill damage" :style="{ width: Math.min(100, (getPlayerInMatch(match, searchResult!.puuid)?.stats?.damageDealtToChampionsRate || 0)) + '%' }"></div>
                  </div>
                </div>
                <div class="damage-stat">
                  <span class="damage-label">承伤</span>
                  <span class="damage-value" :style="{ color: getHealColor(getPlayerInMatch(match, searchResult!.puuid)?.stats?.damageTakenRate || 0) }">
                    {{ formatNumber(getPlayerInMatch(match, searchResult!.puuid)?.stats?.totalDamageTaken) }}
                  </span>
                  <div class="damage-bar">
                    <div class="damage-fill taken" :style="{ width: Math.min(100, (getPlayerInMatch(match, searchResult!.puuid)?.stats?.damageTakenRate || 0)) + '%' }"></div>
                  </div>
                </div>
              </div>

              <!-- 队伍头像区 -->
              <div class="match-teams-section" v-if="gameDetailsCache[match.gameId]">
                <div class="team-mini-row">
                  <div
                    v-for="(player, idx) in getTeamChampions(match.gameId, 100)"
                    :key="'t1-'+idx"
                    class="team-mini-avatar"
                    :class="{ 'is-me': player.puuid === searchResult!.puuid }"
                    @click.stop="navigateToPlayer(player.gameName, player.tagLine)"
                    :title="player.gameName + (player.tagLine ? '#' + player.tagLine : '')"
                  >
                    <img
                      class="team-mini-img"
                      :src="getChampionUrl(player.championId)"
                      @error="handleImageError"
                    />
                  </div>
                </div>
                <div class="team-mini-row">
                  <div
                    v-for="(player, idx) in getTeamChampions(match.gameId, 200)"
                    :key="'t2-'+idx"
                    class="team-mini-avatar"
                    :class="{ 'is-me': player.puuid === searchResult!.puuid }"
                    @click.stop="navigateToPlayer(player.gameName, player.tagLine)"
                    :title="player.gameName + (player.tagLine ? '#' + player.tagLine : '')"
                  >
                    <img
                      class="team-mini-img"
                      :src="getChampionUrl(player.championId)"
                      @error="handleImageError"
                    />
                  </div>
                </div>
              </div>
              <!-- 加载中占位 -->
              <div class="match-teams-section match-teams-loading" v-else>
                <div class="team-mini-row">
                  <div v-for="i in 5" :key="'lt1-'+i" class="team-mini-avatar loading-placeholder"></div>
                </div>
                <div class="team-mini-row">
                  <div v-for="i in 5" :key="'lt2-'+i" class="team-mini-avatar loading-placeholder"></div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 分页 -->
        <div class="pagination">
          <button class="page-btn" :disabled="currentPage <= 1" @click="prevPage">
            ◀ 上一页
          </button>
          <span class="page-num">第 {{ currentPage }} / {{ totalPages }} 页</span>
          <button class="page-btn" :disabled="currentPage >= totalPages" @click="nextPage">
            下一页 ▶
          </button>
        </div>
      </div>
    </div>

    <!-- 对局详情弹窗 -->
    <MatchDetailModal
      :visible="showDetailModal"
      :game-detail="selectedGameDetail"
      :match-history="selectedMatchHistory"
      :current-puuid="searchResult?.puuid || ''"
      :current-summoner-name="getSummonerName(searchResult)"
      @close="closeDetailModal"
      @navigate-to-player="navigateToPlayer"
    />

    <!-- 加载中状态 -->
    <div v-if="loadingDetail && showDetailModal" class="loading-overlay">
      <div class="loading-spinner">加载中...</div>
    </div>
  </div>
</template>

<style scoped>
.summoner-view {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0;
  color: var(--text-primary);
}

.search-section {
  margin-bottom: 24px;
}

.search-box {
  display: flex;
  gap: 12px;
}

.search-box input {
  flex: 1;
  padding: 12px 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  font-size: 14px;
}

.search-box input:focus {
  outline: none;
  border-color: var(--accent-color);
}

.search-box button {
  padding: 12px 24px;
  background: var(--accent-color);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.search-box button:hover:not(:disabled) {
  opacity: 0.9;
}

.search-box button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-msg {
  color: var(--error-color);
  margin-top: 8px;
  font-size: 14px;
}

/* 搜索结果布局 */
.result-container {
  display: flex;
  gap: 20px;
}

.user-panel {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.match-panel {
  flex: 1;
  min-width: 0;
}

.match-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.match-panel-header h3 {
  margin: 0;
  font-size: 18px;
  color: var(--text-primary);
}

.match-count {
  font-size: 13px;
  color: var(--text-tertiary);
}

/* 筛选工具栏 */
.filter-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.filter-select {
  padding: 6px 12px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
}

.filter-select:focus {
  outline: none;
  border-color: var(--accent-color);
}

.reset-btn {
  padding: 6px 10px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.reset-btn:hover {
  background: var(--bg-tertiary);
}

/* 用户卡片 */
.user-card {
  background: var(--bg-secondary);
  border-radius: 12px;
  padding: 16px;
}

.user-card-header {
  display: flex;
  gap: 16px;
}

.avatar-wrapper {
  position: relative;
  display: inline-block;
}

.avatar-img {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: 2px solid var(--border-color);
}

.level-badge {
  position: absolute;
  bottom: -4px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  padding: 0 8px;
  border-radius: 10px;
  font-size: 11px;
  color: var(--text-secondary);
}

.user-info {
  flex: 1;
}

.user-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.copy-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  opacity: 0.7;
}

.copy-btn:hover {
  opacity: 1;
}

.user-tag {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.tag {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.tag.good {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.tag.bad {
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
}

.tag.neutral {
  background: rgba(128, 128, 128, 0.2);
  color: var(--text-secondary);
}

/* 好友/宿敌 */
.relationship-section {
  display: flex;
  gap: 12px;
}

.relationship-col {
  flex: 1;
  background: var(--bg-secondary);
  border-radius: 10px;
  padding: 12px;
}

.section-header {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}

.section-header.good {
  color: #3d9b7a;
}

.section-header.bad {
  color: #c45c5c;
}

.relationship-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.relationship-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  background: var(--bg-tertiary);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.relationship-item:hover {
  background: var(--bg-elevated, rgba(255, 255, 255, 0.08));
}

.relationship-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
}

.relationship-name {
  flex: 1;
  font-size: 12px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.relationship-rate {
  font-size: 12px;
  font-weight: 700;
}

.empty-text {
  font-size: 12px;
  color: var(--text-tertiary);
  text-align: center;
  padding: 8px 0;
}

/* 段位卡片 */
.rank-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rank-card {
  background: var(--bg-secondary);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.rank-icon-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 80px;
  position: relative;
}

.rank-label {
  font-size: 10px;
  color: var(--text-tertiary);
  display: block;
}

.rank-img {
  width: 48px;
  height: 48px;
  object-fit: contain;
}

.rank-tier {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  margin-top: 2px;
}

.rank-division {
  font-size: 11px;
  color: var(--text-secondary);
}

.rank-stats {
  flex: 1;
}

.win-rate-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  background: rgba(128, 128, 128, 0.15);
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.win-rate-badge.good {
  background: rgba(61, 155, 122, 0.2);
  color: #3d9b7a;
}

.win-rate-badge.bad {
  background: rgba(196, 92, 92, 0.2);
  color: #c45c5c;
}

.rank-wl {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-tertiary);
}

/* 最近统计 */
.recent-stats-card {
  background: var(--bg-secondary);
  border-radius: 10px;
  padding: 14px;
}

.recent-stats-header {
  margin-bottom: 12px;
}

.recent-stats-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.stat-row {
  display: flex;
  align-items: center;
  font-size: 13px;
  margin-bottom: 10px;
}

.stat-row:last-child {
  margin-bottom: 0;
}

.stat-label {
  width: 60px;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.stat-value-group {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.stat-kda-main {
  font-size: 14px;
  font-weight: 700;
  min-width: 40px;
}

.stat-detail {
  font-size: 12px;
  color: var(--text-tertiary);
}

.stat-value {
  font-weight: 600;
  min-width: 45px;
  text-align: right;
}

.stat-raw {
  width: 50px;
  text-align: right;
  color: var(--text-secondary);
  font-size: 12px;
}

.progress-bar {
  flex: 1;
  height: 6px;
  background: rgba(128, 128, 128, 0.2);
  border-radius: 3px;
  overflow: hidden;
}

.progress-bar.small {
  flex: 0.6;
}

.progress-fill {
  height: 100%;
  background: var(--accent-color);
  border-radius: 3px;
  transition: width 0.3s;
}

/* 对局列表 */
.match-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.match-card {
  border: 1px solid var(--border-subtle, var(--border-color));
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  background: var(--bg-secondary);
}

.match-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

/* 卡片头部 */
.match-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 10px;
  color: #fff;
}

.match-card-header.win {
  background: linear-gradient(90deg, rgba(45, 138, 108, 0.88), rgba(45, 138, 108, 0.52));
}

.match-card-header.loss {
  background: linear-gradient(90deg, rgba(184, 66, 66, 0.88), rgba(184, 66, 66, 0.52));
}

.match-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.match-result-tag {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.2);
}

.match-queue-name {
  font-size: 14px;
  font-weight: 600;
}

.match-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  opacity: 0.95;
}

.match-duration {
  font-weight: 500;
}

.match-date-text {
  opacity: 0.85;
}

/* 卡片主体 */
.match-card-body {
  display: grid;
  grid-template-columns: minmax(120px, 1.2fr) minmax(140px, 1.4fr) minmax(100px, 1fr) minmax(80px, 0.8fr);
  gap: 12px;
  align-items: center;
  padding: 8px 10px;
}

/* 英雄信息区 */
.match-hero-section {
  display: flex;
  align-items: center;
  gap: 8px;
}

.match-hero-avatar {
  position: relative;
  flex-shrink: 0;
}

.hero-img {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  border: 1px solid var(--border-subtle, var(--border-color));
  background: var(--bg-tertiary);
}

.mvp-tag {
  position: absolute;
  left: 0;
  bottom: 0;
  font-size: 8px;
  font-weight: 700;
  padding: 1px 4px;
  border-radius: 3px;
  color: #000;
}

.mvp-tag.mvp {
  background: #FFD700;
}

.mvp-tag.svp {
  background: #FFFFFF;
}

.match-hero-stats {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kda-display {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 13px;
  font-weight: 600;
}

.kda-num {
  font-size: 13px;
  font-weight: 600;
}

.kda-sep {
  color: var(--text-tertiary);
  font-size: 12px;
}

.kda-ratio-text {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 600;
}

.match-gold-cs {
  display: flex;
  gap: 8px;
  font-size: 10px;
  color: var(--text-secondary);
}

/* 装备技能区 */
.match-build-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.build-top-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.spells-mini {
  display: flex;
  gap: 2px;
}

.spell-mini-img {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  border: 1px solid var(--border-subtle, var(--border-color));
  background: var(--bg-tertiary);
  object-fit: cover;
}

.runes-mini {
  display: flex;
  gap: 2px;
}

.rune-mini-img {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1px solid var(--border-subtle, var(--border-color));
  background: var(--bg-tertiary);
  object-fit: cover;
}

.items-mini {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}

.item-mini-img {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  border: 1px solid var(--border-subtle, var(--border-color));
  background: var(--bg-tertiary);
  object-fit: cover;
}

/* 数据统计区 */
.match-damage-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.damage-stat {
  display: flex;
  align-items: center;
  gap: 6px;
}

.damage-label {
  font-size: 10px;
  color: var(--text-secondary);
  width: 28px;
}

.damage-value {
  font-size: 11px;
  font-weight: 600;
  min-width: 45px;
  text-align: right;
}

.damage-bar {
  flex: 1;
  height: 4px;
  background: rgba(128, 128, 128, 0.2);
  border-radius: 2px;
  overflow: hidden;
}

.damage-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s;
}

.damage-fill.damage {
  background: linear-gradient(90deg, rgba(229, 167, 50, 0.8), rgba(229, 167, 50, 0.5));
}

.damage-fill.taken {
  background: linear-gradient(90deg, rgba(92, 163, 234, 0.8), rgba(92, 163, 234, 0.5));
}

/* 队伍头像区 */
.match-teams-section {
  display: flex;
  flex-direction: column;
  gap: 3px;
  justify-content: center;
}

.match-teams-loading {
  opacity: 0.5;
}

.team-mini-row {
  display: flex;
  gap: 2px;
  justify-content: center;
}

.team-mini-avatar {
  width: 18px;
  height: 18px;
  border-radius: 3px;
  background: var(--bg-tertiary);
  cursor: pointer;
  overflow: hidden;
  border: 1px solid transparent;
  transition: transform 0.1s, border-color 0.1s;
}

.team-mini-avatar:hover {
  transform: scale(1.15);
}

.team-mini-avatar.is-me {
  border-color: var(--accent-color);
  box-shadow: 0 0 4px var(--accent-color);
}

.team-mini-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.loading-placeholder {
  cursor: default;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.8; }
}

/* 分页 */
.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

.page-btn {
  padding: 8px 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}

.page-btn:hover:not(:disabled) {
  background: var(--bg-tertiary);
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-num {
  font-size: 13px;
  color: var(--text-secondary);
}

/* 加载遮罩 */
.loading-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}

.loading-spinner {
  background: var(--bg-secondary);
  padding: 20px 40px;
  border-radius: 8px;
  color: var(--text-primary);
}

@media (max-width: 900px) {
  .result-container {
    flex-direction: column;
  }

  .user-panel {
    width: 100%;
  }

  .relationship-section {
    flex-direction: column;
  }

  .match-card-body {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }

  .match-teams-section {
    grid-column: span 2;
  }
}
</style>
