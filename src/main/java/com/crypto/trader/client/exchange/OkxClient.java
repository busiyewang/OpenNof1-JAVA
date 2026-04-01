package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * OKX 交易所 REST API 客户端（仅公开接口：K 线数据获取）。
 */
@Service
@Slf4j
public class OkxClient implements ExchangeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Value("${crypto.exchange.okx.base-url}")
    private String baseUrl;

    @Value("${crypto.exchange.okx.simulated:false}")
    private boolean simulated;

    public OkxClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        log.info("[OKX REST] 初始化完成: baseUrl={}, simulated={}", baseUrl, simulated);
    }

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
