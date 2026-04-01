package com.crypto.trader.client.glassnode;

import com.crypto.trader.model.OnChainMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GlassnodeClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;

    @Value("${crypto.glassnode.base-url}")
    private String baseUrl;

    @Value("${crypto.glassnode.api-key}")
    private String apiKey;

    public GlassnodeClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /** 巨鲸大额转账量 */
    public List<OnChainMetric> getWhaleTransferVolume(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/transactions/transfers_volume_sum",
                asset, "whale_transfer_volume", from, to, "24h");
    }

    /** 净未实现盈亏 (NUPL) */
    public List<OnChainMetric> getNUPL(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/indicators/net_unrealized_profit_loss",
                asset, "nupl", from, to, "24h");
    }

    /** 花费产出利润率 (SOPR) */
    public List<OnChainMetric> getSOPR(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/indicators/sopr",
                asset, "sopr", from, to, "24h");
    }

    /** 交易所净流入 */
    public List<OnChainMetric> getExchangeNetFlow(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/transactions/transfers_volume_exchanges_net",
                asset, "exchange_net_flow", from, to, "24h");
    }

    /** 交易所流入量 */
    public List<OnChainMetric> getExchangeInflow(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/transactions/transfers_volume_to_exchanges_sum",
                asset, "exchange_inflow", from, to, "24h");
    }

    /** 交易所流出量 */
    public List<OnChainMetric> getExchangeOutflow(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/transactions/transfers_volume_from_exchanges_sum",
                asset, "exchange_outflow", from, to, "24h");
    }

    /** 活跃地址数 */
    public List<OnChainMetric> getActiveAddresses(String asset, Instant from, Instant to) {
        return fetchMetric("/v1/metrics/addresses/active_count",
                asset, "active_addresses", from, to, "24h");
    }

    /**
     * 通用 Glassnode 指标拉取方法。
     *
     * @param endpoint   API 路径
     * @param asset      交易对（BTCUSDT 会自动转换为 BTC）
     * @param metricName 存储时使用的指标名称
     * @param from       起始时间
     * @param to         结束时间
     * @param resolution 时间分辨率 (如 "24h", "1h")
     */
    private List<OnChainMetric> fetchMetric(String endpoint, String asset, String metricName,
                                            Instant from, Instant to, String resolution) {
        String ticker = toGlassnodeAsset(asset);
        try {
            List<Map<String, Object>> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("a", ticker)
                            .queryParam("api_key", apiKey)
                            .queryParam("s", from.getEpochSecond())
                            .queryParam("u", to.getEpochSecond())
                            .queryParam("i", resolution)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (response == null || response.isEmpty()) {
                return List.of();
            }

            List<OnChainMetric> result = new ArrayList<>(response.size());
            for (Map<String, Object> item : response) {
                Object t = item.get("t");
                Object v = item.get("v");
                if (t == null || v == null) continue;

                OnChainMetric metric = new OnChainMetric();
                metric.setSymbol(asset);
                metric.setMetricName(metricName);
                metric.setTimestamp(Instant.ofEpochSecond(((Number) t).longValue()));
                metric.setValue(new BigDecimal(String.valueOf(v)));
                result.add(metric);
            }
            log.debug("Fetched {} Glassnode [{}] metrics for {}", result.size(), metricName, ticker);
            return result;

        } catch (Exception e) {
            log.error("Failed to fetch Glassnode [{}] for {}: {}", metricName, ticker, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将内部交易对符号映射为 Glassnode 资产代码。
     * BTCUSDT -> BTC，ETHUSDT -> ETH
     */
    private String toGlassnodeAsset(String symbol) {
        if (symbol == null) return null;
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4);
        if (symbol.endsWith("USDC")) return symbol.substring(0, symbol.length() - 4);
        if (symbol.endsWith("BTC"))  return symbol.substring(0, symbol.length() - 3);
        return symbol;
    }
}
