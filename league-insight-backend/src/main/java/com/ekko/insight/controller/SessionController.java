package com.ekko.insight.controller;

import com.ekko.insight.model.ApiResponse;
import com.ekko.insight.model.ChampionSelectSession;
import com.ekko.insight.model.GameState;
import com.ekko.insight.model.Lobby;
import com.ekko.insight.model.SessionData;
import com.ekko.insight.service.LcuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 会话控制器
 * 提供游戏会话相关接口
 */
@RestController
@RequestMapping("/api/v1/session")
@RequiredArgsConstructor
public class SessionController {

    private final LcuService lcuService;

    /**
     * 获取游戏状态
     */
    @GetMapping("/game-state")
    public ApiResponse<GameState> getGameState() {
        GameState state = new GameState();

        try {
            boolean connected = lcuService.checkConnection();
            state.setConnected(connected);

            if (connected) {
                String phase = lcuService.getGamePhase();
                state.setPhase(phase);

                state.setSummoner(lcuService.getMySummoner());
            }
        } catch (Exception e) {
            state.setConnected(false);
        }

        return ApiResponse.success(state);
    }

    /**
     * 获取游戏阶段
     */
    @GetMapping("/phase")
    public ApiResponse<String> getGamePhase() {
        return ApiResponse.success(lcuService.getGamePhase());
    }

    /**
     * 获取大厅信息
     */
    @GetMapping("/lobby")
    public ApiResponse<Lobby> getLobby() {
        return ApiResponse.success(lcuService.getLobby());
    }

    /**
     * 获取选人会话
     */
    @GetMapping("/champion-select")
    public ApiResponse<ChampionSelectSession> getChampionSelectSession() {
        return ApiResponse.success(lcuService.getChampionSelectSession());
    }

    /**
     * 开始匹配
     */
    @PostMapping("/matchmaking/start")
    public ApiResponse<Void> startMatchmaking() {
        lcuService.startMatchmaking();
        return ApiResponse.success();
    }

    /**
     * 取消匹配
     */
    @PostMapping("/matchmaking/cancel")
    public ApiResponse<Void> cancelMatchmaking() {
        lcuService.cancelMatchmaking();
        return ApiResponse.success();
    }

    /**
     * 接受对局
     */
    @PostMapping("/accept")
    public ApiResponse<Void> acceptMatch() {
        lcuService.acceptMatch();
        return ApiResponse.success();
    }

    /**
     * 检查连接状态
     */
    @GetMapping("/connected")
    public ApiResponse<Boolean> isConnected() {
        return ApiResponse.success(lcuService.checkConnection());
    }

    /**
     * 获取完整会话数据（包含双方队伍所有玩家信息）
     * @param mode 队列模式（可选，<=0 表示全部）
     * @return 完整会话数据
     */
    @GetMapping("/data")
    public ApiResponse<SessionData> getSessionData(@RequestParam(required = false) Integer mode) {
        return ApiResponse.success(lcuService.getSessionData(mode));
    }
}
