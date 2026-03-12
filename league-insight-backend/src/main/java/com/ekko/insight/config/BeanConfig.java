package com.ekko.insight.config;

import com.ekko.insight.service.LcuHttpClient;
import com.ekko.insight.websocket.LcuWebSocketClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean 配置
 */
@Configuration
public class BeanConfig {

    /**
     * LCU HTTP 客户端
     */
    @Bean
    public LcuHttpClient lcuHttpClient() {
        return new LcuHttpClient();
    }

    /**
     * LCU WebSocket 客户端
     */
    @Bean
    public LcuWebSocketClient lcuWebSocketClient() {
        return new LcuWebSocketClient();
    }
}
