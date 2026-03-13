package com.ekko.insight.exception;

import com.ekko.insight.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 LCU 异常
     */
    @ExceptionHandler(LcuException.class)
    public ApiResponse<Void> handleLcuException(LcuException e) {
        log.error("LCU 异常: {}", e.getMessage());
        return ApiResponse.error(503, "LCU Error: " + e.getMessage());
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("服务器异常: ", e);
        return ApiResponse.error(500, "服务器内部错误: " + e.getMessage());
    }
}
