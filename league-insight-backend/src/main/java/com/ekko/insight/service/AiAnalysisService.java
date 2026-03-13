package com.ekko.insight.service;

import com.ekko.insight.config.AppConfig;
import com.ekko.insight.model.AIAnalysisResult;
import com.ekko.insight.model.GameDetail;
import com.ekko.insight.model.SessionData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AI 分析服务
 * 提供对局 AI 复盘分析功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final LcuService lcuService;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    // 分析结果缓存
    private final Cache<String, String> analysisCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private static final String DEFAULT_AI_ENDPOINT = "your endpoint";
    private static final String DEFAULT_MODEL = "your model";
    private static final String DEFAULT_API_KEY = "your api key";
    private static final String SYSTEM_PROMPT = "你是一个LOL游戏分析师，擅长分析玩家战绩和给出游戏建议。请用简洁、专业、直接的中文回复。所有结论都必须绑定数据证据，避免空泛。";

    /**
     * 分析对局详情
     * @param gameId 游戏ID
     * @param mode 分析模式：overview（整局总览）、player（单人复盘）
     * @param participantId 玩家ID（单人复盘时使用）
     * @return 分析结果
     */
    public AIAnalysisResult analyzeGameDetail(Long gameId, String mode, Integer participantId) {
        String cacheKey = String.format("analysis_%s_%d_%s", mode, gameId,
                participantId != null ? participantId : "all");

        // 检查缓存
        String cached = analysisCache.getIfPresent(cacheKey);
        if (cached != null) {
            return AIAnalysisResult.success(cached);
        }

        try {
            GameDetail gameDetail = lcuService.getGameDetailById(gameId);
            if (gameDetail == null) {
                return AIAnalysisResult.error("未找到对局详情");
            }

            String prompt;
            if ("player".equals(mode) && participantId != null) {
                prompt = buildPlayerAnalysisPrompt(gameDetail, participantId);
            } else {
                prompt = buildOverviewAnalysisPrompt(gameDetail);
            }

            String result = callAiApi(prompt);
            if (result != null) {
                analysisCache.put(cacheKey, result);
                return AIAnalysisResult.success(result);
            }

            return AIAnalysisResult.error("AI 分析失败");

        } catch (Exception e) {
            log.error("AI 分析失败: {}", e.getMessage(), e);
            return AIAnalysisResult.error("AI 分析失败: " + e.getMessage());
        }
    }

    /**
     * 分析房间级数据（组队阶段）
     * @param sessionData 会话数据
     * @param mode 分析模式：team（队伍分析）、player（单人分析）
     * @return 分析结果
     */
    public AIAnalysisResult analyzeSessionData(SessionData sessionData, String mode) {
        String cacheKey = String.format("session_%s_%d", mode, System.currentTimeMillis() / 60000);

        // 检查缓存
        String cached = analysisCache.getIfPresent(cacheKey);
        if (cached != null) {
            return AIAnalysisResult.success(cached);
        }

        try {
            String prompt;
            if ("player".equals(mode)) {
                prompt = buildSessionPlayerAnalysisPrompt(sessionData);
            } else {
                prompt = buildSessionTeamAnalysisPrompt(sessionData);
            }

            String result = callAiApi(prompt);
            if (result != null) {
                analysisCache.put(cacheKey, result);
                return AIAnalysisResult.success(result);
            }

            return AIAnalysisResult.error("AI 分析失败");

        } catch (Exception e) {
            log.error("AI 分析失败: {}", e.getMessage(), e);
            return AIAnalysisResult.error("AI 分析失败: " + e.getMessage());
        }
    }

    /**
     * 调用 AI API
     */
    private String callAiApi(String prompt) throws IOException {
        String apiKey = appConfig.getSettings().getAi().getApiKey();
        if (apiKey == null || apiKey.isEmpty() || "your-api-key-here".equals(apiKey)) {
            apiKey = DEFAULT_API_KEY;
        }

        String endpoint = appConfig.getSettings().getAi().getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = DEFAULT_AI_ENDPOINT;
        }

        String model = appConfig.getSettings().getAi().getModel();
        if (model == null || model.isEmpty()) {
            model = DEFAULT_MODEL;
        }

        log.info("调用 AI API: endpoint={}, model={}", endpoint, model);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        ));

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        long startTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AI API 响应: code={}, elapsed={}ms", response.code(), elapsed);

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                log.error("AI API 调用失败: code={}, body={}", response.code(), errorBody);
                return null;
            }

            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }

            String responseBody = body.string();
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

            if (responseMap.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
            }

            if (responseMap.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
                log.error("AI API 返回错误: {}", error.get("message"));
            }

            return null;
        }
    }

    /**
     * 构建整局总览分析提示词
     */
    private String buildOverviewAnalysisPrompt(GameDetail gameDetail) {
        Map<String, Object> snapshot = buildMatchSnapshot(gameDetail);

        return """
你是 LOL 单场复盘分析师。请只基于下面这场比赛的数据做结论，不要编造对线细节、团战时间点或装备效果。

【任务目标】
请你判断这场比赛里：
1. 谁最尽力
2. 谁最犯罪
3. 谁是被对位或被局势打爆的
4. 谁属于被队友连累
5. 胜负的核心原因是什么

【硬性要求】
- 每个判断都必须引用至少 2 个具体数据证据，例如 KDA、伤害占比、承伤占比、经济、参团率、推塔、死亡数。
- 不要因为输了就默认某个人犯罪，也不要因为赢了就默认某个人尽力。
- "被连累"只给在败方里数据明显完成职责、但团队整体明显失衡的人。
- "被爆"优先看高死亡、低经济占比、低输出占比、低参团，或者同队里明显拖后腿。
- 允许结论为"无人明显犯罪"或"多人都尽力"。
- 语气直接，纯锐评，突出一针见血。

【对局信息】
队列ID：%d
游戏模式：%s
时长：%d分%d秒
构筑类型：%s

【全场数据快照】
%s

【输出格式】
请严格按这个结构输出：

## 总体结论
- 先用 2-3 句话总结胜负原因。

## 尽力榜
- 只列 1-2 人。
- 每人一行：名字 + 判定 + 证据。

## 犯罪榜
- 只列 1-2 人。
- 如果没有明显犯罪，明确写"本局无人明显犯罪"。

## 被爆点评
- 点出 1-2 个最明显的崩点。

## 被连累点评
- 如果有人属于被连累，说明他做到了什么、却被哪些队友问题拖垮。

## 关键证据
- 用 3-5 条 bullet 收尾，每条都带数字。
""".formatted(
                gameDetail.getQueueId(),
                gameDetail.getGameMode(),
                gameDetail.getGameDuration() / 60,
                gameDetail.getGameDuration() % 60,
                isAugmentMode(gameDetail.getQueueId()) ? "海克斯/强化局，优先看强化搭配" : "常规局，优先看符文与基础数据",
                toJsonString(snapshot)
        );
    }

    /**
     * 构建单人分析提示词
     */
    private String buildPlayerAnalysisPrompt(GameDetail gameDetail, Integer participantId) {
        Map<String, Object> snapshot = buildMatchSnapshot(gameDetail);
        List<Map<String, Object>> players = (List<Map<String, Object>>) snapshot.get("players");

        Map<String, Object> targetPlayer = players.stream()
                .filter(p -> participantId.equals(((Number) p.get("participantId")).intValue()))
                .findFirst()
                .orElse(players.getFirst());

        List<Map<String, Object>> sameTeamPlayers = players.stream()
                .filter(p -> p.get("teamId").equals(targetPlayer.get("teamId")))
                .collect(Collectors.toList());

        List<Map<String, Object>> enemyPlayers = players.stream()
                .filter(p -> !p.get("teamId").equals(targetPlayer.get("teamId")))
                .collect(Collectors.toList());

        return """
你是 LOL 单人复盘分析师。请围绕指定玩家，判断他这局到底属于"尽力、犯罪、被爆、被连累、正常发挥"中的哪一类。

【硬性要求】
- 必须先给出唯一主标签，只能从：尽力 / 犯罪 / 被爆 / 被连累 / 正常发挥 中选一个。
- 所有结论必须基于数据，至少引用 3 个具体指标。
- 要区分"自己打得差"和"队友整体拖垮"这两种情况。
- 如果是海克斯/强化模式，请结合强化数量和构筑方向，判断是否成型。
- 不要空泛鼓励，不要写成攻略。

【对局信息】
游戏模式：%s
时长：%d分%d秒
构筑类型：%s

【目标玩家】
%s

【同队玩家】
%s

【敌方玩家】
%s

【输出格式】
请严格按这个结构输出：

## 玩家判定
- 先写：名字 + 主标签。

## 为什么这么判
- 用 3-4 条 bullet 解释，必须带数字。

## 他是怎么输/赢的
- 说明是自己打出来的、被针对的、还是被队友带飞/拖累。

## 一句话锐评
- 用一句短评收尾，允许直接一点，纯锐评。
""".formatted(
                gameDetail.getGameMode(),
                gameDetail.getGameDuration() / 60,
                gameDetail.getGameDuration() % 60,
                isAugmentMode(gameDetail.getQueueId()) ? "海克斯/强化局" : "常规局",
                toJsonString(targetPlayer),
                toJsonString(sameTeamPlayers),
                toJsonString(enemyPlayers)
        );
    }

    /**
     * 构建房间队伍分析提示词
     */
    private String buildSessionTeamAnalysisPrompt(SessionData sessionData) {
        // 简化的房间分析
        return """
你是LOL资深分析师，请从以下维度分析这局比赛的预组队情况：

【对局信息】
模式：%s
队列ID：%d

请简要分析双方阵容特点和可能的优势方。
""".formatted(sessionData.getTypeCn(), sessionData.getQueueId());
    }

    /**
     * 构建房间单人分析提示词
     */
    private String buildSessionPlayerAnalysisPrompt(SessionData sessionData) {
        return """
你是LOL资深分析师，请分析当前房间中玩家的实力分布。

【对局信息】
模式：%s

请简要分析各玩家的段位和近期表现。
""".formatted(sessionData.getTypeCn());
    }

    /**
     * 构建比赛快照数据
     */
    private Map<String, Object> buildMatchSnapshot(GameDetail gameDetail) {
        Map<Integer, Map<String, Long>> teamTotals = calculateTeamTotals(gameDetail.getParticipants());
        List<Map<String, Object>> players = buildPlayersData(gameDetail.getParticipants(), teamTotals);
        List<Map<String, Object>> teams = buildTeamsData(players);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gameId", gameDetail.getGameId());
        snapshot.put("queueId", gameDetail.getQueueId());
        snapshot.put("gameMode", gameDetail.getGameMode());
        snapshot.put("durationSeconds", gameDetail.getGameDuration());
        snapshot.put("augmentMode", isAugmentMode(gameDetail.getQueueId()));
        snapshot.put("teams", teams);
        snapshot.put("players", players);

        return snapshot;
    }

    /**
     * 计算队伍总和
     */
    private Map<Integer, Map<String, Long>> calculateTeamTotals(List<GameDetail.GameParticipant> participants) {
        Map<Integer, Map<String, Long>> teamTotals = new HashMap<>();
        
        for (GameDetail.GameParticipant participant : participants) {
            int teamId = participant.getTeamId();
            Map<String, Long> totals = teamTotals.computeIfAbsent(teamId, k -> new HashMap<>());
            GameDetail.Stats stats = participant.getStats();

            totals.merge("damage", stats.getTotalDamageDealtToChampions() != null ? stats.getTotalDamageDealtToChampions() : 0L, Long::sum);
            totals.merge("taken", stats.getTotalDamageTaken() != null ? stats.getTotalDamageTaken() : 0L, Long::sum);
            totals.merge("gold", stats.getGoldEarned() != null ? stats.getGoldEarned() : 0L, Long::sum);
            totals.merge("kills", stats.getKills() != null ? stats.getKills().longValue() : 0L, Long::sum);
        }
        
        return teamTotals;
    }

    /**
     * 构建玩家数据列表
     */
    private List<Map<String, Object>> buildPlayersData(
            List<GameDetail.GameParticipant> participants,
            Map<Integer, Map<String, Long>> teamTotals) {
        
        List<Map<String, Object>> players = new ArrayList<>();
        
        for (GameDetail.GameParticipant participant : participants) {
            Map<String, Object> playerData = buildPlayerData(participant, teamTotals);
            players.add(playerData);
        }
        
        return players;
    }

    /**
     * 构建单个玩家数据
     */
    private Map<String, Object> buildPlayerData(
            GameDetail.GameParticipant participant,
            Map<Integer, Map<String, Long>> teamTotals) {
        
        Map<String, Object> playerData = new LinkedHashMap<>();
        GameDetail.Stats stats = participant.getStats();
        Map<String, Long> totals = teamTotals.getOrDefault(participant.getTeamId(), new HashMap<>());

        // 基础数据
        playerData.put("participantId", participant.getParticipantId());
        playerData.put("teamId", participant.getTeamId());
        playerData.put("championId", participant.getChampionId());
        playerData.put("win", stats.getWin());

        // KDA 和统计
        int kills = stats.getKills() != null ? stats.getKills() : 0;
        int deaths = stats.getDeaths() != null ? stats.getDeaths() : 0;
        int assists = stats.getAssists() != null ? stats.getAssists() : 0;
        double kda = deaths > 0 ? (kills + assists) * 1.0 / deaths : kills + assists;
        
        playerData.put("kda", Math.round(kda * 100) / 100.0);
        playerData.put("kills", kills);
        playerData.put("deaths", deaths);
        playerData.put("assists", assists);

        // 经济和数据
        long damage = stats.getTotalDamageDealtToChampions() != null ? stats.getTotalDamageDealtToChampions() : 0;
        long taken = stats.getTotalDamageTaken() != null ? stats.getTotalDamageTaken() : 0;
        long gold = stats.getGoldEarned() != null ? stats.getGoldEarned() : 0;
        
        playerData.put("gold", gold);
        playerData.put("damage", damage);
        playerData.put("taken", taken);

        // 占比数据
        playerData.put("damageShare", calculateShare(damage, totals.getOrDefault("damage", 1L)));
        playerData.put("takenShare", calculateShare(taken, totals.getOrDefault("taken", 1L)));
        playerData.put("goldShare", calculateShare(gold, totals.getOrDefault("gold", 1L)));
        playerData.put("killParticipation", calculateShare(kills + assists, totals.getOrDefault("kills", 1L)));

        // 符文和强化
        playerData.put("perks", buildPerksData(stats));
        playerData.put("augments", buildAugmentsData(stats));
        playerData.put("name", getPlayerNameFromDetail(participant.getParticipantId()));

        return playerData;
    }

    /**
     * 计算占比
     */
    private double calculateShare(long value, long total) {
        if (total <= 0) return 0;
        return Math.round(value * 100.0 / total * 10) / 10.0;
    }

    /**
     * 构建符文数据
     */
    private Map<String, Integer> buildPerksData(GameDetail.Stats stats) {
        return Map.of(
                "primary", stats.getPerk0() != null ? stats.getPerk0() : 0,
                "subStyle", stats.getPerkSubStyle() != null ? stats.getPerkSubStyle() : 0
        );
    }

    /**
     * 构建强化数据
     */
    private List<Integer> buildAugmentsData(GameDetail.Stats stats) {
        List<Integer> augments = new ArrayList<>();
        if (stats.getPlayerAugment1() != null && stats.getPlayerAugment1() > 0) augments.add(stats.getPlayerAugment1());
        if (stats.getPlayerAugment2() != null && stats.getPlayerAugment2() > 0) augments.add(stats.getPlayerAugment2());
        if (stats.getPlayerAugment3() != null && stats.getPlayerAugment3() > 0) augments.add(stats.getPlayerAugment3());
        if (stats.getPlayerAugment4() != null && stats.getPlayerAugment4() > 0) augments.add(stats.getPlayerAugment4());
        return augments;
    }

    /**
     * 构建队伍数据列表
     */
    private List<Map<String, Object>> buildTeamsData(List<Map<String, Object>> players) {
        List<Map<String, Object>> teams = new ArrayList<>();
        
        Map<Integer, List<Map<String, Object>>> teamPlayers = players.stream()
                .collect(Collectors.groupingBy(p -> (Integer) p.get("teamId")));

        for (Map.Entry<Integer, List<Map<String, Object>>> entry : teamPlayers.entrySet()) {
            Map<String, Object> teamData = new LinkedHashMap<>();
            teamData.put("teamId", entry.getKey());
            teamData.put("result", determineTeamResult(entry.getValue()));
            teamData.put("players", entry.getValue());
            teams.add(teamData);
        }

        // 按胜负排序
        teams.sort(Comparator.comparing(t -> !"胜方".equals(t.get("result"))));
        
        return teams;
    }

    /**
     * 判断队伍结果
     */
    private String determineTeamResult(List<Map<String, Object>> teamPlayers) {
        if (teamPlayers.isEmpty()) return "未知";
        
        Boolean win = (Boolean) teamPlayers.get(0).get("win");
        return win != null && win ? "胜方" : "败方";
    }

    /**
     * 获取玩家名称（从 GameDetail 对象）
     */
    private String getPlayerName(GameDetail gameDetail, Integer participantId) {
        if (gameDetail.getParticipantIdentities() != null) {
            for (GameDetail.ParticipantIdentity identity : gameDetail.getParticipantIdentities()) {
                if (participantId.equals(identity.getParticipantId()) && identity.getPlayer() != null) {
                    GameDetail.Player player = identity.getPlayer();
                    if (player.getGameName() != null && !player.getGameName().isEmpty()) {
                        return player.getTagLine() != null ?
                                player.getGameName() + "#" + player.getTagLine() :
                                player.getGameName();
                    }
                    return player.getSummonerName() != null ? player.getSummonerName() : "未知";
                }
            }
        }
        return "玩家" + participantId;
    }

    /**
     * 获取玩家名称（从当前分析的 gameDetail）
     */
    private String getPlayerNameFromDetail(Integer participantId) {
        // 需要从外部传入或存储 currentGameDetail，这里暂时返回默认值
        return "玩家" + participantId;
    }

    /**
     * 判断是否是强化模式
     */
    private boolean isAugmentMode(Integer queueId) {
        if (queueId == null) return false;
        // 1700: 斗魂竞技场, 2400: 海克斯大乱斗
        return queueId == 1700 || queueId == 2400;
    }

    /**
     * 转换为 JSON 字符串
     */
    private String toJsonString(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 异步分析对局详情
     * @param gameId 游戏 ID
     * @param mode 分析模式
     * @param participantId 玩家 ID
     * @return CompletableFuture
     */
    @Async("aiExecutor")
    public CompletableFuture<AIAnalysisResult> analyzeGameDetailAsync(Long gameId, String mode, Integer participantId) {
        return CompletableFuture.completedFuture(analyzeGameDetail(gameId, mode, participantId));
    }
}
