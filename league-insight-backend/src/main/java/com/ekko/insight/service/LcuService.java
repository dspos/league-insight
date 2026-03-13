package com.ekko.insight.service;

import com.ekko.insight.constant.GameConstants;
import com.ekko.insight.event.ChampionSelectUpdatedEvent;
import com.ekko.insight.event.GamePhaseChangedEvent;
import com.ekko.insight.event.LobbyUpdatedEvent;
import com.ekko.insight.model.*;
import com.ekko.insight.websocket.LcuWebSocketClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LCU 核心服务（协调层）
 * 协调各个子服务，提供统一接口
 * 
 * @deprecated 新功能请直接使用各子服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Deprecated
public class LcuService {

    private final LcuHttpClient lcuHttpClient;
    private final LcuWebSocketClient webSocketClient;
    private final ApplicationEventPublisher eventPublisher;
    private final SummonerService summonerService;
    private final RankService rankService;
    private final MatchHistoryService matchHistoryService;
    private final GameFlowService gameFlowService;
    private final ChampionSelectService championSelectService;
    private final SessionAnalysisService sessionAnalysisService;

    private volatile String currentPhase;
    private ScheduledExecutorService scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("初始化 LCU 服务（协调层）...");

        webSocketClient.addListener(this::handleLcuEvent);
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

    private void startConnectionMonitor() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean wasConnected = webSocketClient.isConnected();
                boolean nowConnected = checkAndConnect();

                // 连接状态变化时，通过事件发布来推送状态
                if (nowConnected != wasConnected) {
                    eventPublisher.publishEvent(new GamePhaseChangedEvent(this, null, currentPhase));
                }
            } catch (Exception e) {
                log.error("连接监控错误：{}", e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

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
            log.debug("LCU 未连接：{}", e.getMessage());
        }
        return false;
    }

    private void handleLcuEvent(LcuWebSocketClient.LcuEvent event) {
        String uri = event.uri();

        if ("/lol-gameflow/v1/gameflow-phase".equals(uri)) {
            if (event.data() != null && event.data().isTextual()) {
                String newPhase = event.data().asText();
                String oldPhase = currentPhase;
                currentPhase = newPhase;
                
                log.info("游戏阶段变化：{} -> {}", oldPhase, newPhase);
                
                // 发布游戏阶段变化事件
                eventPublisher.publishEvent(new GamePhaseChangedEvent(this, oldPhase, newPhase));
            }
        }

        if ("/lol-champ-select/v1/session".equals(uri)) {
            // 发布选人阶段更新事件
            eventPublisher.publishEvent(new ChampionSelectUpdatedEvent(this, event.data()));
        }

        if ("/lol-lobby/v2/lobby".equals(uri)) {
            try {
                Lobby lobby = lcuHttpClient.get("lol-lobby/v2/lobby", Lobby.class);
                // 发布大厅更新事件
                eventPublisher.publishEvent(new LobbyUpdatedEvent(this, lobby));
            } catch (Exception e) {
                log.debug("获取大厅数据失败：{}", e.getMessage());
            }
        }
    }

    // ========== 委托给 SummonerService ==========

    public Summoner getMySummoner() {
        return summonerService.getMySummoner();
    }

    public Summoner getSummonerByPuuid(String puuid) {
        return summonerService.getSummonerByPuuid(puuid);
    }

    public Summoner getSummonerByName(String name) {
        return summonerService.getSummonerByName(name);
    }

    // ========== 委托给 RankService ==========

    public Rank getRankByPuuid(String puuid) {
        return rankService.getRankByPuuid(puuid);
    }

    // ========== 委托给 MatchHistoryService ==========

    public List<MatchHistory> getMatchHistory(String puuid, int begIndex, int endIndex) {
        return matchHistoryService.getMatchHistory(puuid, begIndex, endIndex);
    }

    public List<MatchHistory> getFilteredMatchHistory(String puuid, int begIndex, int endIndex,
                                                       Integer queueId, Integer championId, int maxResults) {
        return matchHistoryService.getFilteredMatchHistory(puuid, begIndex, endIndex, queueId, championId, maxResults);
    }

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

    public WinRate getWinRate(String puuid, Integer mode) {
        return matchHistoryService.getWinRate(puuid, mode);
    }

    public Map<String, WinRate> getRankedWinRates(String puuid) {
        return matchHistoryService.getRankedWinRates(puuid);
    }

    // ========== 委托给 GameFlowService ==========

    public String getGamePhase() {
        if (currentPhase != null) {
            return currentPhase;
        }
        return gameFlowService.getGamePhase();
    }

    public Lobby getLobby() {
        return gameFlowService.getLobby();
    }

    public void startMatchmaking() {
        gameFlowService.startMatchmaking();
    }

    public void cancelMatchmaking() {
        gameFlowService.cancelMatchmaking();
    }

    public void acceptMatch() {
        gameFlowService.acceptMatch();
    }

    public boolean checkConnection() {
        return checkAndConnect();
    }

    // ========== 委托给 ChampionSelectService ==========

    public ChampionSelectSession getChampionSelectSession() {
        return championSelectService.getChampionSelectSession();
    }

    public void pickChampion(int actionId, int championId, boolean completed) {
        championSelectService.pickChampion(actionId, championId, completed);
    }

    public void banChampion(int actionId, int championId, boolean completed) {
        championSelectService.banChampion(actionId, championId, completed);
    }

    // ========== 委托给 SessionAnalysisService ==========

    public SessionData getSessionData(Integer mode) {
        return sessionAnalysisService.getSessionData(mode);
    }

    // ========== 其他方法 ==========

    public GameDetail getGameDetailById(Long gameId) {
        String uri = String.format("lol-match-history/v1/games/%d", gameId);
        return lcuHttpClient.get(uri, GameDetail.class);
    }

    public GameSession getGameSession() {
        return lcuHttpClient.get("lol-gameflow/v1/session", GameSession.class);
    }
}
