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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final Cache<Integer, AramBalanceData> aramCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(4, TimeUnit.HOURS)
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("初始化 Fandom 服务...");
        // 使用虚拟线程或普通线程异步加载
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(3000);
                String result = updateAramBalanceData();
                log.info("初始化结果: {}", result);
            } catch (Exception e) {
                log.error("初始加载 ARAM 数据失败", e);
            }
        });
    }

    public String updateAramBalanceData() {
        log.info("开始获取 Fandom ARAM 平衡性数据...");
        try {
            Request request = new Request.Builder()
                    .url(FANDOM_API_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || Objects.isNull(response.body())) {
                    throw new RuntimeException("请求失败: " + response.code());
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode pages = root.path("query").path("pages");

                if (pages.isMissingNode() || !pages.isObject()) {
                    throw new RuntimeException("未找到页面数据");
                }

                JsonNode page = pages.properties()
                        .stream()
                        .findFirst()
                        .map(Map.Entry::getValue)
                        .orElseThrow(() -> new RuntimeException("Fandom API 返回的 pages 为空，无法获取目标页面数据"));
                JsonNode contentNode = page.path("revisions").get(0).path("slots").path("main").path("*");

                if (contentNode.isMissingNode()) {
                    throw new RuntimeException("无法获取 Lua 内容");
                }

                String luaScript = contentNode.asText();
                log.info("获取到 Lua 脚本，长度: {} 字符", luaScript.length());

                int count = parseLuaToAramData(luaScript);
                return "成功更新 " + count + " 个英雄的数据";
            }
        } catch (Exception e) {
            log.error("更新 ARAM 数据失败", e);
            return "更新失败: " + e.getMessage();
        }
    }

    /**
     * 核心解析逻辑：
     * 1. 提取每个英雄的定义块 [Name] = { ... }
     * 2. 从块中提取 id 和 aram 子表
     * 3. 将 Lua 风格的 aram 表转换为 JSON 并解析
     */
    private int parseLuaToAramData(String luaScript) {
        Map<Integer, AramBalanceData> newDataMap = new HashMap<>();

        // 步骤 1: 匹配英雄定义块
        // 匹配模式: ["Name"] = { ... }
        // 我们需要手动处理大括号的嵌套，因为正则很难完美匹配多层嵌套
        int count = 0;

        // 简单的状态机来分割英雄块
        // 寻找 pattern: ["Key"] = {
        Pattern headerPattern = Pattern.compile("\\[\"([^\"]+)\"\\]\\s*=\\s*\\{");
        Matcher matcher = headerPattern.matcher(luaScript);

        while (matcher.find()) {
            int start = matcher.end(); // 跳过 {
            String championName = matcher.group(1);

            // 寻找对应的闭合括号 }
            int end = findMatchingBrace(luaScript, start);
            if (end == -1) continue;

            String championBlock = luaScript.substring(start, end);

            // 步骤 2: 从块中提取 ID
            Integer id = extractId(championBlock);
            if (id == null) continue;

            // 步骤 3: 提取 aram 块
            String aramLua = extractAramBlock(championBlock);
            if (aramLua == null || aramLua.trim().isEmpty()) continue;

            // 步骤 4: 转换并解析
            try {
                AramBalanceData data = parseAramJson(aramLua);
                if (data != null) {
                    data.setChampionId(id);
                    newDataMap.put(id, data);
                    count++;
                }
            } catch (Exception e) {
                log.warn("解析英雄 {} (ID: {}) 的 ARAM 数据失败: {}", championName, id, e.getMessage());
            }
        }

        // 更新缓存
        aramCache.invalidateAll();
        newDataMap.forEach(aramCache::put);

        log.info("解析完成，共处理 {} 个英雄，缓存大小: {}", count, aramCache.estimatedSize());
        return count;
    }

    /**
     * 查找匹配的右大括号，处理嵌套
     */
    private int findMatchingBrace(String text, int startPos) {
        int depth = 1;
        int i = startPos;
        boolean inString = false;
        char stringChar = 0;
        boolean inComment = false;

        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);

            // 处理注释 (--)
            if (!inString && !inComment && c == '-' && i + 1 < text.length() && text.charAt(i+1) == '-') {
                inComment = true;
                i += 2;
                continue;
            }
            if (inComment && c == '\n') {
                inComment = false;
                i++;
                continue;
            }
            if (inComment) {
                i++;
                continue;
            }

            // 处理字符串
            if (!inString && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar && (i == 0 || text.charAt(i-1) != '\\')) {
                inString = false;
            }

            // 处理括号
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            i++;
        }
        return (depth == 0) ? i - 1 : -1;
    }

    private Integer extractId(String block) {
        Pattern idPattern = Pattern.compile("id\\s*=\\s*(\\d+)");
        Matcher m = idPattern.matcher(block);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private String extractAramBlock(String block) {
        // 尝试匹配 aram = { ... }
        Pattern aramStartPattern = Pattern.compile("aram\\s*=\\s*\\{");
        Matcher m = aramStartPattern.matcher(block);

        if (!m.find()) {
            // 如果没有找到 {，检查是否是 aram = , (空值)
            if (block.contains("aram") && block.matches(".*aram\\s*=\\s*,.*")) {
                return "{}"; // 返回空 JSON 对象
            }
            return null;
        }

        int start = m.end();
        int end = findMatchingBrace(block, start);

        if (end == -1) {
            // 如果找不到匹配的括号，可能是格式错误，尝试返回空
            log.warn("未找到 aram 块的闭合括号，视为空块");
            return "{}";
        }

        String content = block.substring(start, end).trim();

        // 如果是空块 {}
        if (content.isEmpty()) {
            return "{}";
        }

        return content;
    }

    /**
     * 将 Lua 表内容转换为 JSON 对象并解析
     * Lua: key = value,  -> JSON: "key": value,
     */
    private AramBalanceData parseAramJson(String luaContent) throws Exception {
        if (luaContent == null || luaContent.trim().isEmpty() || "{}".equals(luaContent.trim())) {
            return null; // 空数据直接返回
        }

        // 1. 移除单行注释 (-- ...)
        // 注意：要排除掉字符串内的 --，但简单场景下直接移除通常够用
        // 更严谨的做法是按行处理，如果在字符串外才移除
        String clean = luaContent.replaceAll("--[^\n]*", "");

        // 2. 移除多行注释 (--[[ ... ]]) - 简单处理
        clean = clean.replaceAll("--\\[\\[.*?\\]\\]", "");

        // 3. 预处理：移除那些 key = 后面没有有效值的行 (防止生成 "key": , )
        // 匹配: 单词 = (后面紧跟逗号、换行或右大括号)
        // 我们只保留 key = number 或 key = "string" 的行
        StringBuilder validLines = new StringBuilder();
        String[] lines = clean.split("\n");

        // 定义合法值的正则：数字 (含小数/负数) 或 双引号字符串
        Pattern validValuePattern = Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(-?\\d+(\\.\\d+)?|\"[^\"]*\")\\s*,?\\s*$");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 如果行符合合法模式，保留
            if (validValuePattern.matcher(line).matches()) {
                validLines.append(line).append("\n");
            } else {
                // 可选：打印调试日志，看看丢弃了什么
                // log.trace("丢弃非法 Lua 行: {}", line);
            }
        }

        clean = validLines.toString();
        if (clean.trim().isEmpty()) {
            return null;
        }

        // 4. 将 Lua 键值对转换为 JSON 格式
        // 匹配: key = value -> "key": value
        // 这里的 value 已经是经过筛选的数字或字符串了
        Pattern kvPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*");
        String jsonLike = kvPattern.matcher(clean).replaceAll("\"$1\": ");

        // 5. 处理尾随逗号 (JSON 不允许)
        // 替换 }, 为 }
        jsonLike = jsonLike.replaceAll(",\\s*}", "}");
        // 替换 ], 为 ] (以防万一有数组)
        jsonLike = jsonLike.replaceAll(",\\s*]", "]");
        // 处理可能出现的 ,\n} 情况
        jsonLike = jsonLike.replaceAll(",\\s*\n\\s*}", "\n}");

        String finalJson = "{" + jsonLike + "}";

        // 6. 使用 Jackson 解析
        try {
            JsonNode node = objectMapper.readTree(finalJson);
            AramBalanceData data = new AramBalanceData();

            // 映射字段 (保持与你原有逻辑一致)
            data.setDmgDealt(getDoubleOrNull(node, "dmg_dealt"));
            data.setDmgTaken(getDoubleOrNull(node, "dmg_taken"));
            data.setHealing(getDoubleOrNull(node, "healing"));
            data.setShielding(getDoubleOrNull(node, "shielding"));
            data.setAbilityHaste(getDoubleOrNull(node, "ability_haste"));
            data.setManaRegen(getDoubleOrNull(node, "mana_regen"));
            data.setEnergyRegen(getDoubleOrNull(node, "energy_regen"));
            data.setAttackSpeed(getDoubleOrNull(node, "attack_speed"));
            data.setMovementSpeed(getDoubleOrNull(node, "movement_speed"));
            data.setTenacity(getDoubleOrNull(node, "tenacity"));

            // 校验：如果关键字段都为空，认为该条目无效
            if (data.getDmgDealt() == null && data.getDmgTaken() == null && data.getHealing() == null) {
                return null;
            }

            return data;

        } catch (Exception e) {
            // 记录具体的非法 JSON 以便调试
            log.debug("JSON 解析失败，原始 Lua 片段: {}\n生成的 JSON: {}", luaContent, finalJson);
            // 对于个别解析失败的英雄，不要抛出异常中断整个流程，返回 null 即可
            return null;
        }
    }

    private Double getDoubleOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val != null && val.isNumber()) {
            return val.asDouble();
        }
        return null;
    }

    public AramBalanceData getAramBalance(Integer championId) {
        return aramCache.getIfPresent(championId);
    }

    public Map<Integer, AramBalanceData> getAllAramBalance() {
        return new HashMap<>(aramCache.asMap());
    }

    public boolean hasData() {
        return aramCache.estimatedSize() > 0;
    }
}