package com.ekko.insight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ekko.insight.model.AuthInfo;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * LCU WebSocket 客户端
 * 用于监听游戏客户端事件
 */
@Slf4j
public class LcuWebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Consumer<LcuEvent>> listeners = new CopyOnWriteArrayList<>();
    private WebSocketClient webSocketClient;
    private AuthInfo authInfo;
    private volatile boolean running = false;
    private Thread reconnectThread;

    /**
     * LCU 事件
     */
    public record LcuEvent(String uri, JsonNode data, String eventType) {}

    /**
     * 添加事件监听器
     */
    public void addListener(Consumer<LcuEvent> listener) {
        listeners.add(listener);
    }

    /**
     * 移除事件监听器
     */
    public void removeListener(Consumer<LcuEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * 连接到 LCU WebSocket
     */
    public synchronized void connect(AuthInfo authInfo) {
        if (running && this.authInfo != null &&
            this.authInfo.getPort().equals(authInfo.getPort())) {
            log.debug("WebSocket 已连接到相同端口，跳过重连");
            return;
        }

        this.authInfo = authInfo;
        this.running = true;

        // 关闭旧连接
        disconnect();

        // 启动连接线程
        reconnectThread = new Thread(() -> {
            while (running) {
                try {
                    doConnect();
                    break; // 连接成功，退出循环
                } catch (Exception e) {
                    log.error("WebSocket 连接失败: {}", e.getMessage());
                    if (running) {
                        try {
                            Thread.sleep(5000); // 5秒后重试
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, "lcu-websocket-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * 执行连接
     */
    private void doConnect() throws Exception {
        String wsUrl = authInfo.buildWsUrl();
        log.info("正在连接 LCU WebSocket: {}", wsUrl);

        // 创建信任所有证书的 SSL 上下文
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new java.security.SecureRandom());

        String authHeader = authInfo.toBasicAuth();

        webSocketClient = new WebSocketClient(URI.create(wsUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                log.info("LCU WebSocket 已连接");

                // 订阅 OnJsonApiEvent (opcode 5)
                String subscribeMessage = "[5,\"OnJsonApiEvent\"]";
                send(subscribeMessage);
                log.debug("已订阅 OnJsonApiEvent");
            }

            @Override
            public void onMessage(String message) {
                if (message == null || message.isEmpty()) {
                    return;
                }

                try {
                    JsonNode root = objectMapper.readTree(message);

                    // 检查是否是事件消息 (opcode 8)
                    if (root.isArray() && root.size() >= 3) {
                        int opcode = root.get(0).asInt();
                        String eventName = root.get(1).asText();

                        if (opcode == 8 && "OnJsonApiEvent".equals(eventName)) {
                            JsonNode eventData = root.get(2);
                            handleEvent(eventData);
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析 WebSocket 消息失败: {}", e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.info("LCU WebSocket 已关闭: code={}, reason={}, remote={}", code, reason, remote);

                // 如果仍在运行，尝试重连
                if (running) {
                    log.info("将在 5 秒后尝试重新连接...");
                    try {
                        Thread.sleep(5000);
                        if (running) {
                            doConnect();
                        }
                    } catch (Exception e) {
                        log.error("重连失败: {}", e.getMessage());
                    }
                }
            }

            @Override
            public void onError(Exception ex) {
                log.error("LCU WebSocket 错误: {}", ex.getMessage());
            }
        };

        // 设置 SSL
        webSocketClient.setSocketFactory(sslContext.getSocketFactory());

        // 添加认证头
        webSocketClient.addHeader("Authorization", authHeader);

        // 连接
        CountDownLatch connectLatch = new CountDownLatch(1);
        webSocketClient.connect();
    }

    /**
     * 处理 LCU 事件
     */
    private void handleEvent(JsonNode eventData) {
        try {
            String uri = eventData.has("uri") ? eventData.get("uri").asText() : null;
            String eventType = eventData.has("eventType") ? eventData.get("eventType").asText() : null;
            JsonNode data = eventData.has("data") ? eventData.get("data") : null;

            if (uri != null) {
                LcuEvent event = new LcuEvent(uri, data, eventType);

                // 过滤感兴趣的事件
                if (isInterestingEvent(uri)) {
                    log.debug("收到 LCU 事件: uri={}, eventType={}", uri, eventType);

                    // 通知所有监听器
                    for (Consumer<LcuEvent> listener : listeners) {
                        try {
                            listener.accept(event);
                        } catch (Exception e) {
                            log.error("事件处理器错误: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理事件失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否是感兴趣的事件
     */
    private boolean isInterestingEvent(String uri) {
        return uri != null && (
            uri.equals("/lol-gameflow/v1/gameflow-phase") ||
            uri.equals("/lol-champ-select/v1/session") ||
            uri.equals("/lol-lobby/v2/lobby") ||
            uri.equals("/lol-gameflow/v1/session")
        );
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        running = false;

        if (webSocketClient != null) {
            try {
                webSocketClient.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 时出错: {}", e.getMessage());
            }
            webSocketClient = null;
        }

        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }

        log.info("LCCU WebSocket 已断开");
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }
}
