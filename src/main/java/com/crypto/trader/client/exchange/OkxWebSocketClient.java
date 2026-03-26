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
 * OKX WebSocket 公共频道客户端，用于实时订阅 K 线推送。
 *
 * <h3>OKX WebSocket 规范</h3>
 * <ul>
 *   <li>公共频道地址: wss://ws.okx.com:8443/ws/v5/public</li>
 *   <li>连接限制: 3 次/秒 (基于 IP)</li>
 *   <li>订阅/取消/登录总次数: 480 次/小时</li>
 *   <li>30 秒无消息需发送 "ping" 保持连接</li>
 * </ul>
 *
 * <h3>K 线频道订阅格式</h3>
 * <pre>
 * {"op":"subscribe","args":[{"channel":"candle1m","instId":"BTC-USDT"}]}
 * </pre>
 *
 * <h3>K 线推送数据格式</h3>
 * <pre>
 * {"arg":{"channel":"candle1m","instId":"BTC-USDT"},
 *  "data":[["ts","o","h","l","c","vol","volCcy","volCcyQuote","confirm"]]}
 * </pre>
 */
@Component
@Slf4j
public class OkxWebSocketClient {

    private static final String WS_PUBLIC_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final int PING_INTERVAL_SECONDS = 25;
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** symbol -> interval -> 已订阅 */
    private final ConcurrentHashMap<String, Boolean> subscriptions = new ConcurrentHashMap<>();

    /** K 线数据回调 */
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

    /**
     * 注册 K 线数据回调。每收到一条 K 线推送就调用一次。
     */
    public void onKline(Consumer<Kline> callback) {
        this.klineCallback = callback;
    }

    /**
     * 启动 WebSocket 连接并订阅 K 线频道。
     */
    @PostConstruct
    public void start() {
        running = true;
        connect();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pingTask != null) {
            pingTask.cancel(true);
        }
        scheduler.shutdownNow();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        log.info("[OkxWS] WebSocket client stopped");
    }

    /**
     * 建立 WebSocket 连接。
     */
    private void connect() {
        if (!running) return;

        log.info("[OkxWS] Connecting to {}", WS_PUBLIC_URL);

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_PUBLIC_URL), new WebSocket.Listener() {

                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        log.info("[OkxWS] Connected");
                        webSocket = ws;
                        reconnectAttempts = 0;
                        // 订阅所有交易对的 1m K 线
                        subscribeAll(ws);
                        // 启动心跳
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
                        log.warn("[OkxWS] Connection closed: code={} reason={}", statusCode, reason);
                        scheduleReconnect();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("[OkxWS] Error: {}", error.getMessage());
                        scheduleReconnect();
                    }
                })
                .exceptionally(ex -> {
                    log.error("[OkxWS] Failed to connect: {}", ex.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    /**
     * 订阅所有 watch-list 交易对的 K 线频道。
     */
    private void subscribeAll(WebSocket ws) {
        for (String symbol : watchList) {
            subscribe(ws, symbol, "1m");
        }
    }

    /**
     * 订阅单个交易对的 K 线频道。
     *
     * @param ws       WebSocket 连接
     * @param symbol   交易对，如 BTCUSDT
     * @param interval 周期，如 1m
     */
    private void subscribe(WebSocket ws, String symbol, String interval) {
        String instId = toOkxInstId(symbol);
        String channel = "candle" + toOkxBar(interval);
        String key = symbol + ":" + interval;

        if (subscriptions.containsKey(key)) {
            return;
        }

        try {
            String msg = objectMapper.writeValueAsString(Map.of(
                    "op", "subscribe",
                    "args", List.of(Map.of(
                            "channel", channel,
                            "instId", instId
                    ))
            ));
            ws.sendText(msg, true);
            subscriptions.put(key, true);
            log.info("[OkxWS] Subscribed: channel={} instId={}", channel, instId);
        } catch (Exception e) {
            log.error("[OkxWS] Failed to subscribe {} {}", symbol, interval, e);
        }
    }

    /**
     * 处理 WebSocket 接收到的消息。
     */
    private void handleMessage(String message) {
        // 心跳响应
        if ("pong".equals(message)) {
            log.debug("[OkxWS] Pong received");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(message);

            // 订阅确认
            if (root.has("event")) {
                String event = root.get("event").asText();
                if ("subscribe".equals(event)) {
                    log.debug("[OkxWS] Subscribe confirmed: {}", root.path("arg"));
                } else if ("error".equals(event)) {
                    log.error("[OkxWS] Subscribe error: code={} msg={}",
                            root.path("code").asText(), root.path("msg").asText());
                }
                return;
            }

            // K 线推送数据
            if (root.has("data") && root.has("arg")) {
                JsonNode arg = root.get("arg");
                String channel = arg.path("channel").asText();
                String instId = arg.path("instId").asText();

                // 从 channel 名提取 interval（如 candle1m -> 1m, candle1H -> 1h）
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

                    // confirm 字段: "0" 未完成, "1" 已完成
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
                            log.error("[OkxWS] Kline callback error for {}", symbol, e);
                        }
                    }

                    if (confirmed) {
                        log.debug("[OkxWS] Confirmed kline: {} {} ts={}", symbol, interval, ts);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[OkxWS] Failed to parse message: {}", message.substring(0, Math.min(200, message.length())), e);
        }
    }

    /**
     * 启动心跳定时器，每 25 秒发一次 "ping"（OKX 要求 30 秒内保活）。
     */
    private void startPing(WebSocket ws) {
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (ws != null && !ws.isInputClosed() && !ws.isOutputClosed()) {
                    ws.sendText("ping", true);
                    log.debug("[OkxWS] Ping sent");
                }
            } catch (Exception e) {
                log.warn("[OkxWS] Ping failed: {}", e.getMessage());
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 断线重连。指数退避，最多重试 MAX_RECONNECT_ATTEMPTS 次。
     */
    private void scheduleReconnect() {
        if (!running) return;
        subscriptions.clear();

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("[OkxWS] Max reconnect attempts ({}) reached, giving up", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        reconnectAttempts++;
        long delay = (long) RECONNECT_DELAY_SECONDS * reconnectAttempts;
        log.info("[OkxWS] Reconnecting in {} seconds (attempt {}/{})", delay, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /** candle1m -> 1m, candle1H -> 1h, candle1D -> 1d */
    private String parseIntervalFromChannel(String channel) {
        if (channel == null || !channel.startsWith("candle")) return "1m";
        String raw = channel.substring(6); // 去掉 "candle"
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
