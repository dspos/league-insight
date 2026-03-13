package com.ekko.insight.service;

import com.ekko.insight.model.AramBalanceData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fandom 数据服务
 * 从 Fandom Wiki 获取 ARAM 平衡性数据
 */
@Slf4j
@Service
public class FandomService {

    private static final String FANDOM_API_URL = "https://leagueoflegends.fandom.com/api.php" +
            "?action=query&format=json&prop=revisions" +
            "&titles=Module:ChampionData/data&rvprop=content&rvslots=main";

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ARAM 平衡数据缓存 (2小时)
    private final Cache<Integer, AramBalanceData> aramCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    /**
     * 初始化时加载数据
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("初始化 Fandom 服务...");
        // 异步加载数据
        new Thread(() -> {
            try {
                Thread.sleep(5000); // 延迟5秒后加载
                updateAramBalanceData();
            } catch (Exception e) {
                log.warn("初始加载 ARAM 数据失败: {}", e.getMessage());
            }
        }, "fandom-init").start();
    }

    /**
     * 更新 ARAM 平衡性数据
     */
    public String updateAramBalanceData() {
        log.info("开始获取 Fandom ARAM 平衡性数据...");

        try {
            Request request = new Request.Builder()
                    .url(FANDOM_API_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("请求失败: %d".formatted(response.code()));
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                // 解析 Fandom API 响应
                JsonNode pages = root.path("query").path("pages");
                if (pages.isMissingNode()) {
                    throw new RuntimeException("响应格式错误");
                }

                // 获取第一个页面的内容
                JsonNode page = pages.fields().next().getValue();
                JsonNode content = page.path("revisions").get(0).path("slots").path("main").path("*");

                if (content.isMissingNode()) {
                    throw new RuntimeException("无法获取 Lua 脚本内容");
                }

                String luaScript = content.asText();
                log.info("获取到 Lua 脚本，长度: {}", luaScript.length());

                // 解析 Lua 数据
                return parseLuaScript(luaScript);
            }

        } catch (Exception e) {
            log.error("更新 ARAM 平衡性数据失败: {}", e.getMessage());
            return "更新失败: " + e.getMessage();
        }
    }

    /**
     * 解析 Lua 脚本提取 ARAM 数据
     */
    private String parseLuaScript(String luaScript) {
        // 简化的 Lua 解析 - 使用正则表达式
        // 实际项目中建议使用 Lua 解析库
        Map<Integer, AramBalanceData> data = new HashMap<>();

        // 匹配英雄数据块
        Pattern championPattern = Pattern.compile(
                "\\[\"[^\"]+\"\\]\\s*=\\s*\\{[^}]*id\\s*=\\s*(\\d+)[^}]*stats\\s*=\\s*\\{[^}]*aram\\s*=\\s*\\{([^}]*)\\}[^}]*\\}[^}]*\\}",
                Pattern.DOTALL
        );

        // 尝试更简单的匹配
        Pattern idPattern = Pattern.compile("id\\s*=\\s*(\\d+)");
        Pattern aramPattern = Pattern.compile("aram\\s*=\\s*\\{([^}]+)\\}");

        String[] lines = luaScript.split("\n");
        Integer currentId = null;
        StringBuilder aramBlock = null;

        for (String line : lines) {
            // 查找 ID
            Matcher idMatcher = idPattern.matcher(line);
            if (idMatcher.find()) {
                currentId = Integer.parseInt(idMatcher.group(1));
            }

            // 查找 ARAM 块
            if (line.contains("aram = {")) {
                aramBlock = new StringBuilder();
            }

            if (aramBlock != null) {
                aramBlock.append(line).append("\n");
                if (line.contains("}")) {
                    // 解析 ARAM 数据
                    if (currentId != null) {
                        AramBalanceData balance = parseAramBlock(currentId, aramBlock.toString());
                        if (balance != null) {
                            data.put(currentId, balance);
                            aramCache.put(currentId, balance);
                        }
                    }
                    aramBlock = null;
                }
            }
        }

        log.info("解析完成，共获取 {} 个英雄的 ARAM 平衡数据", data.size());
        return "成功更新 %d 个英雄的数据".formatted(data.size());
    }

    /**
     * 解析 ARAM 数据块
     */
    private AramBalanceData parseAramBlock(Integer championId, String block) {
        AramBalanceData data = new AramBalanceData();
        data.setChampionId(championId);

        // 使用正则提取各项数值
        data.setDmgDealt(extractDouble(block, "dmg_dealt"));
        data.setDmgTaken(extractDouble(block, "dmg_taken"));
        data.setHealing(extractDouble(block, "healing"));
        data.setShielding(extractDouble(block, "shielding"));
        data.setAbilityHaste(extractDouble(block, "ability_haste"));
        data.setManaRegen(extractDouble(block, "mana_regen"));
        data.setEnergyRegen(extractDouble(block, "energy_regen"));
        data.setAttackSpeed(extractDouble(block, "attack_speed"));
        data.setMovementSpeed(extractDouble(block, "movement_speed"));
        data.setTenacity(extractDouble(block, "tenacity"));

        // 如果没有任何有效数据，返回 null
        if (data.getDmgDealt() == null && data.getDmgTaken() == null) {
            return null;
        }

        return data;
    }

    /**
     * 从文本中提取浮点数值
     */
    private Double extractDouble(String text, String key) {
        Pattern pattern = Pattern.compile(key + "\\s*=\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 获取英雄的 ARAM 平衡数据
     */
    public AramBalanceData getAramBalance(Integer championId) {
        return aramCache.getIfPresent(championId);
    }

    /**
     * 获取所有 ARAM 平衡数据
     */
    public Map<Integer, AramBalanceData> getAllAramBalance() {
        Map<Integer, AramBalanceData> result = new HashMap<>();
        aramCache.asMap().forEach(result::put);
        return result;
    }

    /**
     * 检查缓存是否有数据
     */
    public boolean hasData() {
        return aramCache.estimatedSize() > 0;
    }
}
