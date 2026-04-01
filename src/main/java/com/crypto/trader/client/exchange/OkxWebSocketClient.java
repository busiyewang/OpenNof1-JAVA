package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * OKX WebSocket Business 频道客户端，用于实时订阅 K 线推送。
 *
 * <h3>OKX WebSocket 规范</h3>
 * <ul>
 *   <li>Business 频道: wss://ws.okx.com:8443/ws/v5/business（K 线在此频道）</li>
 *   <li>模拟盘: wss://wspap.okx.com:8443/ws/v5/business</li>
 *   <li>连接限制: 3 次/秒 (基于 IP)</li>
 *   <li>订阅/取消/登录总次数: 480 次/小时</li>
 *   <li>30 秒无消息自动断开，需 ping 保活</li>
 * </ul>
 *
 * <h3>K 线订阅格式（OKX 官方文档）</h3>
 * <pre>
 * {"op":"subscribe","args":[{"channel":"candle1m","instId":"BTC-USDT"}]}
 * </pre>
 *
 * <h3>K 线推送数据格式</h3>
 * <pre>
 * {"arg":{"channel":"candle1m","instId":"BTC-USDT"},
 *  "data":[["ts","o","h","l","c","vol","volCcy","volCcyQuote","confirm"]]}
 * </pre>
 *
 * @see <a href="https://www.okx.com/docs-v5/zh/#overview-websockets">OKX WebSocket 文档</a>
 */
@Component
@Slf4j
public class OkxWebSocketClient {

    /** 实盘 Business WebSocket */
    private static final String WS_BUSINESS_URL = "wss://ws.okx.com:8443/ws/v5/business";
    /** 模拟盘 Business WebSocket */
    private static final String WS_BUSINESS_URL_DEMO = "wss://wspap.okx.com:8443/ws/v5/business";

    private static final int PING_INTERVAL_SECONDS = 25;
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_ATTEMPTS = 20;
    private static final int CONNECT_TIMEOUT_SECONDS = 15;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    private final ConcurrentHashMap<String, Boolean> subscriptions = new ConcurrentHashMap<>();
    private volatile Consumer<Kline> klineCallback;
    private volatile WebSocket webSocket;
    private volatile boolean running = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "okx-ws-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pingTask;
    private int reconnectAttempts = 0;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    @Value("${crypto.exchange.okx.simulated:false}")
    private boolean simulated;

    @Value("${crypto.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${crypto.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${crypto.proxy.port:7890}")
    private int proxyPort;

    public void onKline(Consumer<Kline> callback) {
        this.klineCallback = callback;
    }

