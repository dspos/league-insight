package com.ekko.insight.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 对局记录模型
 * 对应 LCU API: lol-match-history/v1/products/lol/{puuid}/matches
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchHistory {

    @JsonProperty("gameId")
    private Long gameId;

    @JsonProperty("gameMode")
    private String gameMode;

    @JsonProperty("gameType")
    private String gameType;

    @JsonProperty("queueId")
    private Integer queueId;

    @JsonProperty("queueName")
    private String queueName; // 中文游戏模式名称

    @JsonProperty("gameDuration")
    private Integer gameDuration;

    @JsonProperty("gameCreation")
    private Long gameCreation;

    @JsonProperty("gameCreationDate")
    private String gameCreationDate;

    @JsonProperty("platformId")
    private String platformId;

    @JsonProperty("mapId")
    private Integer mapId;

    @JsonProperty("participants")
    private List<Participant> participants;

    @JsonProperty("participantIdentities")
    private List<ParticipantIdentity> participantIdentities;

    /**
     * 参与者游戏数据
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Participant {
        @JsonProperty("participantId")
        private Integer participantId;

        @JsonProperty("teamId")
        private Integer teamId;

        @JsonProperty("championId")
        private Integer championId;

        @JsonProperty("spell1Id")
        private Integer spell1Id;

        @JsonProperty("spell2Id")
        private Integer spell2Id;

        @JsonProperty("stats")
        private Stats stats;
    }

    /**
     * 参与者统计数据
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        @JsonProperty("win")
        private Boolean win;

        @JsonProperty("kills")
        private Integer kills;

        @JsonProperty("deaths")
        private Integer deaths;

        @JsonProperty("assists")
        private Integer assists;

        @JsonProperty("goldEarned")
        private Integer goldEarned;

        @JsonProperty("totalMinionsKilled")
        private Integer totalMinionsKilled;

        @JsonProperty("neutralMinionsKilled")
        private Integer neutralMinionsKilled;

        @JsonProperty("totalDamageDealtToChampions")
        private Integer totalDamageDealtToChampions;

        @JsonProperty("totalDamageTaken")
        private Integer totalDamageTaken;

        @JsonProperty("totalHeal")
        private Integer totalHeal;

        @JsonProperty("item0")
        private Integer item0;

        @JsonProperty("item1")
        private Integer item1;

        @JsonProperty("item2")
        private Integer item2;

        @JsonProperty("item3")
        private Integer item3;

        @JsonProperty("item4")
        private Integer item4;

        @JsonProperty("item5")
        private Integer item5;

        @JsonProperty("item6")
        private Integer item6;
    }

    /**
     * 参与者身份信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParticipantIdentity {
        @JsonProperty("participantId")
        private Integer participantId;

        @JsonProperty("player")
        private Player player;
    }

    /**
     * 玩家信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Player {
        @JsonProperty("accountId")
        private Long accountId;

        @JsonProperty("summonerId")
        private Long summonerId;

        @JsonProperty("summonerName")
        private String summonerName;

        @JsonProperty("gameName")
        private String gameName;

        @JsonProperty("tagLine")
        private String tagLine;

        @JsonProperty("puuid")
        private String puuid;

        @JsonProperty("platformId")
        private String platformId;
    }
}
