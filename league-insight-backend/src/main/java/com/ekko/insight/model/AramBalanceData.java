package com.ekko.insight.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ARAM 平衡性数据
 * 来源：Fandom Wiki
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AramBalanceData {

    /**
     * 英雄 ID
     */
    private Integer championId;

    /**
     * 英雄名称
     */
    private String championName;

    /**
     * 伤害输出倍率
     */
    @JsonProperty("dmg_dealt")
    private Double dmgDealt;

    /**
     * 承受伤害倍率
     */
    @JsonProperty("dmg_taken")
    private Double dmgTaken;

    /**
     * 治疗倍率
     */
    private Double healing;

    /**
     * 护盾倍率
     */
    private Double shielding;

    /**
     * 技能急速加成
     */
    @JsonProperty("ability_haste")
    private Double abilityHaste;

    /**
     * 法力回复加成
     */
    @JsonProperty("mana_regen")
    private Double manaRegen;

    /**
     * 能量回复加成
     */
    @JsonProperty("energy_regen")
    private Double energyRegen;

    /**
     * 攻击速度加成
     */
    @JsonProperty("attack_speed")
    private Double attackSpeed;

    /**
     * 移动速度加成
     */
    @JsonProperty("movement_speed")
    private Double movementSpeed;

    /**
     * 韧性加成
     */
    private Double tenacity;

    /**
     * 判断是否有增益
     */
    public boolean hasBuff() {
        return (dmgDealt != null && dmgDealt > 1.0) ||
               (dmgTaken != null && dmgTaken < 1.0) ||
               (healing != null && healing > 1.0) ||
               (shielding != null && shielding > 1.0);
    }

    /**
     * 判断是否有削弱
     */
    public boolean hasNerf() {
        return (dmgDealt != null && dmgDealt < 1.0) ||
               (dmgTaken != null && dmgTaken > 1.0) ||
               (healing != null && healing < 1.0) ||
               (shielding != null && shielding < 1.0);
    }

    /**
     * 获取简要描述
     */
    public String getBrief() {
        StringBuilder sb = new StringBuilder();
        if (dmgDealt != null && dmgDealt != 1.0) {
            sb.append(String.format("伤害%.0f%% ", dmgDealt * 100));
        }
        if (dmgTaken != null && dmgTaken != 1.0) {
            sb.append(String.format("承伤%.0f%% ", dmgTaken * 100));
        }
        return sb.toString().trim();
    }
}
