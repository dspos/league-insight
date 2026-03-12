package com.ekko.insight.service;

import com.ekko.insight.model.MatchHistory;
import com.ekko.insight.model.RankTag;
import com.ekko.insight.model.TagConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ekko.insight.model.TagConfig.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 标签配置服务
 * 管理标签规则的加载、保存、评估
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagConfigService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final File configFile = new File("tag-config.json");

    private List<TagConfig> tagConfigs = new ArrayList<>();

    // 队列 ID 常量
    private static final int QUEUE_SOLO_5X5 = 420;
    private static final int QUEUE_FLEX = 440;

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * 获取所有标签配置
     */
    public List<TagConfig> getAllTagConfigs() {
        return new ArrayList<>(tagConfigs);
    }

    /**
     * 保存标签配置
     */
    public void saveTagConfigs(List<TagConfig> configs) {
        this.tagConfigs = new ArrayList<>(configs);
        persistConfig();
    }

    /**
     * 根据战绩评估标签
     */
    public List<RankTag> evaluateTags(List<MatchHistory> matchHistory, String puuid, Integer currentMode) {
        List<RankTag> result = new ArrayList<>();

        for (TagConfig config : tagConfigs) {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                continue;
            }

            if (evaluateCondition(config.getCondition(), matchHistory, puuid, currentMode)) {
                String displayName = formatName(config.getName(), matchHistory, puuid);
                result.add(RankTag.builder()
                        .good(config.getGood())
                        .tagName(displayName)
                        .tagDesc(config.getDesc())
                        .build());
            }
        }

        return result;
    }

    // ========== 条件评估 ==========

    private boolean evaluateCondition(TagCondition condition, List<MatchHistory> history,
                                       String puuid, Integer currentMode) {
        return switch (condition) {
            case TagCondition.AndCondition and -> and.getConditions().stream()
                    .allMatch(c -> evaluateCondition(c, history, puuid, currentMode));
            case TagCondition.OrCondition or -> or.getConditions().stream()
                    .anyMatch(c -> evaluateCondition(c, history, puuid, currentMode));
            case TagCondition.NotCondition not -> !evaluateCondition(not.getCondition(), history, puuid, currentMode);
            case TagCondition.CurrentQueueCondition cq -> cq.getIds().contains(currentMode);
            case TagCondition.CurrentChampionCondition cc ->
                // 暂不实现，需要传入当前英雄
                    false;
            case TagCondition.HistoryCondition hc -> evaluateHistoryCondition(hc, history, puuid);
            case null, default -> false;
        };

    }

    private boolean evaluateHistoryCondition(TagCondition.HistoryCondition condition,
                                              List<MatchHistory> history, String puuid) {
        // 应用过滤器
        List<MatchHistory> filtered = history;
        for (MatchFilter filter : condition.getFilters()) {
            filtered = applyFilter(filtered, filter, puuid);
        }

        // 应用刷新器
        return applyRefresh(condition.getRefresh(), filtered, puuid);
    }

    private List<MatchHistory> applyFilter(List<MatchHistory> games, MatchFilter filter, String puuid) {
        if (filter instanceof MatchFilter.QueueFilter qf) {
            return games.stream()
                    .filter(g -> qf.getIds().contains(g.getQueueId()))
                    .toList();
        }

        if (filter instanceof MatchFilter.ChampionFilter cf) {
            return games.stream()
                    .filter(g -> {
                        MatchHistory.Participant p = findParticipant(g, puuid);
                        return p != null && cf.getIds().contains(p.getChampionId());
                    })
                    .toList();
        }

        if (filter instanceof MatchFilter.StatFilter sf) {
            return games.stream()
                    .filter(g -> {
                        double value = extractMetric(g, puuid, sf.getMetric());
                        return sf.getOp().check(value, sf.getValue());
                    })
                    .toList();
        }

        return games;
    }

    private boolean applyRefresh(MatchRefresh refresh, List<MatchHistory> games, String puuid) {
        if (refresh instanceof MatchRefresh.CountRefresh cr) {
            return cr.getOp().check(games.size(), cr.getValue());
        }

        if (refresh instanceof MatchRefresh.AverageRefresh ar) {
            if (games.isEmpty()) return false;
            double total = games.stream()
                    .mapToDouble(g -> extractMetric(g, puuid, ar.getMetric()))
                    .sum();
            return ar.getOp().check(total / games.size(), ar.getValue());
        }

        if (refresh instanceof MatchRefresh.SumRefresh sr) {
            double total = games.stream()
                    .mapToDouble(g -> extractMetric(g, puuid, sr.getMetric()))
                    .sum();
            return sr.getOp().check(total, sr.getValue());
        }

        if (refresh instanceof MatchRefresh.MaxRefresh mr) {
            if (games.isEmpty()) return false;
            double max = games.stream()
                    .mapToDouble(g -> extractMetric(g, puuid, mr.getMetric()))
                    .max()
                    .orElse(Double.MIN_VALUE);
            return mr.getOp().check(max, mr.getValue());
        }

        if (refresh instanceof MatchRefresh.MinRefresh mir) {
            if (games.isEmpty()) return false;
            double min = games.stream()
                    .mapToDouble(g -> extractMetric(g, puuid, mir.getMetric()))
                    .min()
                    .orElse(Double.MAX_VALUE);
            return mir.getOp().check(min, mir.getValue());
        }

        if (refresh instanceof MatchRefresh.StreakRefresh sr) {
            int streak = calculateStreak(games, puuid, sr.getKind());
            return streak >= sr.getMin();
        }

        return false;
    }

    // ========== 工具方法 ==========

    /**
     * 根据 puuid 查找参与者
     */
    private MatchHistory.Participant findParticipant(MatchHistory game, String puuid) {
        if (game.getParticipants() == null || game.getParticipantIdentities() == null) return null;

        // 先从 participantIdentities 找到 participantId
        Integer participantId = null;
        for (MatchHistory.ParticipantIdentity identity : game.getParticipantIdentities()) {
            if (identity.getPlayer() != null && puuid.equals(identity.getPlayer().getPuuid())) {
                participantId = identity.getParticipantId();
                break;
            }
        }

        if (participantId == null) return null;

        // 根据 participantId 找到 participant
        for (MatchHistory.Participant p : game.getParticipants()) {
            if (participantId.equals(p.getParticipantId())) {
                return p;
            }
        }

        return null;
    }

    private double extractMetric(MatchHistory game, String puuid, String metric) {
        MatchHistory.Participant p = findParticipant(game, puuid);
        if (p == null || p.getStats() == null) return 0.0;

        MatchHistory.Stats stats = p.getStats();

        return switch (metric.toLowerCase()) {
            case "kills" -> stats.getKills() != null ? stats.getKills() : 0;
            case "deaths" -> stats.getDeaths() != null ? stats.getDeaths() : 0;
            case "assists" -> stats.getAssists() != null ? stats.getAssists() : 0;
            case "kda" -> {
                int deaths = stats.getDeaths() != null && stats.getDeaths() > 0 ? stats.getDeaths() : 1;
                yield ((stats.getKills() != null ? stats.getKills() : 0) +
                       (stats.getAssists() != null ? stats.getAssists() : 0)) * 1.0 / deaths;
            }
            case "win" -> Boolean.TRUE.equals(stats.getWin()) ? 1.0 : 0.0;
            case "gold" -> stats.getGoldEarned() != null ? stats.getGoldEarned() : 0;
            case "cs" -> {
                int cs = stats.getTotalMinionsKilled() != null ? stats.getTotalMinionsKilled() : 0;
                cs += stats.getNeutralMinionsKilled() != null ? stats.getNeutralMinionsKilled() : 0;
                yield cs;
            }
            case "damage" -> stats.getTotalDamageDealtToChampions() != null ?
                    stats.getTotalDamageDealtToChampions() : 0;
            default -> 0.0;
        };
    }

    private int calculateStreak(List<MatchHistory> games, String puuid, StreakType kind) {
        int streak = 0;

        for (MatchHistory game : games) {
            MatchHistory.Participant p = findParticipant(game, puuid);
            if (p == null || p.getStats() == null) continue;

            boolean win = Boolean.TRUE.equals(p.getStats().getWin());

            if (kind == StreakType.WIN && win) {
                streak++;
            } else if (kind == StreakType.LOSS && !win) {
                streak++;
            } else {
                break;
            }
        }

        return streak;
    }

    private int getGlobalStreak(List<MatchHistory> history, String puuid) {
        int streak = 0;
        Boolean isWin = null;

        for (MatchHistory game : history) {
            // 只统计排位
            if (game.getQueueId() != QUEUE_SOLO_5X5 && game.getQueueId() != QUEUE_FLEX) {
                continue;
            }

            MatchHistory.Participant p = findParticipant(game, puuid);
            if (p == null || p.getStats() == null) continue;

            boolean win = Boolean.TRUE.equals(p.getStats().getWin());

            if (isWin == null) {
                isWin = win;
            }

            if (win == isWin) {
                streak++;
            } else {
                break;
            }
        }

        return isWin != null && isWin ? streak : -streak;
    }

    private String formatName(String name, List<MatchHistory> history, String puuid) {
        if (name != null && name.contains("{N}")) {
            int streak = getGlobalStreak(history, puuid);
            String nCn = numberToChinese(Math.abs(streak));
            return name.replace("{N}", nCn);
        }
        return name;
    }

    private String numberToChinese(int num) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (num >= 0 && num < 10) {
            return digits[num];
        }
        return String.valueOf(num);
    }

    // ========== 配置持久化 ==========

    private void loadConfig() {
        if (configFile.exists()) {
            try {
                tagConfigs = objectMapper.readValue(configFile, new TypeReference<>() {});
                log.info("已加载 {} 个标签配置", tagConfigs.size());
            } catch (IOException e) {
                log.warn("加载标签配置失败，使用默认配置: {}", e.getMessage());
                tagConfigs = getDefaultTags();
            }
        } else {
            tagConfigs = getDefaultTags();
            persistConfig();
        }
    }

    private void persistConfig() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, tagConfigs);
            log.info("已保存 {} 个标签配置", tagConfigs.size());
        } catch (IOException e) {
            log.error("保存标签配置失败: {}", e.getMessage());
        }
    }

    /**
     * 获取默认标签配置
     */
    public List<TagConfig> getDefaultTags() {
        List<TagConfig> defaults = new ArrayList<>();

        // 排位过滤器
        List<Integer> rankedIds = List.of(QUEUE_SOLO_5X5, QUEUE_FLEX);

        // 连胜标签
        defaults.add(TagConfig.builder()
                .id("default_streak_win")
                .name("{N}连胜")
                .desc("最近胜率较高的大腿玩家哦")
                .good(true)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(rankedIds)),
                        new MatchRefresh.StreakRefresh(3, StreakType.WIN)
                ))
                .build());

        // 连败标签
        defaults.add(TagConfig.builder()
                .id("default_streak_loss")
                .name("{N}连败")
                .desc("最近连败的玩家哦")
                .good(false)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(rankedIds)),
                        new MatchRefresh.StreakRefresh(3, StreakType.LOSS)
                ))
                .build());

        // 娱乐玩家标签
        List<Integer> casualIds = List.of(430, 450, 900); // 匹配、大乱斗等
        defaults.add(TagConfig.builder()
                .id("default_casual")
                .name("娱乐")
                .desc("排位比例较少")
                .good(false)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(casualIds)),
                        new MatchRefresh.CountRefresh(Operator.GT, 5.0)
                ))
                .build());

        // 峡谷慈善家标签
        defaults.add(TagConfig.builder()
                .id("default_feeder")
                .name("峡谷慈善家")
                .desc("死亡数较多的玩家")
                .good(false)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.StatFilter("deaths", Operator.GTE, 10.0)
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                ))
                .build());

        // Carry 玩家标签
        defaults.add(TagConfig.builder()
                .id("default_carry")
                .name("Carry")
                .desc("近期比赛多次Carry")
                .good(true)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.StatFilter("kda", Operator.GTE, 6.0)
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                ))
                .build());

        // 小火龙专精标签
        defaults.add(TagConfig.builder()
                .id("default_special_smolder")
                .name("小火龙")
                .desc("该玩家使用小火龙场次较多")
                .good(false)
                .enabled(true)
                .isDefault(true)
                .condition(new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.ChampionFilter(List.of(901))
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                ))
                .build());

        return defaults;
    }

    /**
     * 重置为默认配置
     */
    public void resetToDefault() {
        tagConfigs = getDefaultTags();
        persistConfig();
    }

    /**
     * 添加标签配置
     */
    public void addTagConfig(TagConfig config) {
        tagConfigs.add(config);
        persistConfig();
    }

    /**
     * 更新标签配置
     */
    public void updateTagConfig(String id, TagConfig config) {
        tagConfigs.removeIf(c -> c.getId().equals(id));
        tagConfigs.add(config);
        persistConfig();
    }

    /**
     * 删除标签配置
     */
    public void deleteTagConfig(String id) {
        tagConfigs.removeIf(c -> c.getId().equals(id));
        persistConfig();
    }

    /**
     * 切换标签启用状态
     */
    public void toggleTagConfig(String id) {
        tagConfigs.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .ifPresent(c -> c.setEnabled(!Boolean.TRUE.equals(c.getEnabled())));
        persistConfig();
    }
}
