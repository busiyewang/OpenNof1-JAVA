package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.crypto.trader.util.OkxSymbolUtils;
import jakarta.annotation.PostConstruct;
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

    /**
     * 获取指定时间范围内的 K 线数据。
     *
     * <p>OKX API 参数含义（注意与直觉相反）：</p>
     * <ul>
     *   <li>{@code after}: 返回时间戳<b>早于</b>此值的数据（向过去翻页）</li>
     *   <li>{@code before}: 返回时间戳<b>晚于</b>此值的数据（向未来翻页）</li>
     * </ul>
     * <p>所以查询 [startTime, endTime] 范围：after=endTime, before=startTime</p>
     */
    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        String instId = OkxSymbolUtils.toOkxInstId(symbol);
        String bar = OkxSymbolUtils.toOkxBar(interval);

        log.info("[OKX REST] 获取K线: instId={}, bar={}, range=[{} ~ {}]",
                instId, bar, Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/market/candles")
                            .queryParam("instId", instId)
                            .queryParam("bar", bar)
                            .queryParam("after", String.valueOf(endTime))
                            .queryParam("before", String.valueOf(startTime))
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

    /**
     * 拉取历史 K 线数据（自动分页）。
     *
     * <p>OKX 每次最多返回 100 条，本方法会从 endTime 向前循环拉取，
     * 直到达到 startTime 或无更多数据。</p>
     *
     * <p>OKX 有两个端点：</p>
     * <ul>
     *   <li>/api/v5/market/candles — 最近的K线数据</li>
     *   <li>/api/v5/market/history-candles — 更早的历史数据</li>
     * </ul>
     * 本方法会先用 candles 端点，数据不足时自动切换到 history-candles。
     *
     * @param symbol    交易对
     * @param interval  K线周期
     * @param startTime 起始时间（epoch millis）
     * @param endTime   结束时间（epoch millis）
     * @param sleepMs   每次请求间隔（毫秒），避免触发限流，建议 350ms
     * @return 全部 K 线（按时间正序）
     */
    public List<Kline> getKlinesHistory(String symbol, String interval, long startTime, long endTime, long sleepMs) {
        List<Kline> allKlines = new ArrayList<>();
        long cursor = endTime;
        int page = 0;
        boolean useHistory = false;

        while (cursor > startTime) {
            page++;
            String endpoint = useHistory ? "/api/v5/market/history-candles" : "/api/v5/market/candles";

            List<Kline> batch = fetchOnePage(symbol, interval, cursor, endpoint);

            if (batch.isEmpty()) {
                if (!useHistory) {
                    // candles 端点没数据了，切换到 history-candles
                    useHistory = true;
                    log.info("[OKX REST] candles 端点无更多数据，切换到 history-candles, cursor={}",
                            Instant.ofEpochMilli(cursor));
                    continue;
                }
                log.info("[OKX REST] 历史K线拉取完成：无更多数据，共 {} 条", allKlines.size());
                break;
            }

            // 过滤掉 startTime 之前的数据
            for (Kline k : batch) {
                if (k.getTimestamp().toEpochMilli() >= startTime) {
                    allKlines.add(k);
                }
            }

            // OKX 返回的数据按时间倒序，最后一条是最早的
            long oldestTs = batch.stream()
                    .mapToLong(k -> k.getTimestamp().toEpochMilli())
                    .min().orElse(cursor);

            if (oldestTs >= cursor) {
                // 没有更早的数据了
                break;
            }
            cursor = oldestTs;

            log.info("[OKX REST] 历史K线第{}页: 获取{}条, 最早={}, 累计={}",
                    page, batch.size(), Instant.ofEpochMilli(oldestTs), allKlines.size());

            // 限流保护
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }

        // 按时间正序排列
        allKlines.sort(Comparator.comparing(Kline::getTimestamp));
        log.info("[OKX REST] 历史K线拉取完成: {}:{} 共 {} 条, 范围 {} ~ {}",
                symbol, interval, allKlines.size(),
                allKlines.isEmpty() ? "-" : allKlines.get(0).getTimestamp(),
                allKlines.isEmpty() ? "-" : allKlines.get(allKlines.size() - 1).getTimestamp());

        return allKlines;
    }

    /**
     * 拉取一页 K 线（最多100条）。
     *
     * @param cursor   从此时间点向前拉取（OKX after 参数）
     * @param endpoint API 端点路径
     */
    private List<Kline> fetchOnePage(String symbol, String interval, long cursor, String endpoint) {
        String instId = OkxSymbolUtils.toOkxInstId(symbol);
        String bar = OkxSymbolUtils.toOkxBar(interval);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("instId", instId)
                            .queryParam("bar", bar)
                            .queryParam("after", String.valueOf(cursor))
                            .queryParam("limit", "100")
                            .build())
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("[OKX REST] 历史K线响应异常: {}", response);
                return List.of();
            }

            Object dataObj = response.get("data");
            if (!(dataObj instanceof List)) return List.of();

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

            return result;
        } catch (Exception e) {
            log.error("[OKX REST] 历史K线拉取异常: {}:{} error={}", symbol, interval, e.getMessage());
            return List.of();
        }
    }

}
