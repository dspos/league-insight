package com.ekko.insight.controller;

import com.ekko.insight.model.AIAnalysisRequest;
import com.ekko.insight.model.AIAnalysisResult;
import com.ekko.insight.model.ApiResponse;
import com.ekko.insight.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI 分析控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiAnalysisService aiAnalysisService;

    /**
     * 分析对局详情
     * @param request 分析请求
     * @return 分析结果
     */
    @PostMapping("/analyze")
    public ApiResponse<AIAnalysisResult> analyzeGameDetail(@RequestBody AIAnalysisRequest request) {
        log.info("AI 分析请求: gameId={}, mode={}, participantId={}",
                request.getGameId(), request.getMode(), request.getParticipantId());

        String mode = request.getMode() != null ? request.getMode() : "overview";
        AIAnalysisResult result = aiAnalysisService.analyzeGameDetail(
                request.getGameId(),
                mode,
                request.getParticipantId()
        );

        return ApiResponse.success(result);
    }

    /**
     * 清除分析缓存
     */
    @DeleteMapping("/cache")
    public ApiResponse<Void> clearCache() {
        log.info("清除 AI 分析缓存");
        // 缓存会自动过期，这里可以添加手动清除逻辑
        return ApiResponse.success();
    }
}
