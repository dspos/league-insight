package com.ekko.insight.service;

import com.ekko.insight.config.AppConfig;
import com.ekko.insight.model.ChampionSelectSession;
import com.ekko.insight.model.GamePhase;
import com.ekko.insight.model.Lobby;
import com.ekko.insight.model.Summoner;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 自动化服务
 * 提供自动匹配、自动接受、自动选人/禁人功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationService {

    private final LcuService lcuService;
    private final AppConfig appConfig;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    // 任务名称常量
    public static final String TASK_AUTO_MATCH = "auto_match";
    public static final String TASK_AUTO_ACCEPT = "auto_accept";
    public static final String TASK_AUTO_PICK = "auto_pick";
    public static final String TASK_AUTO_BAN = "auto_ban";

    @PreDestroy
    public void destroy() {
        log.info("停止所有自动化任务...");
        tasks.values().forEach(task -> task.cancel(false));
        tasks.clear();
        scheduler.shutdown();
    }

    // ========== 自动匹配 ==========

    /**
     * 启动自动匹配
     */
    public void startAutoMatch() {
        stopTask(TASK_AUTO_MATCH);

        log.info("启动自动匹配任务");
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                this::autoMatchTask,
                0, 1, TimeUnit.SECONDS
        );

        tasks.put(TASK_AUTO_MATCH, future);
    }

    /**
     * 停止自动匹配
     */
    public void stopAutoMatch() {
        stopTask(TASK_AUTO_MATCH);
        log.info("已停止自动匹配");
    }

    private void autoMatchTask() {
        try {
            String phase = lcuService.getGamePhase();

            if (!GamePhase.LOBBY.getCode().equalsIgnoreCase(phase)) {
                return;
            }

            Lobby lobby = lcuService.getLobby();
            if (lobby == null) {
                return;
            }

            // 检查是否是自定义游戏
            if (lobby.getGameConfig() != null &&
                Boolean.TRUE.equals(lobby.getGameConfig().getIsCustom())) {
                log.debug("自定义游戏，跳过自动匹配");
                return;
            }

            // 获取当前召唤师信息
            Summoner me = lcuService.getMySummoner();
            if (me == null) {
                return;
            }

            // 检查是否是房主
            if (!lobby.isLeader(me.getPuuid())) {
                log.debug("不是房主，跳过自动匹配");
                return;
            }

            // 开始匹配
            log.info("自动开始匹配");
            lcuService.startMatchmaking();

            // 等待 6 秒
            Thread.sleep(6000);

        } catch (Exception e) {
            log.error("自动匹配任务错误: {}", e.getMessage());
        }
    }

    // ========== 自动接受 ==========

    /**
     * 启动自动接受
     */
    public void startAutoAccept() {
        stopTask(TASK_AUTO_ACCEPT);

        log.info("启动自动接受任务");
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                this::autoAcceptTask,
                0, 100, TimeUnit.MILLISECONDS
        );

        tasks.put(TASK_AUTO_ACCEPT, future);
    }

    /**
     * 停止自动接受
     */
    public void stopAutoAccept() {
        stopTask(TASK_AUTO_ACCEPT);
        log.info("已停止自动接受");
    }

    private void autoAcceptTask() {
        try {
            String phase = lcuService.getGamePhase();

            if (GamePhase.READYCHECK.getCode().equalsIgnoreCase(phase)) {
                log.info("检测到确认对局阶段，自动接受");
                lcuService.acceptMatch();
            }
        } catch (Exception e) {
            log.error("自动接受任务错误: {}", e.getMessage());
        }
    }

    // ========== 自动选人 ==========

    /**
     * 启动自动选人
     */
    public void startAutoPick() {
        stopTask(TASK_AUTO_PICK);

        log.info("启动自动选人任务");
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                this::autoPickTask,
                0, 2, TimeUnit.SECONDS
        );

        tasks.put(TASK_AUTO_PICK, future);
    }

    /**
     * 停止自动选人
     */
    public void stopAutoPick() {
        stopTask(TASK_AUTO_PICK);
        log.info("已停止自动选人");
    }

    private void autoPickTask() {
        try {
            String phase = lcuService.getGamePhase();

            if (!GamePhase.CHAMPSELECT.getCode().equalsIgnoreCase(phase)) {
                return;
            }

            ChampionSelectSession session = lcuService.getChampionSelectSession();
            if (session == null) {
                return;
            }

            int myCellId = session.getLocalPlayerCellId();
            log.debug("当前 Cell ID: {}", myCellId);

            // 获取配置的英雄列表
            List<Integer> pickChampions = appConfig.getPickChampions();
            if (pickChampions.isEmpty()) {
                log.warn("未配置选择英雄列表");
                return;
            }

            // 收集不可选英雄
            Set<Integer> unavailableChampions = new HashSet<>();

            // 被禁用的英雄
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "ban".equals(actionGroup.getFirst().getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() != myCellId &&
                            Boolean.TRUE.equals(action.getCompleted())) {
                            unavailableChampions.add(action.getChampionId());
                        }
                    }
                }
            }

            // 队友已选的英雄
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "pick".equals(actionGroup.getFirst().getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() != myCellId &&
                            action.getChampionId() != null &&
                            action.getChampionId() != 0) {
                            unavailableChampions.add(action.getChampionId());
                        }
                    }
                }
            }

            // 选择第一个可用的英雄
            int championToPick = pickChampions.stream()
                    .filter(id -> !unavailableChampions.contains(id))
                    .findFirst()
                    .orElse(1);

            // 查找我的 pick 动作
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "pick".equals(actionGroup.getFirst().getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() == myCellId) {
                            // 检查是否正在进行
                            if (Boolean.TRUE.equals(action.getIsInProgress()) &&
                                !Boolean.TRUE.equals(action.getCompleted())) {
                                log.info("自动选择英雄: {}", championToPick);
                                lcuService.pickChampion(action.getId(), championToPick, true);
                            } else if (action.getChampionId() == null || action.getChampionId() == 0) {
                                // 预选
                                log.info("预选英雄: {}", championToPick);
                                lcuService.pickChampion(action.getId(), championToPick, false);
                            }
                            return;
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("自动选人任务错误: {}", e.getMessage());
        }
    }

    // ========== 自动禁人 ==========

    /**
     * 启动自动禁人
     */
    public void startAutoBan() {
        stopTask(TASK_AUTO_BAN);

        log.info("启动自动禁人任务");
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                this::autoBanTask,
                0, 2, TimeUnit.SECONDS
        );

        tasks.put(TASK_AUTO_BAN, future);
    }

    /**
     * 停止自动禁人
     */
    public void stopAutoBan() {
        stopTask(TASK_AUTO_BAN);
        log.info("已停止自动禁人");
    }

    private void autoBanTask() {
        try {
            String phase = lcuService.getGamePhase();

            if (!GamePhase.CHAMPSELECT.getCode().equalsIgnoreCase(phase)) {
                return;
            }

            ChampionSelectSession session = lcuService.getChampionSelectSession();
            if (session == null) {
                return;
            }

            int myCellId = session.getLocalPlayerCellId();

            // 获取配置的禁用英雄列表
            List<Integer> banChampions = appConfig.getBanChampions();
            if (banChampions.isEmpty()) {
                log.warn("未配置禁用英雄列表");
                return;
            }

            // 收集不可禁英雄
            Set<Integer> unavailableChampions = new HashSet<>();
            boolean alreadyBanned = false;

            // 检查是否已经禁用
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "ban".equals(actionGroup.get(0).getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() == myCellId) {
                            if (Boolean.TRUE.equals(action.getCompleted())) {
                                alreadyBanned = true;
                            }
                        } else if (Boolean.TRUE.equals(action.getCompleted())) {
                            unavailableChampions.add(action.getChampionId());
                        }
                    }
                }
            }

            if (alreadyBanned) {
                return;
            }

            // 收集队友预选的英雄（不 ban 队友预选的）
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "pick".equals(actionGroup.get(0).getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() != myCellId &&
                            action.getChampionId() != null &&
                            action.getChampionId() != 0) {
                            unavailableChampions.add(action.getChampionId());
                        }
                    }
                }
            }

            // 选择第一个可用的英雄禁用
            int championToBan = banChampions.stream()
                    .filter(id -> !unavailableChampions.contains(id))
                    .findFirst()
                    .orElse(1);

            // 查找我的 ban 动作
            for (List<ChampionSelectSession.Action> actionGroup : session.getActions()) {
                if (!actionGroup.isEmpty() && "ban".equals(actionGroup.get(0).getActionType())) {
                    for (ChampionSelectSession.Action action : actionGroup) {
                        if (action.getActorCellId() == myCellId &&
                            Boolean.TRUE.equals(action.getIsInProgress())) {
                            log.info("自动禁用英雄: {}", championToBan);
                            lcuService.banChampion(action.getId(), championToBan, true);
                            return;
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("自动禁人任务错误: {}", e.getMessage());
        }
    }

    // ========== 任务管理 ==========

    /**
     * 停止指定任务
     */
    private void stopTask(String taskName) {
        ScheduledFuture<?> task = tasks.remove(taskName);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 获取任务状态
     */
    public Map<String, Boolean> getTaskStatus() {
        Map<String, Boolean> status = new HashMap<>();
        status.put(TASK_AUTO_MATCH, tasks.containsKey(TASK_AUTO_MATCH));
        status.put(TASK_AUTO_ACCEPT, tasks.containsKey(TASK_AUTO_ACCEPT));
        status.put(TASK_AUTO_PICK, tasks.containsKey(TASK_AUTO_PICK));
        status.put(TASK_AUTO_BAN, tasks.containsKey(TASK_AUTO_BAN));
        return status;
    }

    /**
     * 启用/禁用任务
     */
    public void setTaskEnabled(String taskName, boolean enabled) {
        switch (taskName) {
            case TASK_AUTO_MATCH -> {
                if (enabled) startAutoMatch();
                else stopAutoMatch();
            }
            case TASK_AUTO_ACCEPT -> {
                if (enabled) startAutoAccept();
                else stopAutoAccept();
            }
            case TASK_AUTO_PICK -> {
                if (enabled) startAutoPick();
                else stopAutoPick();
            }
            case TASK_AUTO_BAN -> {
                if (enabled) startAutoBan();
                else stopAutoBan();
            }
        }
    }
}
