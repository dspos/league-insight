package com.ekko.insight.service;

import com.ekko.insight.model.MatchHistory;
import com.ekko.insight.model.WinRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 战绩数据服务
 * 提供召唤师战绩查询和统计功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchHistoryService {

    private final LcuHttpClient lcuHttpClient;

    private Cache<String, MatchHistory[]> matchHistoryCache;

    @PostConstruct
    public void init() {
        this.matchHistoryCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
        log.info("战绩服务初始化完成");
    }

    /**
     * 获取对局记录
     * 注意：LCU API 的 begIndex 参数不支持从任意位置开始，所以采用以下策略：
     * 1. 总是获取 0-49 的全部数据
     * 2. 在内存中根据 begIndex 和 endIndex 进行切片
     */
    public List<MatchHistory> getMatchHistory(String puuid, int begIndex, int endIndex) {
        MatchHistory[] matches = matchHistoryCache.get(puuid, this::fetchMatchHistory);

        if (matches == null || matches.length == 0) {
            return List.of();
        }

        int beg = Math.max(0, begIndex);
        int end = Math.min(endIndex + 1, matches.length);

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
     * 获取单局详情
     * @param gameId 对局 ID
     * @return 对局详情
     */
    public com.ekko.insight.model.GameDetail getGameDetailById(Long gameId) {
        String uri = String.format("lol-match-history/v1/games/%d", gameId);
        return lcuHttpClient.get(uri, com.ekko.insight.model.GameDetail.class);
    }

    /**
     * 获取筛选后的对局记录
     */
    public List<MatchHistory> getFilteredMatchHistory(String puuid, int begIndex, int endIndex,
                                                       Integer queueId, Integer championId, int maxResults) {
        MatchHistory[] allMatches = matchHistoryCache.get(puuid, this::fetchMatchHistory);

        if (allMatches == null || allMatches.length == 0) {
            return List.of();
        }

        List<MatchHistory> filteredMatches = new java.util.ArrayList<>();
        for (MatchHistory match : allMatches) {
            boolean queueMatches = queueId == null || queueId <= 0 ||
                    (match.getQueueId() != null && match.getQueueId().equals(queueId));

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

        int beg = Math.max(0, begIndex);
        int end = Math.min(endIndex + 1, filteredMatches.size());

        if (beg >= end) {
            return List.of();
        }

        return filteredMatches.subList(beg, end);
    }

    /**
     * 获取胜率统计
     */
    public WinRate getWinRate(String puuid, Integer mode) {
        List<MatchHistory> matches = getMatchHistory(puuid, 0, 49);

        int wins = 0;
        int losses = 0;

        for (MatchHistory match : matches) {
            if (mode != null && mode > 0 && !mode.equals(match.getQueueId())) {
                continue;
            }

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
     */
    public Map<String, WinRate> getRankedWinRates(String puuid) {
        List<MatchHistory> matches = getMatchHistory(puuid, 0, 49);

        int soloWins = 0, soloLosses = 0;
        int flexWins = 0, flexLosses = 0;

        for (MatchHistory match : matches) {
            Integer queueId = match.getQueueId();
            if (queueId == null) continue;

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

        Map<String, WinRate> result = new HashMap<>();
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

    /**
     * 刷新指定玩家战绩缓存
     */
    public void refreshCache(String puuid) {
        matchHistoryCache.invalidate(puuid);
    }

    /**
     * 刷新所有缓存
     */
    public void refreshAllCache() {
        matchHistoryCache.invalidateAll();
    }
}
