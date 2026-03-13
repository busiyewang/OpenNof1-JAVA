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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OKX 交易所客户端（类名保留 BinanceClient 以兼容现有注入配置）。
 *
 * <p>公开接口（K 线查询）无需签名；私有接口（下单、查询余额）需要 HMAC-SHA256 签名。</p>
 *
 * <p>OKX 签名规则：</p>
 * <pre>
 *   preHash  = timestamp + method + requestPath + body
 *   sign     = Base64( HmacSHA256( preHash, secretKey ) )
 *   timestamp = ISO-8601 UTC，例如 2024-01-01T00:00:00.000Z
 * </pre>
 */
@Service
@Slf4j
public class BinanceClient implements ExchangeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${crypto.exchange.binance.base-url}")
    private String baseUrl;

    @Value("${crypto.exchange.binance.api-key:}")
    private String apiKey;

    @Value("${crypto.exchange.binance.secret-key:}")
    private String secretKey;

    @Value("${crypto.exchange.binance.passphrase:}")
    private String passphrase;

    public BinanceClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    // -------------------------------------------------------------------------
    // 公开接口（无需签名）
    // -------------------------------------------------------------------------

    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        try {
            String instId = toOkxInstId(symbol);
            String bar = toOkxBar(interval);

            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/market/candles")
                            .queryParam("instId", instId)
                            .queryParam("bar", bar)
                            .queryParam("after", startTime)
                            .queryParam("before", endTime)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("OKX getKlines failed for {}:{}", symbol, interval);
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

                // OKX candles 格式：[ ts, o, h, l, c, vol, ... ]
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

            return result;
        } catch (Exception e) {
            log.error("Error fetching klines from OKX for {}:{}", symbol, interval, e);
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 私有接口（需要签名）
    // -------------------------------------------------------------------------

    /**
     * OKX 现货市价下单。
     *
     * <p>请求格式（Map）：</p>
     * <ul>
     *   <li>symbol      - 内部交易对，如 BTCUSDT</li>
     *   <li>side        - buy / sell</li>
     *   <li>type        - market（当前只支持市价单）</li>
     *   <li>quoteQuantity - BUY 时花费的 USDT 金额（买单用 tgtCcy=quote_ccy）</li>
     *   <li>quantity    - SELL 时卖出的基础货币数量</li>
     * </ul>
     *
     * @param request Map&lt;String, Object&gt; 下单参数
     * @return OKX 原始响应 Map；失败时抛出异常
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object placeOrder(Object request) {
        if (!(request instanceof Map)) {
            throw new IllegalArgumentException("request must be Map<String, Object>");
        }
        Map<String, Object> req = (Map<String, Object>) request;

        String symbol = String.valueOf(req.get("symbol"));
        String side   = String.valueOf(req.get("side"));   // buy / sell

        Map<String, Object> body = new HashMap<>();
        body.put("instId", toOkxInstId(symbol));
        body.put("tdMode", "cash");   // 现货交易
        body.put("side", side);
        body.put("ordType", "market");

        if ("buy".equalsIgnoreCase(side)) {
            // 买入：sz 为花费的 USDT；tgtCcy=quote_ccy 告知 OKX sz 单位为报价货币
            body.put("sz", String.valueOf(req.getOrDefault("quoteQuantity", "100")));
            body.put("tgtCcy", "quote_ccy");
        } else {
            // 卖出：sz 为卖出的基础货币数量
            body.put("sz", String.valueOf(req.getOrDefault("quantity", "0")));
        }

        try {
            String bodyJson = objectMapper.writeValueAsString(List.of(body));
            String timestamp = Instant.now().toString();
            String sign = sign(timestamp, "POST", "/api/v5/trade/batch-orders", bodyJson);

            Map<String, Object> response = webClient.post()
                    .uri("/api/v5/trade/batch-orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        h.add("OK-ACCESS-KEY", apiKey);
                        h.add("OK-ACCESS-SIGN", sign);
                        h.add("OK-ACCESS-TIMESTAMP", timestamp);
                        h.add("OK-ACCESS-PASSPHRASE", passphrase);
                    })
                    .bodyValue(bodyJson)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                throw new RuntimeException("OKX placeOrder failed: " + msg);
            }

            log.info("OKX placeOrder success: side={} symbol={}", side, symbol);
            // 返回第一条订单数据
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
     *
     * @return OKX 账户余额 Map
     */
    @Override
    public Object getAccountBalance() {
        try {
            String timestamp = Instant.now().toString();
            String requestPath = "/api/v5/account/balance";
            String sign = sign(timestamp, "GET", requestPath, "");

            Map<String, Object> response = webClient.get()
                    .uri(requestPath)
                    .headers(h -> {
                        h.add("OK-ACCESS-KEY", apiKey);
                        h.add("OK-ACCESS-SIGN", sign);
                        h.add("OK-ACCESS-TIMESTAMP", timestamp);
                        h.add("OK-ACCESS-PASSPHRASE", passphrase);
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                String msg = response != null ? String.valueOf(response.get("msg")) : "null response";
                throw new RuntimeException("OKX getAccountBalance failed: " + msg);
            }
            return response.get("data");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get account balance from OKX", e);
        }
    }

    // -------------------------------------------------------------------------
    // 签名工具
    // -------------------------------------------------------------------------

    /**
     * 计算 OKX REST API HMAC-SHA256 签名。
     *
     * @param timestamp   ISO-8601 时间戳（Instant.now().toString()）
     * @param method      HTTP 方法大写（GET / POST）
     * @param requestPath 路径（含查询参数，不含 host），例如 /api/v5/account/balance
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

    // -------------------------------------------------------------------------
    // 内部转换工具
    // -------------------------------------------------------------------------

    private String toOkxInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) return symbol;
        String base  = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote;
    }

    private String toOkxBar(String interval) {
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
