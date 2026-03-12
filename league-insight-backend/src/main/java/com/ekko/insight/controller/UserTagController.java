package com.ekko.insight.controller;

import com.ekko.insight.model.ApiResponse;
import com.ekko.insight.model.UserTag;
import com.ekko.insight.service.UserTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户标签控制器
 */
@RestController
@RequestMapping("/api/v1/user-tag")
@RequiredArgsConstructor
public class UserTagController {

    private final UserTagService userTagService;

    /**
     * 根据名称获取用户标签
     */
    @GetMapping("/name/{name}")
    public ApiResponse<UserTag> getUserTagByName(
            @PathVariable String name,
            @RequestParam(defaultValue = "0") Integer mode) {
        return ApiResponse.success(userTagService.getUserTagByName(name, mode));
    }

    /**
     * 根据 PUUID 获取用户标签
     */
    @GetMapping("/puuid/{puuid}")
    public ApiResponse<UserTag> getUserTagByPuuid(
            @PathVariable String puuid,
            @RequestParam(defaultValue = "0") Integer mode) {
        return ApiResponse.success(userTagService.getUserTagByPuuid(puuid, mode));
    }
}
