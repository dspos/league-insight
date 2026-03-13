package com.ekko.insight.service;

import com.ekko.insight.constant.QueueType;
import com.ekko.insight.model.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 会话数据分析服务
 * 提供游戏会话数据处理、队伍分析、玩家信息聚合功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionAnalysisService {

    private final SummonerService summonerService;
    private final RankService rankService;
    private final MatchHistoryService matchHistoryService;
    private final GameFlowService gameFlowService;
    private final ChampionSelectService championSelectService;
    private final UserTagService userTagService;

    @PostConstruct
    public void init() {
        log.info("会话数据分析服务初始化完成");
    }

    /**
     * 获取完整会话数据（包含所有玩家信息）
     */
    public SessionData getSessionData(Integer mode) {
        Summoner mySummoner = summonerService.getMySummoner();
        if (mySummoner == null) {
            return SessionData.builder().build();
        }

        String phase = gameFlowService.getGamePhase();
        List<String> validPhases = List.of("ChampSelect", "InProgress", "PreEndOfGame", "EndOfGame");
        if (!validPhases.contains(phase)) {
            return SessionData.builder().phase(phase).build();
        }

        GameSession session = gameFlowService.getGameSession();
        if (session == null || session.getGameData() == null) {
            return SessionData.builder().phase(phase).build();
        }

        processChampionSelectPhase(phase, session);
        ensureMyTeamIsTeamOne(session, mySummoner);
        fillMissingPlayers(session);

        Integer queueId = session.getGameData().getQueue() != null ?
                session.getGameData().getQueue().getId() : 0;
        String queueType = session.getGameData().getQueue() != null ?
                session.getGameData().getQueue().getType() : "";
        String typeCn = QueueType.getQueueNameCn(queueId);

        List<SessionSummoner> teamOne = processTeam(session.getGameData().getTeamOne(), queueId);
        List<SessionSummoner> teamTwo = processTeam(session.getGameData().getTeamTwo(), queueId);

        addPreGroupMarkers(teamOne, teamTwo);
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
     * 处理选人阶段数据
     */
    private void processChampionSelectPhase(String phase, GameSession session) {
        if ("ChampSelect".equals(phase)) {
            ChampionSelectSession selectSession = championSelectService.getChampionSelectSession();
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
                session.getGameData().setTeamTwo(List.of());
            }
        }
    }

    /**
     * 确保我方在 teamOne
     */
    private void ensureMyTeamIsTeamOne(GameSession session, Summoner mySummoner) {
        boolean needSwap = session.getGameData().getTeamOne().stream()
                .noneMatch(p -> mySummoner.getPuuid().equals(p.getPuuid()));
        if (needSwap) {
            List<GameSession.OnePlayer> temp = session.getGameData().getTeamOne();
            session.getGameData().setTeamOne(session.getGameData().getTeamTwo());
            session.getGameData().setTeamTwo(temp);
        }
    }

    /**
     * 补全缺失的玩家信息
     */
    private void fillMissingPlayers(GameSession session) {
        List<GameSession.PlayerChampionSelection> selections = session.getGameData().getPlayerChampionSelections();
        if (selections == null || selections.size() != 10) {
            return;
        }

        boolean needSwap = session.getGameData().getTeamOne().stream()
                .noneMatch(p -> session.getGameData().getTeamTwo().stream()
                        .anyMatch(p2 -> p2.getPuuid() != null && p2.getPuuid().equals(p.getPuuid())));

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

    /**
     * 处理队伍数据（并行处理）
     */
    private List<SessionSummoner> processTeam(List<GameSession.OnePlayer> team, Integer queueId) {
        if (team == null || team.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<SessionSummoner>> futures = team.stream()
                .map(player -> CompletableFuture.supplyAsync(() -> processPlayer(player, queueId)))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 处理单个玩家数据
     */
    private SessionSummoner processPlayer(GameSession.OnePlayer player, Integer queueId) {
        if (player.getPuuid() == null || player.getPuuid().isEmpty()) {
            return buildEmptySessionSummoner(player.getChampionId());
        }

        try {
            CompletableFuture<Summoner> summonerFuture = CompletableFuture.supplyAsync(
                    () -> summonerService.getSummonerByPuuid(player.getPuuid()));
            CompletableFuture<Rank> rankFuture = CompletableFuture.supplyAsync(
                    () -> rankService.getRankByPuuid(player.getPuuid()));
            CompletableFuture<List<MatchHistory>> historyFuture = CompletableFuture.supplyAsync(
                    () -> matchHistoryService.getMatchHistory(player.getPuuid(), 0, 3));
            CompletableFuture<UserTag> userTagFuture = CompletableFuture.supplyAsync(
                    () -> userTagService.getUserTagByPuuid(player.getPuuid(), queueId));

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
            log.warn("获取玩家信息失败：{}", player.getPuuid(), e);
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

        Set<String> currentGamePuuids = new HashSet<>();
        teamOne.forEach(s -> currentGamePuuids.add(s.getSummoner().getPuuid()));
        teamTwo.forEach(s -> currentGamePuuids.add(s.getSummoner().getPuuid()));

        List<List<String>> allMaybeTeams = new ArrayList<>();

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

        List<List<String>> mergedTeams = removeSubsets(allMaybeTeams);

        PreGroupMarker[] markers = {
                PreGroupMarker.builder().name("队伍 1").type("success").build(),
                PreGroupMarker.builder().name("队伍 2").type("warning").build(),
                PreGroupMarker.builder().name("队伍 3").type("error").build(),
                PreGroupMarker.builder().name("队伍 4").type("info").build()
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
    private List<String> findPreGroupMembers(SessionSummoner summoner, Set<String> currentGamePuuids, int threshold) {
        List<String> theTeams = new ArrayList<>();

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
        Map<String, List<OneGamePlayer>> myMap = teamOne.stream()
                .filter(s -> myPuuid.equals(s.getSummoner().getPuuid()))
                .findFirst()
                .filter(s -> s.getUserTag() != null && s.getUserTag().getRecentData() != null)
                .map(s -> s.getUserTag().getRecentData().getOneGamePlayersMap())
                .orElse(null);

        if (myMap == null) return;

        for (SessionSummoner s : teamOne) {
            if (myPuuid.equals(s.getSummoner().getPuuid())) continue;
            if (myMap.containsKey(s.getSummoner().getPuuid())) {
                s.setMeetGames(myMap.get(s.getSummoner().getPuuid()));
            }
        }

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
        List<List<String>> sortedArrays = new ArrayList<>(arrays);
        sortedArrays.sort((a, b) -> Integer.compare(b.size(), a.size()));

        List<List<String>> result = new ArrayList<>();
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
}
