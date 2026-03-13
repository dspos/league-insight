package com.ekko.insight.service;

import com.ekko.insight.constant.GameConstants;
import com.ekko.insight.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ekko.insight.constant.QueueType;
import com.ekko.insight.websocket.LcuWebSocketClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LCU 核心服务
 * 提供召唤师查询、段位查询、战绩查询等功能
 */
@Slf4j
@Service
public class LcuService {

    private final LcuHttpClient lcuHttpClient;
    private final LcuWebSocketClient webSocketClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserTagService userTagService;

    public LcuService(LcuHttpClient lcuHttpClient,
                      LcuWebSocketClient webSocketClient,
                      SimpMessagingTemplate messagingTemplate,
                      @Lazy UserTagService userTagService) {
        this.lcuHttpClient = lcuHttpClient;
        this.webSocketClient = webSocketClient;
        this.messagingTemplate = messagingTemplate;
        this.userTagService = userTagService;
    }

    // 缓存
    private final Cache<String, Summoner> summonerCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final Cache<String, Rank> rankCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // 战绩缓存：存储完整的 50 条数据，key 为 puuid
    private final Cache<String, MatchHistory[]> matchHistoryCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private volatile String currentPhase;
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        log.info("初始化 LCU 服务...");

        // 添加 WebSocket 事件监听
        webSocketClient.addListener(this::handleLcuEvent);

