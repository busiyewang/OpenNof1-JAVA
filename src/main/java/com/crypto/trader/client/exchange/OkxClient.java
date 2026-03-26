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
 * OKX 交易所客户端。
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
public class OkxClient implements ExchangeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Override
    @SuppressWarnings("unchecked")
    public Object placeOrder(Object request) {
        if (!(request instanceof Map)) {
            throw new IllegalArgumentException("request must be Map<String, Object>");
        }
        Map<String, Object> req = (Map<String, Object>) request;

        String symbol = String.valueOf(req.get("symbol"));
        String side   = String.valueOf(req.get("side"));

        Map<String, Object> body = new HashMap<>();
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
