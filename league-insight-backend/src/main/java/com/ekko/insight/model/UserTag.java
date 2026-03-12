package com.ekko.insight.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户标签模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTag {

    /**
     * 近期数据统计
     */
    private RecentData recentData;

    /**
     * 标签列表
     */
    @Builder.Default
    private List<RankTag> tag = new ArrayList<>();
}