    @PostConstruct
    public void start() {
        // 构建 HttpClient（带代理）
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));

        if (proxyEnabled) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            log.info("[OkxWS] HTTP 代理已启用: {}:{}", proxyHost, proxyPort);
        }

        this.httpClient = builder.build();
        running = true;
        connect();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pingTask != null) pingTask.cancel(true);
        scheduler.shutdownNow();
        if (webSocket != null) {
            try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
        }
        log.info("[OkxWS] WebSocket 客户端已停止");
    }

    private void connect() {
        if (!running) return;

        String wsUrl = simulated ? WS_BUSINESS_URL_DEMO : WS_BUSINESS_URL;
        log.info("[OkxWS] 正在连接: {} (simulated={})", wsUrl, simulated);

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {

                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        log.info("[OkxWS] 连接成功: {}", wsUrl);
                        webSocket = ws;
                        reconnectAttempts = 0;
                        subscribeAll(ws);
                        startPing(ws);
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String message = buffer.toString();
                            buffer.setLength(0);
                            handleMessage(message);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.warn("[OkxWS] 连接关闭: code={} reason={}", statusCode, reason);
                        scheduleReconnect();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("[OkxWS] 连接错误: {}", error.getMessage(), error);
                        scheduleReconnect();
                    }
                })
                .exceptionally(ex -> {
                    log.error("[OkxWS] 连接失败: {}", ex.getMessage(), ex);
                    scheduleReconnect();
                    return null;
                });
    }

    @Value("${crypto.analysis.timeframes:1h,4h,1d}")
    private List<String> analysisTimeframes;

    private void subscribeAll(WebSocket ws) {
        for (String symbol : watchList) {
            subscribe(ws, symbol, "1m");
            for (String tf : analysisTimeframes) {
                subscribe(ws, symbol, tf);
            }
        }
    }

    /**
     * 订阅 K 线频道。
     *
     * <p>OKX K 线频道格式：channel 为 "candle" + bar（如 candle1m, candle1H）</p>
     * <pre>
     * {"op":"subscribe","args":[{"channel":"candle1m","instId":"BTC-USDT"}]}
     * </pre>
     */
    private void subscribe(WebSocket ws, String symbol, String interval) {
        String instId = toOkxInstId(symbol);
        String channel = "candle" + toOkxBar(interval);  // candle1m, candle1H, candle1D...
        String key = symbol + ":" + interval;

        if (subscriptions.containsKey(key)) return;

        try {
            // OKX 官方文档格式: channel 直接是 "candle1m" 等
            String msg = objectMapper.writeValueAsString(Map.of(
                    "op", "subscribe",
                    "args", List.of(Map.of(
                            "channel", channel,
                            "instId", instId
                    ))
            ));

            log.info("[OkxWS] 发送订阅: {}", msg);
            ws.sendText(msg, true);
            subscriptions.put(key, true);
        } catch (Exception e) {
            log.error("[OkxWS] 订阅失败: {} {} error={}", symbol, interval, e.getMessage(), e);
        }
    }

    private void handleMessage(String message) {
        if ("pong".equals(message)) {
            log.debug("[OkxWS] Pong 收到");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(message);

            // 事件响应（订阅确认 / 错误）
            if (root.has("event")) {
                String event = root.get("event").asText();
                if ("subscribe".equals(event)) {
                    log.info("[OkxWS] 订阅确认: {}", root.path("arg"));
                } else if ("error".equals(event)) {
                    log.error("[OkxWS] 订阅错误: code={} msg={} connId={}",
                            root.path("code").asText(),
                            root.path("msg").asText(),
                            root.path("connId").asText());
                }
                return;
            }

            // K 线推送数据
            if (root.has("data") && root.has("arg")) {
                JsonNode arg = root.get("arg");
                String channel = arg.path("channel").asText();
                String instId = arg.path("instId").asText();

                String interval = parseIntervalFromChannel(channel);
                String symbol = fromOkxInstId(instId);

                JsonNode dataArr = root.get("data");
                for (JsonNode item : dataArr) {
                    if (!item.isArray() || item.size() < 6) continue;

                    long ts       = item.get(0).asLong();
                    BigDecimal o   = new BigDecimal(item.get(1).asText());
                    BigDecimal h   = new BigDecimal(item.get(2).asText());
                    BigDecimal l   = new BigDecimal(item.get(3).asText());
                    BigDecimal c   = new BigDecimal(item.get(4).asText());
                    BigDecimal vol = new BigDecimal(item.get(5).asText());

                    boolean confirmed = item.size() > 8 && "1".equals(item.get(8).asText());

                    Kline kline = new Kline();
                    kline.setSymbol(symbol);
                    kline.setInterval(interval);
                    kline.setTimestamp(Instant.ofEpochMilli(ts));
                    kline.setOpen(o);
                    kline.setHigh(h);
                    kline.setLow(l);
                    kline.setClose(c);
                    kline.setVolume(vol);

                    if (klineCallback != null) {
                        try {
                            klineCallback.accept(kline);
                        } catch (Exception e) {
                            log.error("[OkxWS] K线回调异常: {} error={}", symbol, e.getMessage(), e);
                        }
                    }

                    if (confirmed) {
                        log.info("[OkxWS] K线已确认: {} {} ts={} close={}", symbol, interval, ts, c);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[OkxWS] 消息解析失败: {}", message.substring(0, Math.min(200, message.length())), e);
        }
    }

    private void startPing(WebSocket ws) {
        if (pingTask != null) pingTask.cancel(false);
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (ws != null && !ws.isInputClosed() && !ws.isOutputClosed()) {
                    ws.sendText("ping", true);
                    log.debug("[OkxWS] Ping 已发送");
                }
            } catch (Exception e) {
                log.warn("[OkxWS] Ping 发送失败: {}", e.getMessage());
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleReconnect() {
        if (!running) return;
        subscriptions.clear();

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("[OkxWS] 已达最大重连次数 ({})，停止重连", MAX_RECONNECT_ATTEMPTS);
            // 重置计数器，10 分钟后再尝试
            scheduler.schedule(() -> {
                reconnectAttempts = 0;
                connect();
            }, 10, TimeUnit.MINUTES);
            return;
        }

        reconnectAttempts++;
        long delay = Math.min((long) RECONNECT_DELAY_SECONDS * reconnectAttempts, 60);
        log.info("[OkxWS] {} 秒后重连 (第 {}/{} 次)", delay, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /** candle1m -> 1m, candle1H -> 1h, candle1D -> 1d */
    private String parseIntervalFromChannel(String channel) {
        if (channel == null || !channel.startsWith("candle")) return "1m";
        String raw = channel.substring(6);
        return raw.toLowerCase();
    }

    /** BTC-USDT -> BTCUSDT */
    private String fromOkxInstId(String instId) {
        if (instId == null) return null;
        return instId.replace("-", "");
    }

    /** BTCUSDT -> BTC-USDT */
    private String toOkxInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) return symbol;
        String base  = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote;
    }

    /** 内部周期 -> OKX bar */
    private String toOkxBar(String interval) {
        if (interval == null) return "1m";
        return switch (interval) {
            case "1m", "3m", "5m", "15m", "30m" -> interval;
            case "1h"  -> "1H";
            case "2h"  -> "2H";
            case "4h"  -> "4H";
            case "1d"  -> "1D";
            default    -> "1m";
        };
    }
}
