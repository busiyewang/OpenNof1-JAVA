package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * OKX 交易所 REST API 客户端。
 *
 * <h3>签名规则</h3>
 * <pre>
 *   preHash = timestamp + method + requestPath + body
 *   sign    = Base64( HmacSHA256( preHash, SecretKey ) )
 * </pre>
 *
 * <h3>必需请求头</h3>
 * OK-ACCESS-KEY / OK-ACCESS-SIGN / OK-ACCESS-TIMESTAMP / OK-ACCESS-PASSPHRASE
 * 模拟盘额外: x-simulated-trading: 1
 *
 * @see <a href="https://www.okx.com/docs-v5/zh/#overview-rest-authentication-signature">OKX 签名文档</a>
 */
@Service
@Slf4j
public class OkxClient implements ExchangeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OKX ISO-8601 UTC 时间戳格式（含毫秒）：2020-12-08T09:08:57.715Z */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 单次请求超时 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Value("${crypto.exchange.okx.base-url}")
    private String baseUrl;

    @Value("${crypto.exchange.okx.api-key:}")
    private String apiKey;

    @Value("${crypto.exchange.okx.secret-key:}")
    private String secretKey;

    @Value("${crypto.exchange.okx.passphrase:}")
    private String passphrase;

    /** 是否为模拟盘 */
    @Value("${crypto.exchange.okx.simulated:false}")
    private boolean simulated;

    public OkxClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        log.info("[OKX REST] 初始化完成: baseUrl={}, simulated={}, apiKey={}...{}",
                baseUrl, simulated,
                apiKey.length() > 8 ? apiKey.substring(0, 8) : "***",
                apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "***");
    }

    // =========================================================================
    // 公开接口（无需签名）
    // =========================================================================

    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        String instId = toOkxInstId(symbol);
        String bar = toOkxBar(interval);

        log.info("[OKX REST] 获取K线: instId={}, bar={}, after={}, before={}", instId, bar, startTime, endTime);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/market/candles")
                            .queryParam("instId", instId)
                            .queryParam("bar", bar)
                            .queryParam("after", String.valueOf(startTime))
                            .queryParam("before", String.valueOf(endTime))
                            .build())
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT)
                    .doOnError(WebClientRequestException.class, e ->
                            log.error("[OKX REST] K线请求连接失败: {}", e.getMessage()))
                    .doOnError(WebClientResponseException.class, e ->
                            log.error("[OKX REST] K线请求HTTP错误: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString()))
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("[OKX REST] K线响应异常: {}:{} response={}", symbol, interval, response);
                return List.of();
            }

            Object dataObj = response.get("data");
            if (!(dataObj instanceof List)) {
                return List.of();
            }

            List<?> data = (List<?>) dataObj;
            List<Kline> result = new ArrayList<>(data.size());

            for (Object item : data) {
                if (!(item instanceof List)) continue;
                List<?> arr = (List<?>) item;
                if (arr.size() < 6) continue;

                long ts = Long.parseLong(String.valueOf(arr.get(0)));
                Kline k = new Kline();
                k.setSymbol(symbol);
                k.setInterval(interval);
                k.setTimestamp(Instant.ofEpochMilli(ts));
                k.setOpen(new BigDecimal(String.valueOf(arr.get(1))));
                k.setHigh(new BigDecimal(String.valueOf(arr.get(2))));
                k.setLow(new BigDecimal(String.valueOf(arr.get(3))));
                k.setClose(new BigDecimal(String.valueOf(arr.get(4))));
                k.setVolume(new BigDecimal(String.valueOf(arr.get(5))));
                result.add(k);
            }

            log.info("[OKX REST] K线获取成功: {}:{} 返回 {} 条", symbol, interval, result.size());
            return result;
        } catch (Exception e) {
            log.error("[OKX REST] K线获取异常: {}:{} error={}", symbol, interval, e.getMessage(), e);
            return List.of();
        }
    }

    // =========================================================================
    // 私有接口（需要签名）
    // =========================================================================

    @Override
    @SuppressWarnings("unchecked")
    public Object placeOrder(Object request) {
        if (!(request instanceof Map)) {
            throw new IllegalArgumentException("request must be Map<String, Object>");
        }
        Map<String, Object> req = (Map<String, Object>) request;

        String symbol = String.valueOf(req.get("symbol"));
        String side   = String.valueOf(req.get("side"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", toOkxInstId(symbol));
        body.put("tdMode", "cash");
        body.put("side", side);
        body.put("ordType", "market");

        if ("buy".equalsIgnoreCase(side)) {
            body.put("sz", String.valueOf(req.getOrDefault("quoteQuantity", "100")));
            body.put("tgtCcy", "quote_ccy");
        } else {
            body.put("sz", String.valueOf(req.getOrDefault("quantity", "0")));
        }

        String requestPath = "/api/v5/trade/order";

        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            String timestamp = generateTimestamp();
            String sign = sign(timestamp, "POST", requestPath, bodyJson);

            log.info("[OKX REST] 下单请求: {} {} body={}", side.toUpperCase(), symbol, bodyJson);

            Map<String, Object> response = webClient.post()
                    .uri(requestPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> setPrivateHeaders(h, sign, timestamp))
                    .bodyValue(bodyJson)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                String code = response != null ? String.valueOf(response.get("code")) : "null";
                log.error("[OKX REST] 下单失败: code={} msg={}", code, msg);
                throw new RuntimeException("OKX placeOrder failed: code=" + code + " msg=" + msg);
            }

            log.info("[OKX REST] 下单成功: {} {}", side, symbol);
            Object data = response.get("data");
            if (data instanceof List && !((List<?>) data).isEmpty()) {
                return ((List<?>) data).get(0);
            }
            return response;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to place order on OKX", e);
        }
    }

    @Override
    public Object getAccountBalance() {
        return getAccountBalance(null);
    }

    public Object getAccountBalance(String ccy) {
        String requestPath = "/api/v5/account/balance";
        if (ccy != null && !ccy.isEmpty()) {
            requestPath += "?ccy=" + ccy;
        }

        try {
            String timestamp = generateTimestamp();
            String sign = sign(timestamp, "GET", requestPath, "");

            log.info("[OKX REST] 查询余额: path={}", requestPath);

            final String finalPath = requestPath;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(finalPath)
                    .headers(h -> setPrivateHeaders(h, sign, timestamp))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                String code = response != null ? String.valueOf(response.get("code")) : "null";
                log.error("[OKX REST] 查询余额失败: code={} msg={}", code, msg);
                throw new RuntimeException("OKX getAccountBalance failed: code=" + code + " msg=" + msg);
            }

            log.info("[OKX REST] 查询余额成功");
            return response.get("data");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get account balance from OKX", e);
        }
    }

    public Object getOrderDetail(String instId, String ordId) {
        String requestPath = "/api/v5/trade/order?instId=" + instId + "&ordId=" + ordId;

        try {
            String timestamp = generateTimestamp();
            String sign = sign(timestamp, "GET", requestPath, "");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(requestPath)
                    .headers(h -> setPrivateHeaders(h, sign, timestamp))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                throw new RuntimeException("OKX getOrderDetail failed: " + msg);
            }

            Object data = response.get("data");
            if (data instanceof List && !((List<?>) data).isEmpty()) {
                return ((List<?>) data).get(0);
            }
            return data;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get order detail from OKX", e);
        }
    }

    // =========================================================================
    // 签名 & 请求头
    // =========================================================================

    private String generateTimestamp() {
        return TIMESTAMP_FMT.format(Instant.now());
    }

    /**
     * sign = Base64( HmacSHA256( timestamp + method + requestPath + body, SecretKey ) )
     */
    private String sign(String timestamp, String method, String requestPath, String body) throws Exception {
        String preHash = timestamp + method + requestPath + body;
        log.debug("[OKX REST] 签名 preHash: {}{}{}<body...>", timestamp, method, requestPath);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(preHash.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 设置 OKX 私有接口必需的请求头。
     * 模拟盘时额外添加 x-simulated-trading: 1
     */
    private void setPrivateHeaders(org.springframework.http.HttpHeaders headers,
                                   String sign, String timestamp) {
        headers.set("OK-ACCESS-KEY", apiKey);
        headers.set("OK-ACCESS-SIGN", sign);
        headers.set("OK-ACCESS-TIMESTAMP", timestamp);
        headers.set("OK-ACCESS-PASSPHRASE", passphrase);
        headers.set("Content-Type", "application/json");
        if (simulated) {
            headers.set("x-simulated-trading", "1");
        }
    }

    // =========================================================================
    // 内部转换工具
    // =========================================================================

    /** BTCUSDT -> BTC-USDT */
    String toOkxInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) return symbol;
        String base  = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote;
    }

    /** 内部周期 -> OKX bar 参数 */
    String toOkxBar(String interval) {
        if (interval == null) return "1m";
        return switch (interval) {
            case "1m", "3m", "5m", "15m", "30m" -> interval;
            case "1h"  -> "1H";
            case "2h"  -> "2H";
            case "4h"  -> "4H";
            case "6h"  -> "6H";
            case "12h" -> "12H";
            case "1d"  -> "1D";
            case "1w"  -> "1W";
            case "1M"  -> "1M";
            default    -> "1m";
        };
    }
}
