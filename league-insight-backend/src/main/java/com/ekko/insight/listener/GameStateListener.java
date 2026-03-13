package com.ekko.insight.listener;

import com.ekko.insight.event.ChampionSelectUpdatedEvent;
import com.ekko.insight.event.GamePhaseChangedEvent;
import com.ekko.insight.event.LobbyUpdatedEvent;
import com.ekko.insight.model.GameState;
import com.ekko.insight.service.SummonerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 游戏状态监听器
 * 监听 LCU 事件并推送 WebSocket 消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameStateListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final SummonerService summonerService;

    /**
     * 监听游戏阶段变化事件
     */
    @EventListener
    @Async("eventExecutor")
    public void onGamePhaseChanged(GamePhaseChangedEvent event) {
        log.info("游戏阶段变化：{} -> {}", event.getOldPhase(), event.getNewPhase());
        
        try {
            pushGameState(true);
        } catch (Exception e) {
            log.error("推送游戏状态失败：{}", e.getMessage());
        }
    }

    /**
     * 监听选人阶段更新事件
     */
    @EventListener
    @Async("eventExecutor")
    public void onChampionSelectUpdated(ChampionSelectUpdatedEvent event) {
        log.debug("选人阶段更新");
        
        try {
            messagingTemplate.convertAndSend("/topic/champion-select", event.getSessionData());
        } catch (Exception e) {
            log.error("推送选人数据失败：{}", e.getMessage());
        }
    }

    /**
     * 监听大厅更新事件
     */
    @EventListener
    @Async("eventExecutor")
    public void onLobbyUpdated(LobbyUpdatedEvent event) {
        log.debug("大厅数据更新");
        
        try {
            messagingTemplate.convertAndSend("/topic/lobby", event.getLobby());
        } catch (Exception e) {
            log.error("推送大厅数据失败：{}", e.getMessage());
        }
    }

    /**
     * 推送游戏状态
     */
    private void pushGameState(boolean connected) {
        try {
            GameState state = new GameState();
            state.setConnected(connected);

            if (connected) {
                try {
                    var summoner = summonerService.getMySummoner();
                    state.setSummoner(summoner);
                } catch (Exception e) {
                    log.debug("获取召唤师信息失败：{}", e.getMessage());
                }
            }

            messagingTemplate.convertAndSend("/topic/game-state", state);
        } catch (Exception e) {
            log.error("推送游戏状态失败：{}", e.getMessage());
        }
    }
}
