package com.crypto.trader.client.market;

import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.util.RetryUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OKX 市场数据客户端（资金费率 + 合约持仓量）。
 *
 * <p>使用 OKX 公开接口，无需 API Key。</p>
 */
@Service
@Slf4j
public class OkxMarketDataClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Value("${crypto.exchange.okx.base-url}")
    private String baseUrl;

    public OkxMarketDataClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 获取当前资金费率。
     *
     * <p>OKX API: GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP</p>
     *
     * <p>资金费率含义：</p>
     * <ul>
     *   <li>正值: 多头支付空头，说明市场偏多（多头拥挤）</li>
     *   <li>负值: 空头支付多头，说明市场偏空（空头拥挤）</li>
     *   <li>极端值 (> 0.1% 或 < -0.1%): 常预示反转</li>
     * </ul>
     */
    public List<OnChainMetric> getFundingRate(String symbol) {
        String instId = toSwapInstId(symbol);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = RetryUtil.withRetry(() ->
                    webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/public/funding-rate")
                            .queryParam("instId", instId)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block(),
                    "OkxMarket-fundingRate-" + symbol);

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("[OkxMarket] 资金费率响应异常: {}", response);
                return List.of();
            }

            List<?> data = (List<?>) response.get("data");
            if (data == null || data.isEmpty()) return List.of();

            List<OnChainMetric> result = new ArrayList<>();
            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) item;

                // 当前资金费率
                String fundingRate = String.valueOf(entry.get("fundingRate"));
                String fundingTime = String.valueOf(entry.get("fundingTime"));

                OnChainMetric current = new OnChainMetric();
                current.setSymbol(symbol);
                current.setMetricName("funding_rate");
                current.setTimestamp(Instant.ofEpochMilli(Long.parseLong(fundingTime)));
                current.setValue(new BigDecimal(fundingRate));
                result.add(current);

                // 下期预测费率
                String nextFundingRate = String.valueOf(entry.get("nextFundingRate"));
                if (nextFundingRate != null && !"null".equals(nextFundingRate) && !nextFundingRate.isEmpty()) {
                    String nextFundingTime = String.valueOf(entry.get("nextFundingTime"));
                    OnChainMetric next = new OnChainMetric();
                    next.setSymbol(symbol);
                    next.setMetricName("funding_rate_next");
                    next.setTimestamp(Instant.ofEpochMilli(Long.parseLong(nextFundingTime)));
                    next.setValue(new BigDecimal(nextFundingRate));
                    result.add(next);
                }
            }

            log.info("[OkxMarket] {} 资金费率: {}", symbol,
                    result.isEmpty() ? "-" : result.get(0).getValue());
            return result;

        } catch (Exception e) {
            log.error("[OkxMarket] 资金费率获取失败: {} {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取资金费率历史（最近 N 条）。
     *
     * <p>OKX API: GET /api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP</p>
     */
    public List<OnChainMetric> getFundingRateHistory(String symbol, int limit) {
        String instId = toSwapInstId(symbol);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = RetryUtil.withRetry(() ->
                    webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/public/funding-rate-history")
                            .queryParam("instId", instId)
                            .queryParam("limit", String.valueOf(Math.min(limit, 100)))
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block(),
                    "OkxMarket-fundingRateHistory-" + symbol);

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                return List.of();
            }

            List<?> data = (List<?>) response.get("data");
            if (data == null) return List.of();

            List<OnChainMetric> result = new ArrayList<>();
            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) item;

                OnChainMetric metric = new OnChainMetric();
                metric.setSymbol(symbol);
                metric.setMetricName("funding_rate");
                metric.setTimestamp(Instant.ofEpochMilli(
                        Long.parseLong(String.valueOf(entry.get("fundingTime")))));
                metric.setValue(new BigDecimal(String.valueOf(entry.get("realizedRate"))));
                result.add(metric);
            }

            log.info("[OkxMarket] {} 资金费率历史: {} 条", symbol, result.size());
            return result;

        } catch (Exception e) {
            log.error("[OkxMarket] 资金费率历史获取失败: {} {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取当前合约持仓量 (Open Interest)。
     *
     * <p>OKX API: GET /api/v5/public/open-interest?instType=SWAP&instId=BTC-USDT-SWAP</p>
     *
     * <p>持仓量含义：</p>
     * <ul>
     *   <li>OI 上升 + 价格上升 = 新多头入场，趋势健康</li>
     *   <li>OI 上升 + 价格下跌 = 新空头入场，下跌趋势加强</li>
     *   <li>OI 下降 + 价格上升 = 空头平仓推动，上涨可能不持久</li>
     *   <li>OI 下降 + 价格下跌 = 多头平仓推动，可能见底</li>
     * </ul>
     */
    public List<OnChainMetric> getOpenInterest(String symbol) {
        String instId = toSwapInstId(symbol);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = RetryUtil.withRetry(() ->
                    webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/public/open-interest")
                            .queryParam("instType", "SWAP")
                            .queryParam("instId", instId)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block(),
                    "OkxMarket-openInterest-" + symbol);

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("[OkxMarket] 持仓量响应异常: {}", response);
                return List.of();
            }

            List<?> data = (List<?>) response.get("data");
            if (data == null || data.isEmpty()) return List.of();

            List<OnChainMetric> result = new ArrayList<>();
            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) item;

                // OI (币本位)
                OnChainMetric oiCoin = new OnChainMetric();
                oiCoin.setSymbol(symbol);
                oiCoin.setMetricName("open_interest");
                oiCoin.setTimestamp(Instant.ofEpochMilli(
                        Long.parseLong(String.valueOf(entry.get("ts")))));
                oiCoin.setValue(new BigDecimal(String.valueOf(entry.get("oi"))));
                result.add(oiCoin);

                // OI (USDT 计价)
                String oiCcy = String.valueOf(entry.get("oiCcy"));
                if (oiCcy != null && !"null".equals(oiCcy)) {
                    OnChainMetric oiUsdt = new OnChainMetric();
                    oiUsdt.setSymbol(symbol);
                    oiUsdt.setMetricName("open_interest_usdt");
                    oiUsdt.setTimestamp(Instant.ofEpochMilli(
                            Long.parseLong(String.valueOf(entry.get("ts")))));
                    oiUsdt.setValue(new BigDecimal(oiCcy));
                    result.add(oiUsdt);
                }
            }

            log.info("[OkxMarket] {} 持仓量: {}", symbol,
                    result.isEmpty() ? "-" : result.get(0).getValue());
            return result;

        } catch (Exception e) {
            log.error("[OkxMarket] 持仓量获取失败: {} {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** BTCUSDT -> BTC-USDT-SWAP */
    private String toSwapInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) return symbol;
        String base = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote + "-SWAP";
    }
}
