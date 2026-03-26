package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * OKX 交易所 REST API 客户端。
 *
 * <h3>签名规则（私有接口）</h3>
 * <pre>
 *   preHash  = timestamp + method + requestPath + body
 *   sign     = Base64( HmacSHA256( preHash, SecretKey ) )
 * </pre>
 * <ul>
 *   <li>timestamp: ISO-8601 UTC 含毫秒，如 {@code 2020-12-08T09:08:57.715Z}</li>
 *   <li>method: 大写 GET / POST</li>
 *   <li>requestPath: 含查询参数的路径，如 {@code /api/v5/account/balance?ccy=BTC}</li>
 *   <li>body: POST 请求的 JSON 字符串；GET 请求无 body</li>
 * </ul>
 *
 * <h3>请求头（私有接口）</h3>
 * <ul>
 *   <li>OK-ACCESS-KEY: APIKey</li>
 *   <li>OK-ACCESS-SIGN: 签名</li>
 *   <li>OK-ACCESS-TIMESTAMP: ISO 时间戳</li>
 *   <li>OK-ACCESS-PASSPHRASE: 创建 API 时设置的 Passphrase</li>
 *   <li>Content-Type: application/json</li>
 * </ul>
 */
@Service
@Slf4j
public class OkxClient implements ExchangeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OKX 要求的 ISO-8601 UTC 时间戳格式，含毫秒 */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Value("${crypto.exchange.okx.base-url}")
    private String baseUrl;

    @Value("${crypto.exchange.okx.api-key:}")
    private String apiKey;

    @Value("${crypto.exchange.okx.secret-key:}")
    private String secretKey;

    @Value("${crypto.exchange.okx.passphrase:}")
    private String passphrase;

    public OkxClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    // =========================================================================
    // 公开接口（无需签名）
    // =========================================================================

    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        try {
            String instId = toOkxInstId(symbol);
            String bar = toOkxBar(interval);

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
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("OKX getKlines failed for {}:{} response={}", symbol, interval, response);
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

                // OKX candles: [ ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm ]
                long ts = Long.parseLong(String.valueOf(arr.get(0)));
                BigDecimal open   = new BigDecimal(String.valueOf(arr.get(1)));
                BigDecimal high   = new BigDecimal(String.valueOf(arr.get(2)));
                BigDecimal low    = new BigDecimal(String.valueOf(arr.get(3)));
                BigDecimal close  = new BigDecimal(String.valueOf(arr.get(4)));
                BigDecimal volume = new BigDecimal(String.valueOf(arr.get(5)));

                Kline k = new Kline();
                k.setSymbol(symbol);
                k.setInterval(interval);
                k.setTimestamp(Instant.ofEpochMilli(ts));
                k.setOpen(open);
                k.setHigh(high);
                k.setLow(low);
                k.setClose(close);
                k.setVolume(volume);
                result.add(k);
            }

            log.debug("OKX getKlines {}:{} returned {} candles", symbol, interval, result.size());
            return result;
        } catch (Exception e) {
            log.error("Error fetching klines from OKX for {}:{}", symbol, interval, e);
            return List.of();
        }
    }

    // =========================================================================
    // 私有接口（需要签名）
    // =========================================================================

    /**
     * OKX 现货市价下单（POST /api/v5/trade/order）。
     *
     * @param request Map 包含 symbol, side, type, quoteQuantity/quantity
     * @return OKX 原始响应 data 对象
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object placeOrder(Object request) {
        if (!(request instanceof Map)) {
            throw new IllegalArgumentException("request must be Map<String, Object>");
        }
        Map<String, Object> req = (Map<String, Object>) request;

        String symbol = String.valueOf(req.get("symbol"));
        String side   = String.valueOf(req.get("side"));

        // 构建 OKX 下单请求体
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

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri(requestPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> setPrivateHeaders(h, apiKey, sign, timestamp, passphrase))
                    .bodyValue(bodyJson)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                String code = response != null ? String.valueOf(response.get("code")) : "null";
                throw new RuntimeException("OKX placeOrder failed: code=" + code + " msg=" + msg);
            }

            log.info("OKX placeOrder success: side={} symbol={}", side, symbol);
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

    /**
     * 查询 OKX 账户余额（GET /api/v5/account/balance）。
     */
    @Override
    public Object getAccountBalance() {
        return getAccountBalance(null);
    }

    /**
     * 查询 OKX 账户余额，可指定币种。
     *
     * @param ccy 币种，如 "BTC" 或 "USDT"；null 查全部
     */
    public Object getAccountBalance(String ccy) {
        String requestPath = "/api/v5/account/balance";
        // GET 请求查询参数是 requestPath 的一部分（用于签名）
        if (ccy != null && !ccy.isEmpty()) {
            requestPath += "?ccy=" + ccy;
        }

        try {
            String timestamp = generateTimestamp();
            // GET 请求：签名包含完整 requestPath（含查询参数），body 为空
            String sign = sign(timestamp, "GET", requestPath, "");

            final String finalPath = requestPath;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(finalPath)
                    .headers(h -> setPrivateHeaders(h, apiKey, sign, timestamp, passphrase))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                String code = response != null ? String.valueOf(response.get("code")) : "null";
                throw new RuntimeException("OKX getAccountBalance failed: code=" + code + " msg=" + msg);
            }

            return response.get("data");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get account balance from OKX", e);
        }
    }

    /**
     * 查询订单详情（GET /api/v5/trade/order）。
     *
     * @param instId 产品 ID，如 BTC-USDT
     * @param ordId  订单 ID
     * @return 订单信息
     */
    public Object getOrderDetail(String instId, String ordId) {
        String requestPath = "/api/v5/trade/order?instId=" + instId + "&ordId=" + ordId;

        try {
            String timestamp = generateTimestamp();
            String sign = sign(timestamp, "GET", requestPath, "");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(requestPath)
                    .headers(h -> setPrivateHeaders(h, apiKey, sign, timestamp, passphrase))
                    .retrieve()
                    .bodyToMono(Map.class)
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

    /**
     * 生成 OKX 要求的 ISO-8601 UTC 时间戳（含毫秒）。
     * 示例: {@code 2020-12-08T09:08:57.715Z}
     */
    private String generateTimestamp() {
        return TIMESTAMP_FMT.format(Instant.now());
    }

    /**
     * 计算 OKX REST API HMAC-SHA256 签名。
     *
     * <pre>sign = Base64( HmacSHA256( timestamp + method + requestPath + body, SecretKey ) )</pre>
     *
     * @param timestamp   ISO-8601 时间戳
     * @param method      HTTP 方法大写：GET / POST
     * @param requestPath 请求路径（GET 请求含查询参数，如 /api/v5/account/balance?ccy=BTC）
     * @param body        POST body JSON 字符串；GET 请求传空字符串
     * @return Base64 编码的签名字符串
     */
    private String sign(String timestamp, String method, String requestPath, String body) throws Exception {
        String preHash = timestamp + method + requestPath + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(preHash.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 设置 OKX 私有接口请求头。
     */
    private void setPrivateHeaders(org.springframework.http.HttpHeaders headers,
                                   String key, String sign, String timestamp, String pass) {
        headers.set("OK-ACCESS-KEY", key);
        headers.set("OK-ACCESS-SIGN", sign);
        headers.set("OK-ACCESS-TIMESTAMP", timestamp);
        headers.set("OK-ACCESS-PASSPHRASE", pass);
        headers.set("Content-Type", "application/json");
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

    /** 内部周期标识 -> OKX bar 参数 */
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
