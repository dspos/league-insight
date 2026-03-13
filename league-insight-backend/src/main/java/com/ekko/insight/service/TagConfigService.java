package com.ekko.insight.service;

import com.ekko.insight.model.MatchHistory;
import com.ekko.insight.model.RankTag;
import com.ekko.insight.model.TagConfig;
import com.ekko.insight.model.TagConfig.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    @EventListener(ApplicationReadyEvent.class)
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

        log.debug("evaluateTags 开始, tagConfigs数量: {}, matchHistory数量: {}",
                tagConfigs.size(), matchHistory.size());

        for (TagConfig config : tagConfigs) {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                log.debug("标签 {} 未启用，跳过", config.getId());
                continue;
            }

            EvaluateResult evalResult = evaluateConditionWithResult(config.getCondition(), matchHistory, puuid, currentMode);
            log.debug("标签 {} 条件评估结果: {}, streakValue: {}", config.getId(), evalResult.matched, evalResult.streakValue);

            if (evalResult.matched) {
                String displayName = formatName(config.getName(), evalResult.streakValue);
                result.add(RankTag.builder()
                        .good(config.getGood())
                        .tagName(displayName)
                        .tagDesc(config.getDesc())
                        .build());
            }
        }

        return result;
    }

    /**
     * 评估结果（包含连胜/连败次数）
     */
    private static class EvaluateResult {
        boolean matched;
        int streakValue; // 连胜/连败次数（正数连胜，负数连败）

        EvaluateResult(boolean matched) {
            this.matched = matched;
            this.streakValue = 0;
        }

        EvaluateResult(boolean matched, int streakValue) {
            this.matched = matched;
            this.streakValue = streakValue;
        }
    }

    /**
     * 评估条件并返回结果（包含连胜数）
     */
    private EvaluateResult evaluateConditionWithResult(TagCondition condition, List<MatchHistory> history,
                                                       String puuid, Integer currentMode) {
        if (condition instanceof TagCondition.HistoryCondition hc) {
            // 应用过滤器
            List<MatchHistory> filtered = history;
            for (MatchFilter filter : hc.getFilters()) {
                filtered = applyFilter(filtered, filter, puuid);
            }

            // 应用刷新器并获取连胜数
            if (hc.getRefresh() instanceof MatchRefresh.StreakRefresh sr) {
                int streak = calculateStreak(filtered, puuid, sr.getKind());
                boolean matched = streak >= sr.getMin();
                // 正数表示连胜，负数表示连败
                int streakValue = sr.getKind() == StreakType.LOSS ? -streak : streak;
                return new EvaluateResult(matched, streakValue);
            } else {
                boolean matched = applyRefresh(hc.getRefresh(), filtered, puuid);
                return new EvaluateResult(matched);
            }
        } else {
            boolean matched = evaluateCondition(condition, history, puuid, currentMode);
            return new EvaluateResult(matched);
        }
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
            int beforeSize = filtered.size();
            filtered = applyFilter(filtered, filter, puuid);
            log.debug("过滤后: {} -> {} 条记录, filter={}", beforeSize, filtered.size(), filter.getClass().getSimpleName());
        }

        // 应用刷新器
        boolean result = applyRefresh(condition.getRefresh(), filtered, puuid);
        log.debug("刷新器结果: {}, filtered.size={}, refresh={}", result, filtered.size(),
                condition.getRefresh().getClass().getSimpleName());
        return result;
    }

    private List<MatchHistory> applyFilter(List<MatchHistory> games, MatchFilter filter, String puuid) {
        if (filter instanceof MatchFilter.QueueFilter qf) {
            List<MatchHistory> result = games.stream()
                    .filter(g -> g.getQueueId() != null && qf.getIds().contains(g.getQueueId()))
                    .toList();

            // 调试：打印所有 queueId
            if (result.isEmpty() && !games.isEmpty()) {
                List<Integer> queueIds = games.stream()
                        .map(MatchHistory::getQueueId)
                        .distinct()
                        .toList();
                log.debug("QueueFilter 无匹配! 过滤目标: {}, 实际 queueIds: {}", qf.getIds(), queueIds);
            }

            return result;
        }

        if (filter instanceof MatchFilter.ChampionFilter cf) {
            return games.stream()
                    .filter(g -> {
                        MatchHistory.Participant p = findParticipant(g, puuid);
                        return p != null && p.getChampionId() != null && cf.getIds().contains(p.getChampionId());
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

    private String formatName(String name, int streakValue) {
        if (name != null && name.contains("{N}")) {
            String nCn = numberToChinese(Math.abs(streakValue));
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
                tagConfigs = objectMapper.readValue(configFile, new TypeReference<>() {
                });
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

        // 排位过滤器（包含所有排位类型）
        List<Integer> rankedIds = List.of(
                430,
                420,
                440,
                450,
                2400
        );

        // 连胜标签
        defaults.add(new TagConfig(
                "default_streak_win",
                "{N}连胜",
                "最近胜率较高的大腿玩家哦",
                true,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(rankedIds)),
                        new MatchRefresh.StreakRefresh(3, StreakType.WIN)
                )
        ));

        // 连败标签
        defaults.add(new TagConfig(
                "default_streak_loss",
                "{N}连败",
                "最近连败的玩家哦",
                false,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(rankedIds)),
                        new MatchRefresh.StreakRefresh(3, StreakType.LOSS)
                )
        ));

        // 娱乐玩家标签
        List<Integer> casualIds = List.of(430, 450, 900, 1900, 2000); // 匹配、大乱斗等
        defaults.add(new TagConfig(
                "default_casual",
                "娱乐",
                "排位比例较少",
                false,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(new MatchFilter.QueueFilter(casualIds)),
                        new MatchRefresh.CountRefresh(Operator.GT, 5.0)
                )
        ));

        // 峡谷慈善家标签
        defaults.add(new TagConfig(
                "default_feeder",
                "峡谷慈善家",
                "死亡数较多的玩家",
                false,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.StatFilter("deaths", Operator.GTE, 10.0)
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                )
        ));

        // Carry 玩家标签
        defaults.add(new TagConfig(
                "default_carry",
                "Carry",
                "近期比赛多次 Carry",
                true,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.StatFilter("kda", Operator.GTE, 6.0)
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                )
        ));

        // 小火龙专精标签
        defaults.add(new TagConfig(
                "default_special_smolder",
                "小火龙",
                "该玩家使用小火龙场次较多",
                false,
                true,
                true,
                new TagCondition.HistoryCondition(
                        List.of(
                                new MatchFilter.QueueFilter(rankedIds),
                                new MatchFilter.ChampionFilter(List.of(901))
                        ),
                        new MatchRefresh.CountRefresh(Operator.GTE, 5.0)
                )
        ));

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