        // 启动连接检查
        startConnectionMonitor();
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭 LCU 服务...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        webSocketClient.disconnect();
    }

    /**
     * 启动连接监控
     */
    private void startConnectionMonitor() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean wasConnected = webSocketClient.isConnected();
                boolean nowConnected = checkAndConnect();

                // 状态变化时推送
                if (nowConnected != wasConnected) {
                    pushGameState(nowConnected);
                }
            } catch (Exception e) {
                log.error("连接监控错误: {}", e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * 检查并连接
     */
    private boolean checkAndConnect() {
        try {
            if (lcuHttpClient.isConnected()) {
                if (!webSocketClient.isConnected()) {
                    var authInfo = lcuHttpClient.getAuthInfo();
                    if (authInfo != null) {
                        webSocketClient.connect(authInfo);
                    }
                }
                return true;
            }
        } catch (Exception e) {
            log.debug("LCU 未连接: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 处理 LCU 事件
     */
    private void handleLcuEvent(LcuWebSocketClient.LcuEvent event) {
        String uri = event.uri();

        // 处理阶段变化
        if ("/lol-gameflow/v1/gameflow-phase".equals(uri)) {
            if (event.data() != null && event.data().isTextual()) {
                currentPhase = event.data().asText();
                log.info("游戏阶段变化: {}", currentPhase);
                pushGameState(true);
            }
        }

        // 处理选人会话变化
        if ("/lol-champ-select/v1/session".equals(uri)) {
            messagingTemplate.convertAndSend("/topic/champion-select", event.data());
        }

        // 处理大厅变化
        if ("/lol-lobby/v2/lobby".equals(uri)) {
            messagingTemplate.convertAndSend("/topic/lobby", event.data());
        }
    }

    /**
     * 推送游戏状态
     */
    private void pushGameState(boolean connected) {
        try {
            GameState state = new GameState();
            state.setConnected(connected);
            state.setPhase(currentPhase);

            if (connected) {
                try {
                    Summoner summoner = getMySummoner();
                    state.setSummoner(summoner);
                } catch (Exception e) {
                    log.debug("获取召唤师信息失败: {}", e.getMessage());
                }
            } else {
                currentPhase = null;
            }

            messagingTemplate.convertAndSend("/topic/game-state", state);
        } catch (Exception e) {
            log.error("推送游戏状态失败: {}", e.getMessage());
        }
    }

    // ========== 召唤师 API ==========

    /**
     * 获取当前登录的召唤师信息
     */
    public Summoner getMySummoner() {
        Summoner summoner = lcuHttpClient.get("lol-summoner/v1/current-summoner", Summoner.class);
        if (summoner != null && summoner.getPuuid() != null) {
            summonerCache.put(summoner.getPuuid(), summoner);
        }
        return summoner;
    }

    /**
     * 根据 PUUID 获取召唤师信息
     */
    public Summoner getSummonerByPuuid(String puuid) {
        return summonerCache.get(puuid, key -> {
            String uri = String.format("lol-summoner/v2/summoners/puuid/%s", puuid);
            return lcuHttpClient.get(uri, Summoner.class);
        });
    }

    /**
     * 根据名称获取召唤师信息
     */
    public Summoner getSummonerByName(String name) {
        return summonerCache.get(name, key -> {
            String encodedName = java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
            String uri = String.format("lol-summoner/v1/summoners/?name=%s", encodedName);
            return lcuHttpClient.get(uri, Summoner.class);
        });
    }

    // ========== 段位 API ==========

    /**
     * 获取召唤师段位信息
     */
    public Rank getRankByPuuid(String puuid) {
        return rankCache.get(puuid, key -> {
            String uri = String.format("lol-ranked/v1/ranked-stats/%s", key);
            Rank rank = lcuHttpClient.get(uri, Rank.class);
            // 调试日志 - 显示原始值
            if (rank != null && rank.getQueueMap() != null) {
                if (rank.getQueueMap().getRankedSolo5x5() != null) {
                    var solo = rank.getQueueMap().getRankedSolo5x5();
                    log.info("单双排段位原始数据 - tier: {}, wins: {}, losses: {}, games: {}",
                            solo.getTier(), solo.getWins(), solo.getLosses(), solo.getGames());
                }
                if (rank.getQueueMap().getRankedFlexSr() != null) {
                    var flex = rank.getQueueMap().getRankedFlexSr();
                    log.info("灵活组排段位原始数据 - tier: {}, wins: {}, losses: {}, games: {}",
                            flex.getTier(), flex.getWins(), flex.getLosses(), flex.getGames());
                }
            }
            return rank;
        });
    }

    // ========== 战绩 API ==========

    /**
     * 获取对局记录
     * 注意：LCU API 的 begIndex 参数不支持从任意位置开始，所以采用以下策略：
     * 1. 总是获取 0-49 的全部数据
     * 2. 在内存中根据 begIndex 和 endIndex 进行切片
     */
    public List<MatchHistory> getMatchHistory(String puuid, int begIndex, int endIndex) {
        // 从缓存获取完整的 50 条数据
        MatchHistory[] matches = matchHistoryCache.get(puuid, this::fetchMatchHistory);

        if (matches == null || matches.length == 0) {
            return List.of();
        }

        // 在内存中进行切片
        int beg = Math.max(0, begIndex);
        int end = Math.min(endIndex + 1, matches.length); // endIndex 是 inclusive 的

        if (beg >= end) {
            return List.of();
        }

        return Arrays.asList(Arrays.copyOfRange(matches, beg, end));
    }

    /**
     * 从 LCU API 获取战绩数据
     */
    private MatchHistory[] fetchMatchHistory(String puuid) {
        String uri = String.format("lol-match-history/v1/products/lol/%s/matches?begIndex=%d&endIndex=%d",
                puuid, 0, 49);

        JsonNode response = lcuHttpClient.get(uri, JsonNode.class);

        // API 返回格式: { "games": { "games": [...] } }
        if (response != null && response.has("games")) {
            JsonNode gamesWrapper = response.get("games");
            if (gamesWrapper != null && gamesWrapper.has("games")) {
                JsonNode games = gamesWrapper.get("games");
                return lcuHttpClient.getObjectMapper().convertValue(games, MatchHistory[].class);
            }
        }

        return new MatchHistory[0];
    }

    /**
     * 获取筛选后的对局记录
     * @param puuid 玩家 PUUID
     * @param begIndex 起始索引
     * @param endIndex 结束索引（最多49）
     * @param queueId 队列 ID（<=0 表示不过滤）
     * @param championId 英雄 ID（<=0 表示不过滤）
     * @param maxResults 最大返回数量
     * @return 筛选后的对局记录列表
     */
    public List<MatchHistory> getFilteredMatchHistory(String puuid, int begIndex, int endIndex,
                                                       Integer queueId, Integer championId, int maxResults) {
        // 从缓存获取数据
        MatchHistory[] allMatches = matchHistoryCache.get(puuid, this::fetchMatchHistory);

        if (allMatches == null || allMatches.length == 0) {
            return List.of();
        }

        // 先过滤
        List<MatchHistory> filteredMatches = new java.util.ArrayList<>();
        for (MatchHistory match : allMatches) {
            // 队列过滤
            boolean queueMatches = queueId == null || queueId <= 0 ||
                    (match.getQueueId() != null && match.getQueueId().equals(queueId));

            // 英雄过滤
            boolean championMatches = championId == null || championId <= 0;
            if (!championMatches && match.getParticipants() != null) {
                Integer participantId = findParticipantId(match, puuid);
                if (participantId != null) {
                    championMatches = match.getParticipants().stream()
                            .anyMatch(p -> participantId.equals(p.getParticipantId()) &&
                                    p.getChampionId() != null && p.getChampionId().equals(championId));
                }
            }

            if (queueMatches && championMatches) {
                filteredMatches.add(match);
            }
        }

        // 然后切片
        int beg = Math.max(0, begIndex);
        int end = Math.min(endIndex + 1, filteredMatches.size()); // endIndex 是 inclusive 的

        if (beg >= end) {
            return List.of();
        }

        return filteredMatches.subList(beg, end);
    }

    /**
     * 获取服务器名称
     * @param name 召唤师名称
     * @return 服务器中文名
     */
    public String getPlatformName(String name) {
        Summoner summoner = getSummonerByName(name);
        if (summoner == null || summoner.getPuuid() == null) {
            return "暂无";
        }

        List<MatchHistory> matches = getMatchHistory(summoner.getPuuid(), 0, 1);
        if (matches.isEmpty() || matches.getFirst().getPlatformId() == null) {
            return "暂无";
        }

        return GameConstants.getServerName(matches.getFirst().getPlatformId());
    }

    /**
     * 获取胜率统计
     * @param puuid 玩家 PUUID
     * @param mode 队列模式（<=0 表示全部）
     * @return 胜率统计
     */
    public WinRate getWinRate(String puuid, Integer mode) {
        List<MatchHistory> matches = getMatchHistory(puuid, 0, 49);

        int wins = 0;
        int losses = 0;

        for (MatchHistory match : matches) {
            // 模式过滤
            if (mode != null && mode > 0 && !mode.equals(match.getQueueId())) {
                continue;
            }

            // 查找当前玩家的数据
            Integer participantId = findParticipantId(match, puuid);
            if (participantId != null && match.getParticipants() != null) {
                for (MatchHistory.Participant p : match.getParticipants()) {
                    if (participantId.equals(p.getParticipantId()) && p.getStats() != null) {
                        if (Boolean.TRUE.equals(p.getStats().getWin())) {
                            wins++;
                        } else {
                            losses++;
                        }
                        break;
                    }
                }
            }
        }

        return WinRate.of(wins, losses);
    }

    /**
     * 获取排位胜率统计
     * @param puuid 玩家 PUUID
     * @return 包含单排和灵活组排胜率的统计
     */
    public Map<String, WinRate> getRankedWinRates(String puuid) {
        List<MatchHistory> matches = getMatchHistory(puuid, 0, 49);

        // 单排/双排队列 ID: 420
        int soloWins = 0, soloLosses = 0;
        // 灵活组排队列 ID: 440
        int flexWins = 0, flexLosses = 0;

        for (MatchHistory match : matches) {
            Integer queueId = match.getQueueId();
            if (queueId == null) continue;

            // 只统计排位赛
            if (queueId != 420 && queueId != 440) continue;

            Integer participantId = findParticipantId(match, puuid);
            if (participantId != null && match.getParticipants() != null) {
                for (MatchHistory.Participant p : match.getParticipants()) {
                    if (participantId.equals(p.getParticipantId()) && p.getStats() != null) {
                        boolean win = Boolean.TRUE.equals(p.getStats().getWin());
                        if (queueId == 420) {
                            if (win) soloWins++; else soloLosses++;
                        } else {
                            if (win) flexWins++; else flexLosses++;
                        }
                        break;
                    }
                }
            }
        }

        Map<String, WinRate> result = new java.util.HashMap<>();
        result.put("RANKED_SOLO_5x5", WinRate.of(soloWins, soloLosses));
        result.put("RANKED_FLEX_SR", WinRate.of(flexWins, flexLosses));
        return result;
    }

    /**
     * 根据 puuid 查找 participantId
     */
    private Integer findParticipantId(MatchHistory match, String puuid) {
        if (match.getParticipantIdentities() != null) {
            for (MatchHistory.ParticipantIdentity identity : match.getParticipantIdentities()) {
                if (identity.getPlayer() != null && puuid.equals(identity.getPlayer().getPuuid())) {
                    return identity.getParticipantId();
                }
            }
        }
        return null;
    }

    // ========== 游戏 API ==========

    /**
     * 获取当前游戏阶段
     */
    public String getGamePhase() {
        if (currentPhase != null) {
            return currentPhase;
        }
        return lcuHttpClient.get("lol-gameflow/v1/gameflow-phase", String.class);
    }

    /**
     * 获取大厅信息
     */
    public Lobby getLobby() {
        return lcuHttpClient.get("lol-lobby/v2/lobby", Lobby.class);
    }

    /**
     * 开始匹配
     */
    public void startMatchmaking() {
        lcuHttpClient.post("lol-lobby/v2/lobby/matchmaking/search", null, Void.class);
        log.info("已开始匹配");
    }

    /**
     * 取消匹配
     */
    public void cancelMatchmaking() {
        lcuHttpClient.delete("lol-lobby/v2/lobby/matchmaking/search", Void.class);
        log.info("已取消匹配");
    }

    /**
     * 接受对局
     */
    public void acceptMatch() {
        lcuHttpClient.post("lol-matchmaking/v1/ready-check/accept", null, Void.class);
        log.info("已接受对局");
    }

    /**
     * 获取选人会话
     */
    public ChampionSelectSession getChampionSelectSession() {
        return lcuHttpClient.get("lol-champ-select/v1/session", ChampionSelectSession.class);
    }

    /**
     * 选择英雄
     */
    public void pickChampion(int actionId, int championId, boolean completed) {
        Map<String, Object> body = Map.of(
                "championId", championId,
                "type", "pick",
                "completed", completed
        );
        String uri = String.format("lol-champ-select/v1/session/actions/%d", actionId);
        lcuHttpClient.patch(uri, body, Void.class);
        log.info("选择英雄: championId={}, completed={}", championId, completed);
    }

    /**
     * 禁用英雄
     */
    public void banChampion(int actionId, int championId, boolean completed) {
        Map<String, Object> body = Map.of(
                "championId", championId,
                "type", "ban",
                "completed", completed
        );
        String uri = String.format("lol-champ-select/v1/session/actions/%d", actionId);
        lcuHttpClient.patch(uri, body, Void.class);
        log.info("禁用英雄: championId={}, completed={}", championId, completed);
    }

    /**
     * 获取单局详情
     */
    public GameDetail getGameDetailById(Long gameId) {
        String uri = String.format("lol-match-history/v1/games/%d", gameId);
        return lcuHttpClient.get(uri, GameDetail.class);
    }

    /**
     * 获取游戏会话数据
     */
    public GameSession getGameSession() {
        return lcuHttpClient.get("lol-gameflow/v1/session", GameSession.class);
    }

    /**
     * 获取完整会话数据（包含所有玩家信息）
     */
    public SessionData getSessionData(Integer mode) {
        Summoner mySummoner = getMySummoner();
        if (mySummoner == null) {
            return SessionData.builder().build();
        }

        String phase = getGamePhase();
        List<String> validPhases = List.of("ChampSelect", "InProgress", "PreEndOfGame", "EndOfGame");
        if (!validPhases.contains(phase)) {
            return SessionData.builder().phase(phase).build();
        }

        GameSession session = getGameSession();
        if (session == null || session.getGameData() == null) {
            return SessionData.builder().phase(phase).build();
        }

        // 选人阶段使用 ChampionSelectSession 的队伍数据
        if ("ChampSelect".equals(phase)) {
            ChampionSelectSession selectSession = getChampionSelectSession();
            if (selectSession != null && selectSession.getMyTeam() != null) {
                session.getGameData().setTeamOne(
                        selectSession.getMyTeam().stream()
                                .map(p -> {
                                    GameSession.OnePlayer player = new GameSession.OnePlayer();
                                    player.setChampionId(p.getChampionId());
                                    player.setPuuid(p.getPuuid());
                                    player.setSelectedPosition("");
                                    return player;
                                })
                                .toList()
                );
                session.getGameData().setTeamTwo(List.of()); // 选人阶段看不到对手
            }
        }

        // 确定我方在 teamOne
        boolean needSwap = session.getGameData().getTeamOne().stream()
                .noneMatch(p -> mySummoner.getPuuid().equals(p.getPuuid()));
        if (needSwap) {
            List<GameSession.OnePlayer> temp = session.getGameData().getTeamOne();
            session.getGameData().setTeamOne(session.getGameData().getTeamTwo());
            session.getGameData().setTeamTwo(temp);
        }

        // 用 playerChampionSelections 补全缺失的玩家
        List<GameSession.PlayerChampionSelection> selections = session.getGameData().getPlayerChampionSelections();
        if (selections != null && selections.size() == 10) {
            List<GameSession.PlayerChampionSelection> firstFive = needSwap ?
                    selections.subList(5, 10) : selections.subList(0, 5);
            List<GameSession.PlayerChampionSelection> secondFive = needSwap ?
                    selections.subList(0, 5) : selections.subList(5, 10);

            if (session.getGameData().getTeamOne().size() < 5) {
                session.getGameData().setTeamOne(firstFive.stream()
                        .map(s -> {
                            GameSession.OnePlayer p = new GameSession.OnePlayer();
                            p.setChampionId(s.getChampionId());
                            p.setPuuid(s.getPuuid());
                            p.setSelectedPosition("");
                            return p;
                        })
                        .toList());
            }
            if (session.getGameData().getTeamTwo().size() < 5) {
                session.getGameData().setTeamTwo(secondFive.stream()
                        .map(s -> {
                            GameSession.OnePlayer p = new GameSession.OnePlayer();
                            p.setChampionId(s.getChampionId());
                            p.setPuuid(s.getPuuid());
                            p.setSelectedPosition("");
                            return p;
                        })
                        .toList());
            }
        }

        // 获取队列信息
        Integer queueId = session.getGameData().getQueue() != null ?
                session.getGameData().getQueue().getId() : 0;
        String queueType = session.getGameData().getQueue() != null ?
                session.getGameData().getQueue().getType() : "";
        String typeCn = QueueType.getQueueNameCn(queueId);

        // 处理双方队伍
        List<SessionSummoner> teamOne = processTeam(session.getGameData().getTeamOne(), queueId);
        List<SessionSummoner> teamTwo = processTeam(session.getGameData().getTeamTwo(), queueId);

        // 标记预组队
        addPreGroupMarkers(teamOne, teamTwo);

        // 添加遇到过的玩家记录
        insertMeetGamersRecord(teamOne, teamTwo, mySummoner.getPuuid());

        return SessionData.builder()
                .phase(phase)
                .queueType(queueType)
                .typeCn(typeCn)
                .queueId(queueId)
                .teamOne(teamOne)
                .teamTwo(teamTwo)
                .build();
    }

    /**
     * 处理队伍数据（并行优化版本）
     */
    private List<SessionSummoner> processTeam(List<GameSession.OnePlayer> team, Integer queueId) {
        if (team == null) return List.of();

        // 并行处理每个玩家
        List<CompletableFuture<SessionSummoner>> futures = team.stream()
                .map(player -> CompletableFuture.supplyAsync(() -> processPlayer(player, queueId)))
                .toList();

        // 等待所有任务完成并收集结果
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 处理单个玩家数据（并行获取多个 API）
     */
    private SessionSummoner processPlayer(GameSession.OnePlayer player, Integer queueId) {
        if (player.getPuuid() == null || player.getPuuid().isEmpty()) {
            return buildEmptySessionSummoner(player.getChampionId());
        }

        try {
            // 并行获取 4 个 API 数据
            CompletableFuture<Summoner> summonerFuture = CompletableFuture.supplyAsync(
                    () -> getSummonerByPuuid(player.getPuuid()));
            CompletableFuture<Rank> rankFuture = CompletableFuture.supplyAsync(
                    () -> getRankByPuuid(player.getPuuid()));
            CompletableFuture<List<MatchHistory>> historyFuture = CompletableFuture.supplyAsync(
                    () -> getMatchHistory(player.getPuuid(), 0, 3));
            CompletableFuture<UserTag> userTagFuture = CompletableFuture.supplyAsync(
                    () -> userTagService.getUserTagByPuuid(player.getPuuid(), queueId));

            // 等待所有请求完成
            CompletableFuture.allOf(summonerFuture, rankFuture, historyFuture, userTagFuture).join();

            Summoner summoner = summonerFuture.join();
            Rank rank = rankFuture.join();
            List<MatchHistory> history = historyFuture.join();
            UserTag userTag = userTagFuture.join();

            return SessionSummoner.builder()
                    .championId(player.getChampionId())
                    .championKey("champion_" + player.getChampionId())
                    .summoner(summoner != null ? summoner : new Summoner())
                    .matchHistory(history != null ? history : List.of())
                    .userTag(userTag != null ? userTag : UserTag.builder().build())
                    .rank(rank != null ? rank : new Rank())
                    .meetGames(List.of())
                    .preGroupMarkers(PreGroupMarker.empty())
                    .isLoading(false)
                    .build();
        } catch (Exception e) {
            log.warn("获取玩家信息失败: {}", player.getPuuid(), e);
            return buildEmptySessionSummoner(player.getChampionId());
        }
    }

    /**
     * 构建空的 SessionSummoner
     */
    private SessionSummoner buildEmptySessionSummoner(Integer championId) {
        return SessionSummoner.builder()
                .championId(championId)
                .championKey("champion_" + championId)
                .summoner(new Summoner())
                .matchHistory(List.of())
                .userTag(UserTag.builder().build())
                .rank(new Rank())
                .meetGames(List.of())
                .preGroupMarkers(PreGroupMarker.empty())
                .isLoading(false)
                .build();
    }

    /**
     * 标记预组队
     */
    private void addPreGroupMarkers(List<SessionSummoner> teamOne, List<SessionSummoner> teamTwo) {
        int friendThreshold = 3;
        int teamMinSum = 2;

        // 收集当前对局所有玩家 PUUID
        java.util.Set<String> currentGamePuuids = new java.util.HashSet<>();
        teamOne.forEach(s -> currentGamePuuids.add(s.getSummoner().getPuuid()));
        teamTwo.forEach(s -> currentGamePuuids.add(s.getSummoner().getPuuid()));

        // 收集可能的预组队
        List<List<String>> allMaybeTeams = new java.util.ArrayList<>();

        for (SessionSummoner summoner : teamOne) {
            List<String> theTeams = findPreGroupMembers(summoner, currentGamePuuids, friendThreshold);
            if (!theTeams.isEmpty()) {
                allMaybeTeams.add(theTeams);
            }
        }

        for (SessionSummoner summoner : teamTwo) {
            List<String> theTeams = findPreGroupMembers(summoner, currentGamePuuids, friendThreshold);
            if (!theTeams.isEmpty()) {
                allMaybeTeams.add(theTeams);
            }
        }

        // 合并队伍，去除子集
        List<List<String>> mergedTeams = removeSubsets(allMaybeTeams);

        // 预组队标记常量
        PreGroupMarker[] markers = {
                PreGroupMarker.builder().name("队伍1").type("success").build(),
                PreGroupMarker.builder().name("队伍2").type("warning").build(),
                PreGroupMarker.builder().name("队伍3").type("error").build(),
                PreGroupMarker.builder().name("队伍4").type("info").build()
        };

        List<String> teamOnePuuids = teamOne.stream()
                .map(s -> s.getSummoner().getPuuid())
                .toList();
        List<String> teamTwoPuuids = teamTwo.stream()
                .map(s -> s.getSummoner().getPuuid())
                .toList();

        int constIndex = 0;
        for (List<String> team : mergedTeams) {
            if (constIndex >= markers.length) break;

            List<String> intersectionTeamOne = intersection(team, teamOnePuuids);
            List<String> intersectionTeamTwo = intersection(team, teamTwoPuuids);

            if (intersectionTeamOne.size() >= teamMinSum) {
                for (SessionSummoner s : teamOne) {
                    if (intersectionTeamOne.contains(s.getSummoner().getPuuid()) &&
                            s.getPreGroupMarkers().getName().isEmpty()) {
                        s.setPreGroupMarkers(markers[constIndex]);
                    }
                }
                constIndex++;
            } else if (intersectionTeamTwo.size() >= teamMinSum) {
                for (SessionSummoner s : teamTwo) {
                    if (intersectionTeamTwo.contains(s.getSummoner().getPuuid()) &&
                            s.getPreGroupMarkers().getName().isEmpty()) {
                        s.setPreGroupMarkers(markers[constIndex]);
                    }
                }
                constIndex++;
            }
        }
    }

    /**
     * 查找预组队成员
     */
    private List<String> findPreGroupMembers(SessionSummoner summoner, java.util.Set<String> currentGamePuuids, int threshold) {
        List<String> theTeams = new java.util.ArrayList<>();

        if (summoner.getUserTag() != null &&
                summoner.getUserTag().getRecentData() != null &&
                summoner.getUserTag().getRecentData().getOneGamePlayersMap() != null) {

            for (Map.Entry<String, List<OneGamePlayer>> entry :
                    summoner.getUserTag().getRecentData().getOneGamePlayersMap().entrySet()) {
                if (!currentGamePuuids.contains(entry.getKey())) continue;

                long teamCount = entry.getValue().stream()
                        .filter(OneGamePlayer::getIsMyTeam)
                        .count();

                if (teamCount >= threshold) {
                    theTeams.add(entry.getKey());
                }
            }
        }

        return theTeams;
    }

    /**
     * 插入遇到过的玩家记录
     */
    private void insertMeetGamersRecord(List<SessionSummoner> teamOne, List<SessionSummoner> teamTwo, String myPuuid) {
        // 找到自己的 oneGamePlayersMap
        Map<String, List<OneGamePlayer>> myMap = teamOne.stream()
                .filter(s -> myPuuid.equals(s.getSummoner().getPuuid()))
                .findFirst()
                .filter(s -> s.getUserTag() != null && s.getUserTag().getRecentData() != null)
                .map(s -> s.getUserTag().getRecentData().getOneGamePlayersMap())
                .orElse(null);

        if (myMap == null) return;

        // 遍历我方
        for (SessionSummoner s : teamOne) {
            if (myPuuid.equals(s.getSummoner().getPuuid())) continue;
            if (myMap.containsKey(s.getSummoner().getPuuid())) {
                s.setMeetGames(myMap.get(s.getSummoner().getPuuid()));
            }
        }

        // 遍历敌方
        for (SessionSummoner s : teamTwo) {
            if (myMap.containsKey(s.getSummoner().getPuuid())) {
                s.setMeetGames(myMap.get(s.getSummoner().getPuuid()));
            }
        }
    }

    /**
     * 去重并保留最大范围的数组
     */
    private List<List<String>> removeSubsets(List<List<String>> arrays) {
        List<List<String>> sortedArrays = new java.util.ArrayList<>(arrays);
        sortedArrays.sort((a, b) -> Integer.compare(b.size(), a.size()));

        List<List<String>> result = new java.util.ArrayList<>();
        for (List<String> arr : sortedArrays) {
            boolean isSubset = result.stream().anyMatch(resArr -> isSubset(arr, resArr));
            if (!isSubset) {
                result.add(arr);
            }
        }
        return result;
    }

    /**
     * 判断 a 是否是 b 的子集
     */
    private boolean isSubset(List<String> a, List<String> b) {
        if (a.size() >= b.size()) return false;
        return new HashSet<>(b).containsAll(a);
    }

    /**
     * 取两个列表的交集
     */
    private List<String> intersection(List<String> list1, List<String> list2) {
        return list1.stream()
                .filter(list2::contains)
                .toList();
    }

    // ========== 状态检查 ==========

    /**
     * 检查 LCU 连接状态
     */
    public boolean checkConnection() {
        return checkAndConnect();
    }

    /**
     * 刷新缓存
     */
    public void refreshCache(String puuid) {
        summonerCache.invalidate(puuid);
        rankCache.invalidate(puuid);
        matchHistoryCache.invalidate(puuid);
    }
}
