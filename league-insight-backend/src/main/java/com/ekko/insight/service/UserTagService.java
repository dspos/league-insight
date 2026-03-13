package com.ekko.insight.service;

import com.ekko.insight.constant.QueueType;
import com.ekko.insight.exception.LcuException;
import com.ekko.insight.exception.ResourceNotFoundException;
import com.ekko.insight.model.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final SummonerService summonerService;
    private final MatchHistoryService matchHistoryService;
    private final TagConfigService tagConfigService;

    private final Cache<Long, GameDetail> gameDetailCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    public UserTag getUserTagByName(String name, Integer mode) {
        try {
            Summoner summoner = summonerService.getSummonerByName(name);
            if (summoner == null) {
                throw new ResourceNotFoundException("召唤师", name);
            }
            return getUserTagByPuuid(summoner.getPuuid(), mode);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new LcuException("获取用户标签失败：" + name, e);
        }
    }

    public UserTag getUserTagByPuuid(String puuid, Integer mode) {
        log.debug("计算用户标签：puuid={}, mode={}", puuid, mode);

        try {
            List<MatchHistory> matchHistory = matchHistoryService.getMatchHistory(puuid, 0, 20);

            if (matchHistory.isEmpty()) {
                return createEmptyTag();
            }

            List<MatchHistory> enrichedHistory = enrichMatchHistory(matchHistory);
            log.debug("enrichMatchHistory 完成，有效对局数：{}", enrichedHistory.stream()
                    .filter(g -> g.getParticipants() != null && !g.getParticipants().isEmpty())
                    .count());

            List<RankTag> tags = evaluateTags(enrichedHistory, puuid, mode);
            log.debug("evaluateTags 结果：{} 个标签", tags.size());

            RecentData recentData = calculateRecentData(enrichedHistory, puuid, mode);

            Map<String, List<OneGamePlayer>> oneGamePlayersMap = analyzeOneGamePlayers(enrichedHistory, puuid);
            recentData.setOneGamePlayersMap(oneGamePlayersMap);

            calculateFriendAndDispute(oneGamePlayersMap, recentData, puuid);

            return UserTag.builder()
                    .recentData(recentData)
                    .tag(tags)
                    .build();

        } catch (Exception e) {
            log.error("计算用户标签失败：{}", e.getMessage(), e);
            return createEmptyTag();
        }
    }

    private List<MatchHistory> enrichMatchHistory(List<MatchHistory> matchHistory) {
        for (MatchHistory game : matchHistory) {
            try {
                GameDetail detail = gameDetailCache.get(game.getGameId(),
                        id -> lcuHttpClient.get("lol-match-history/v1/games/" + id, GameDetail.class));

                if (detail != null && detail.getParticipants() != null) {
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

    private List<RankTag> evaluateTags(List<MatchHistory> matchHistory, String puuid, Integer mode) {
        return tagConfigService.evaluateTags(matchHistory, puuid, mode);
    }

    private MatchHistory.Participant getParticipantByPuuid(MatchHistory game, String puuid) {
        Integer participantId = null;
        if (game.getParticipantIdentities() != null) {
            for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
                if (identity.getPlayer() != null && puuid.equals(identity.getPlayer().getPuuid())) {
                    participantId = identity.getParticipantId();
                    break;
                }
            }
        }

        if (participantId != null && game.getParticipants() != null) {
            for (MatchHistory.Participant p : game.getParticipants()) {
                if (participantId.equals(p.getParticipantId())) {
                    return p;
                }
            }
        }

        return null;
    }

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

    private Map<String, List<OneGamePlayer>> analyzeOneGamePlayers(List<MatchHistory> matchHistory, String myPuuid) {
        Map<String, List<OneGamePlayer>> result = new HashMap<>();

        for (int index = 0; index < matchHistory.size(); index++) {
            MatchHistory game = matchHistory.get(index);
            if (game.getParticipants() == null) continue;

            Integer myTeamId = null;
            MatchHistory.Participant myParticipant = getParticipantByPuuid(game, myPuuid);
            if (myParticipant != null) {
                myTeamId = myParticipant.getTeamId();
            }

            for (MatchHistory.Participant p : game.getParticipants()) {
                String playerPuuid = getPlayerPuuid(game, p.getParticipantId());

                if (playerPuuid == null || playerPuuid.isEmpty() || playerPuuid.equals(myPuuid)) {
                    continue;
                }

                MatchHistory.Stats stats = p.getStats();
                if (stats == null) continue;

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

    private void calculateFriendAndDispute(
            Map<String, List<OneGamePlayer>> oneGamePlayersMap,
            RecentData recentData,
            String myPuuid) {

        List<List<OneGamePlayer>> friendsList = new ArrayList<>();
        List<List<OneGamePlayer>> disputeList = new ArrayList<>();

        for (Map.Entry<String, List<OneGamePlayer>> entry : oneGamePlayersMap.entrySet()) {
            List<OneGamePlayer> games = entry.getValue();

            if (games.size() < 3) continue;

            boolean allSameTeam = games.stream().allMatch(OneGamePlayer::getIsMyTeam);

            if (allSameTeam) {
                friendsList.add(games);
            } else {
                disputeList.add(games);
            }
        }

        List<OneGamePlayerSummoner> friendsSummoner = new ArrayList<>();
        int friendsWins = 0, friendsLosses = 0;

        for (List<OneGamePlayer> games : friendsList.stream().limit(5).toList()) {
            int wins = (int) games.stream().filter(OneGamePlayer::getWin).count();
            int losses = games.size() - wins;
            friendsWins += wins;
            friendsLosses += losses;

            try {
                Summoner summoner = summonerService.getSummonerByPuuid(games.getFirst().getPuuid());
                friendsSummoner.add(OneGamePlayerSummoner.builder()
                        .winRate(wins * 100 / games.size())
                        .wins(wins)
                        .losses(losses)
                        .summoner(summoner)
                        .oneGamePlayer(games)
                        .build());
            } catch (Exception e) {
                log.debug("获取召唤师信息失败：{}", e.getMessage());
            }
        }

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
                Summoner summoner = summonerService.getSummonerByPuuid(games.get(0).getPuuid());
                disputeSummoner.add(OneGamePlayerSummoner.builder()
                        .winRate(wins * 100 / enemyGames.size())
                        .wins(wins)
                        .losses(losses)
                        .summoner(summoner)
                        .oneGamePlayer(new ArrayList<>(enemyGames))
                        .build());
            } catch (Exception e) {
                log.debug("获取召唤师信息失败：{}", e.getMessage());
            }
        }

        int totalFriends = friendsWins + friendsLosses;
        int totalDispute = disputeWins + disputeLosses;

        recentData.getFriendAndDispute().setFriendsRate(totalFriends > 0 ? friendsWins * 100 / totalFriends : 0);
        recentData.getFriendAndDispute().setDisputeRate(totalDispute > 0 ? disputeWins * 100 / totalDispute : 0);
        recentData.getFriendAndDispute().setFriendsSummoner(friendsSummoner);
        recentData.getFriendAndDispute().setDisputeSummoner(disputeSummoner);
    }

    private UserTag createEmptyTag() {
        return UserTag.builder()
                .recentData(RecentData.builder()
                        .selectModeCn("全部模式")
                        .build())
                .tag(new ArrayList<>())
                .build();
    }
}
