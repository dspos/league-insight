package com.ekko.insight.service;

import com.ekko.insight.model.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ekko.insight.constant.QueueType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 用户标签服务
 * 根据近期战绩分析玩家特征并生成标签
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTagService {

    private final LcuHttpClient lcuHttpClient;
    private final LcuService lcuService;
    private final TagConfigService tagConfigService;

    // 对局详情缓存
    private final Cache<Long, GameDetail> gameDetailCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    /**
     * 根据名称获取用户标签
     */
    public UserTag getUserTagByName(String name, Integer mode) {
        try {
            Summoner summoner = lcuService.getSummonerByName(name);
            if (summoner == null) {
                throw new RuntimeException("未找到召唤师: %s".formatted(name));
            }
            return getUserTagByPuuid(summoner.getPuuid(), mode);
        } catch (Exception e) {
            log.error("获取用户标签失败: {}", e.getMessage());
            return createEmptyTag();
        }
    }

    /**
     * 根据 PUUID 获取用户标签
     */
    public UserTag getUserTagByPuuid(String puuid, Integer mode) {
        log.debug("计算用户标签: puuid={}, mode={}", puuid, mode);

        try {
            // 获取战绩
            List<MatchHistory> matchHistory = lcuService.getMatchHistory(puuid, 0, 20);

            if (matchHistory.isEmpty()) {
                return createEmptyTag();
            }

            // 获取对局详情
            List<MatchHistory> enrichedHistory = enrichMatchHistory(matchHistory);
            log.debug("enrichMatchHistory 完成, 有效对局数: {}", enrichedHistory.stream()
                    .filter(g -> g.getParticipants() != null && !g.getParticipants().isEmpty())
                    .count());

            // 计算标签
            List<RankTag> tags = evaluateTags(enrichedHistory, puuid, mode);
            log.debug("evaluateTags 结果: {} 个标签", tags.size());

            // 计算近期数据
            RecentData recentData = calculateRecentData(enrichedHistory, puuid, mode);

            // 分析遇到过的人
            Map<String, List<OneGamePlayer>> oneGamePlayersMap = analyzeOneGamePlayers(enrichedHistory, puuid);
            recentData.setOneGamePlayersMap(oneGamePlayersMap);

            // 计算好友/冤家
            calculateFriendAndDispute(oneGamePlayersMap, recentData, puuid);

            return UserTag.builder()
                    .recentData(recentData)
                    .tag(tags)
                    .build();

        } catch (Exception e) {
            log.error("计算用户标签失败: {}", e.getMessage(), e);
            return createEmptyTag();
        }
    }

    /**
     * 获取对局详情
     */
    private List<MatchHistory> enrichMatchHistory(List<MatchHistory> matchHistory) {
        // 为每场对局获取详情，以便获取所有 10 个玩家的信息
        for (MatchHistory game : matchHistory) {
            try {
                // 使用缓存获取对局详情
                GameDetail detail = gameDetailCache.get(game.getGameId(),
                        lcuService::getGameDetailById);

                if (detail != null && detail.getParticipants() != null) {
                    // 将 GameDetail 中的参与者信息转换到 MatchHistory
                    List<MatchHistory.Participant> allParticipants = new ArrayList<>();
                    List<MatchHistory.ParticipantIdentity> allIdentities = new ArrayList<>();

                    for (GameDetail.GameParticipant gp : detail.getParticipants()) {
                        MatchHistory.Participant p = new MatchHistory.Participant();
                        p.setParticipantId(gp.getParticipantId());
                        p.setTeamId(gp.getTeamId());
                        p.setChampionId(gp.getChampionId());

                        MatchHistory.Stats stats = new MatchHistory.Stats();
                        if (gp.getStats() != null) {
                            stats.setWin(gp.getStats().getWin());
                            stats.setKills(gp.getStats().getKills());
                            stats.setDeaths(gp.getStats().getDeaths());
                            stats.setAssists(gp.getStats().getAssists());
                            stats.setGoldEarned(gp.getStats().getGoldEarned() != null ? gp.getStats().getGoldEarned().intValue() : null);
                            stats.setTotalDamageDealtToChampions(gp.getStats().getTotalDamageDealtToChampions() != null ? gp.getStats().getTotalDamageDealtToChampions().intValue() : null);
                            stats.setTotalDamageTaken(gp.getStats().getTotalDamageTaken() != null ? gp.getStats().getTotalDamageTaken().intValue() : null);
                            stats.setTotalHeal(gp.getStats().getTotalHeal() != null ? gp.getStats().getTotalHeal().intValue() : null);
                        }
                        p.setStats(stats);
                        allParticipants.add(p);
                    }

                    for (GameDetail.ParticipantIdentity identity : detail.getParticipantIdentities()) {
                        MatchHistory.ParticipantIdentity pid = new MatchHistory.ParticipantIdentity();
                        pid.setParticipantId(identity.getParticipantId());

                        MatchHistory.Player player = new MatchHistory.Player();
                        if (identity.getPlayer() != null) {
                            player.setPuuid(identity.getPlayer().getPuuid());
                            player.setGameName(identity.getPlayer().getGameName());
                            player.setTagLine(identity.getPlayer().getTagLine());
                            player.setSummonerName(identity.getPlayer().getSummonerName());
                            player.setAccountId(identity.getPlayer().getAccountId());
                            player.setSummonerId(identity.getPlayer().getSummonerId());
                            player.setPlatformId(identity.getPlayer().getPlatformId());
                        }
                        pid.setPlayer(player);
                        allIdentities.add(pid);
                    }

                    game.setParticipants(allParticipants);
                    game.setParticipantIdentities(allIdentities);
                }
            } catch (Exception e) {
                log.debug("获取对局详情失败 gameId={}: {}", game.getGameId(), e.getMessage());
            }
        }
        return matchHistory;
    }

    /**
     * 评估标签
     */
    private List<RankTag> evaluateTags(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        return tagConfigService.evaluateTags(matchHistory, puuid, mode);
    }

    /**
     * 获取玩家的 participant（根据 puuid）
     */
    private MatchHistory.Participant getParticipantByPuuid(MatchHistory game, String puuid) {
        // 先从 participantIdentities 找到 participantId
        Integer participantId = null;
        if (game.getParticipantIdentities() != null) {
            for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
                if (identity.getPlayer() != null && puuid.equals(identity.getPlayer().getPuuid())) {
                    participantId = identity.getParticipantId();
                    break;
                }
            }
        }

        // 根据 participantId 找到 participant
        if (participantId != null && game.getParticipants() != null) {
            for (MatchHistory.Participant p : game.getParticipants()) {
                if (participantId.equals(p.getParticipantId())) {
                    return p;
                }
            }
        }

        return null;
    }

    /**
     * 检测连胜
     */
    private RankTag checkStreak(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        int streak = 0;
        for (MatchHistory game : matchHistory) {
            if (mode != 0 && !mode.equals(game.getQueueId())) continue;

            MatchHistory.Participant p = getParticipantByPuuid(game, puuid);
            if (p != null && p.getStats() != null && Boolean.TRUE.equals(p.getStats().getWin())) {
                streak++;
            } else {
                break;
            }
        }

        if (streak >= 5) {
            return RankTag.builder()
                    .good(true)
                    .tagName("连胜王")
                    .tagDesc("近期连胜 " + streak + " 场")
                    .build();
        } else if (streak >= 3) {
            return RankTag.builder()
                    .good(true)
                    .tagName("连胜中")
                    .tagDesc("近期连胜 " + streak + " 场")
                    .build();
        }
        return null;
    }

    /**
     * 检测连败
     */
    private RankTag checkLosing(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        int losing = 0;
        for (MatchHistory game : matchHistory) {
            if (mode != 0 && !mode.equals(game.getQueueId())) continue;

            MatchHistory.Participant p = getParticipantByPuuid(game, puuid);
            if (p != null && p.getStats() != null && Boolean.FALSE.equals(p.getStats().getWin())) {
                losing++;
            } else {
                break;
            }
        }

        if (losing >= 5) {
            return RankTag.builder()
                    .good(false)
                    .tagName("连败中")
                    .tagDesc("近期连败 " + losing + " 场")
                    .build();
        } else if (losing >= 3) {
            return RankTag.builder()
                    .good(false)
                    .tagName("运势不佳")
                    .tagDesc("近期连败 " + losing + " 场")
                    .build();
        }
        return null;
    }

    /**
     * 检测娱乐玩家
     */
    private RankTag checkCasual(List<MatchHistory> matchHistory, Integer mode) {
        long aramCount = matchHistory.stream()
                .filter(g -> mode == 0 || mode.equals(g.getQueueId()))
                .filter(g -> QueueType.isAram(g.getQueueId()))
                .count();

        long total = matchHistory.stream()
                .filter(g -> mode == 0 || mode.equals(g.getQueueId()))
                .count();

        if (total > 0 && aramCount * 100 / total >= 70) {
            return RankTag.builder()
                    .good(null)
                    .tagName("大乱斗玩家")
                    .tagDesc("近期 " + (aramCount * 100 / total) + "% 的对局是大乱斗")
                    .build();
        }
        return null;
    }

    /**
     * 检测高手玩家
     */
    private RankTag checkPro(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        int wins = 0;
        int total = 0;
        double totalKda = 0;

        for (MatchHistory game : matchHistory) {
            if (mode != 0 && !mode.equals(game.getQueueId())) continue;

            MatchHistory.Participant p = getParticipantByPuuid(game, puuid);
            if (p != null && p.getStats() != null) {
                total++;
                MatchHistory.Stats stats = p.getStats();
                if (Boolean.TRUE.equals(stats.getWin())) wins++;
                int deaths = stats.getDeaths() != null && stats.getDeaths() > 0 ? stats.getDeaths() : 1;
                totalKda += (stats.getKills() + stats.getAssists()) * 1.0 / deaths;
            }
        }

        if (total > 0) {
            double winRate = wins * 100.0 / total;
            double avgKda = totalKda / total;

            if (winRate >= 60 && avgKda >= 3.0) {
                return RankTag.builder()
                        .good(true)
                        .tagName("高手玩家")
                        .tagDesc(String.format("胜率 %.0f%%, KDA %.1f", winRate, avgKda))
                        .build();
            }
        }
        return null;
    }

    /**
     * 计算近期数据
     */
    private RecentData calculateRecentData(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        int count = 0;
        double kills = 0, deaths = 0, assists = 0;
        int selectWins = 0, selectLosses = 0;
        long totalGold = 0, totalDamage = 0;

        for (MatchHistory game : matchHistory) {
            if (mode != 0 && !mode.equals(game.getQueueId())) continue;

            MatchHistory.Participant p = getParticipantByPuuid(game, puuid);
            if (p != null && p.getStats() != null) {
                count++;
                MatchHistory.Stats stats = p.getStats();
                kills += stats.getKills() != null ? stats.getKills() : 0;
                deaths += stats.getDeaths() != null ? stats.getDeaths() : 0;
                assists += stats.getAssists() != null ? stats.getAssists() : 0;

                if (Boolean.TRUE.equals(stats.getWin())) {
                    selectWins++;
                } else {
                    selectLosses++;
                }

                totalGold += stats.getGoldEarned() != null ? stats.getGoldEarned() : 0;
                totalDamage += stats.getTotalDamageDealtToChampions() != null ? stats.getTotalDamageDealtToChampions() : 0;
            }
        }

        if (count == 0) count = 1;

        double kda = deaths > 0 ? (kills + assists) / deaths : kills + assists;

        return RecentData.builder()
                .kda(Math.round(kda * 10.0) / 10.0)
                .kills(Math.round(kills / count * 10.0) / 10.0)
                .deaths(Math.round(deaths / count * 10.0) / 10.0)
                .assists(Math.round(assists / count * 10.0) / 10.0)
                .selectMode(mode)
                .selectModeCn(mode == 0 ? "全部模式" : QueueType.getQueueNameCn(mode))
                .selectWins(selectWins)
                .selectLosses(selectLosses)
                .averageGold((int) (totalGold / count))
                .averageDamageDealtToChampions((int) (totalDamage / count))
                .build();
    }

    /**
     * 获取玩家名称
     */
    private String getPlayerName(MatchHistory game, Integer participantId) {
        if (game.getParticipantIdentities() != null) {
            for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
                if (participantId.equals(identity.getParticipantId()) && identity.getPlayer() != null) {
                    MatchHistory.Player player = identity.getPlayer();
                    if (player.getGameName() != null && !player.getGameName().isEmpty()) {
                        return player.getTagLine() != null ?
                            player.getGameName() + "#" + player.getTagLine() :
                            player.getGameName();
                    }
                    return player.getSummonerName();
                }
            }
        }
        return "未知";
    }

    /**
     * 获取玩家 puuid
     */
    private String getPlayerPuuid(MatchHistory game, Integer participantId) {
        if (game.getParticipantIdentities() != null) {
            for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
                if (participantId.equals(identity.getParticipantId()) && identity.getPlayer() != null) {
                    return identity.getPlayer().getPuuid();
                }
            }
        }
        return null;
    }

    /**
     * 分析遇到过的人
     */
    private Map<String, List<OneGamePlayer>> analyzeOneGamePlayers(List<MatchHistory> matchHistory, String myPuuid) {
        Map<String, List<OneGamePlayer>> result = new HashMap<>();

        for (int index = 0; index < matchHistory.size(); index++) {
            MatchHistory game = matchHistory.get(index);
            if (game.getParticipants() == null) continue;

            // 找到自己的 teamId
            Integer myTeamId = null;
            MatchHistory.Participant myParticipant = getParticipantByPuuid(game, myPuuid);
            if (myParticipant != null) {
                myTeamId = myParticipant.getTeamId();
            }

            for (MatchHistory.Participant p : game.getParticipants()) {
                String playerPuuid = getPlayerPuuid(game, p.getParticipantId());

                // 跳过自己和机器人
                if (playerPuuid == null || playerPuuid.isEmpty() || playerPuuid.equals(myPuuid)) {
                    continue;
                }

                MatchHistory.Stats stats = p.getStats();
                if (stats == null) continue;

                // 获取玩家名称和 tagLine
                String gameName = "";
                String tagLine = "";
                if (game.getParticipantIdentities() != null) {
                    for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
                        if (p.getParticipantId().equals(identity.getParticipantId()) && identity.getPlayer() != null) {
                            gameName = identity.getPlayer().getGameName();
                            tagLine = identity.getPlayer().getTagLine();
                            break;
                        }
                    }
                }

                OneGamePlayer player = OneGamePlayer.builder()
                        .index(index)
                        .gameId(game.getGameId())
                        .puuid(playerPuuid)
                        .gameCreatedAt(new java.util.Date(game.getGameCreation()).toString())
                        .isMyTeam(myTeamId != null && myTeamId.equals(p.getTeamId()))
                        .gameName(gameName)
                        .tagLine(tagLine)
                        .championId(p.getChampionId())
                        .kills(stats.getKills())
                        .deaths(stats.getDeaths())
                        .assists(stats.getAssists())
                        .win(stats.getWin())
                        .queueIdCn(QueueType.getQueueNameCn(game.getQueueId()))
                        .build();

                result.computeIfAbsent(playerPuuid, k -> new ArrayList<>()).add(player);
            }
        }

        return result;
    }

    /**
     * 计算好友/冤家
     */
    private void calculateFriendAndDispute(
            Map<String, List<OneGamePlayer>> oneGamePlayersMap,
            RecentData recentData,
            String myPuuid) {

        List<List<OneGamePlayer>> friendsList = new ArrayList<>();
        List<List<OneGamePlayer>> disputeList = new ArrayList<>();

        for (Map.Entry<String, List<OneGamePlayer>> entry : oneGamePlayersMap.entrySet()) {
            List<OneGamePlayer> games = entry.getValue();

            // 至少 3 次相遇才判断
            if (games.size() < 3) continue;

            boolean allSameTeam = games.stream().allMatch(OneGamePlayer::getIsMyTeam);

            if (allSameTeam) {
                friendsList.add(games);
            } else {
                disputeList.add(games);
            }
        }

        // 计算好友组队数据
        List<OneGamePlayerSummoner> friendsSummoner = new ArrayList<>();
        int friendsWins = 0, friendsLosses = 0;

        for (List<OneGamePlayer> games : friendsList.stream().limit(5).toList()) {
            int wins = (int) games.stream().filter(OneGamePlayer::getWin).count();
            int losses = games.size() - wins;
            friendsWins += wins;
            friendsLosses += losses;

            try {
                Summoner summoner = lcuService.getSummonerByPuuid(games.getFirst().getPuuid());
                friendsSummoner.add(OneGamePlayerSummoner.builder()
                        .winRate(wins * 100 / games.size())
                        .wins(wins)
                        .losses(losses)
                        .summoner(summoner)
                        .oneGamePlayer(games)
                        .build());
            } catch (Exception e) {
                log.debug("获取召唤师信息失败: {}", e.getMessage());
            }
        }

        // 计算冤家组队数据
        List<OneGamePlayerSummoner> disputeSummoner = new ArrayList<>();
        int disputeWins = 0, disputeLosses = 0;

        for (List<OneGamePlayer> games : disputeList.stream().limit(5).toList()) {
            List<OneGamePlayer> enemyGames = games.stream()
                    .filter(g -> !g.getIsMyTeam())
                    .toList();

            if (enemyGames.isEmpty()) continue;

            int wins = (int) enemyGames.stream().filter(OneGamePlayer::getWin).count();
            int losses = enemyGames.size() - wins;
            disputeWins += wins;
            disputeLosses += losses;

            try {
                Summoner summoner = lcuService.getSummonerByPuuid(games.get(0).getPuuid());
                disputeSummoner.add(OneGamePlayerSummoner.builder()
                        .winRate(wins * 100 / enemyGames.size())
                        .wins(wins)
                        .losses(losses)
                        .summoner(summoner)
                        .oneGamePlayer(new ArrayList<>(enemyGames))
                        .build());
            } catch (Exception e) {
                log.debug("获取召唤师信息失败: {}", e.getMessage());
            }
        }

        int totalFriends = friendsWins + friendsLosses;
        int totalDispute = disputeWins + disputeLosses;

        recentData.getFriendAndDispute().setFriendsRate(totalFriends > 0 ? friendsWins * 100 / totalFriends : 0);
        recentData.getFriendAndDispute().setDisputeRate(totalDispute > 0 ? disputeWins * 100 / totalDispute : 0);
        recentData.getFriendAndDispute().setFriendsSummoner(friendsSummoner);
        recentData.getFriendAndDispute().setDisputeSummoner(disputeSummoner);
    }

    /**
     * 创建空标签
     */
    private UserTag createEmptyTag() {
        return UserTag.builder()
                .recentData(RecentData.builder()
                        .selectModeCn("全部模式")
                        .build())
                .tag(new ArrayList<>())
                .build();
    }
}
