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

    /**
     * 获取指定资产在时间范围内的巨鲸大额转账量指标（transfers_volume_sum）。
     *
     * <p>Glassnode 响应格式：[{"t": unix_seconds, "v": number}, ...]</p>
     *
     * @param asset Glassnode 资产代码（BTC/ETH/SOL）；若传入交易对（如 BTCUSDT）会自动截取
     * @param from  起始时间（含）
     * @param to    结束时间（含）
     * @return 指标列表；无数据或请求失败时返回空列表
     */
    public List<OnChainMetric> getWhaleTransactionCount(String asset, Instant from, Instant to) {
        String ticker = toGlassnodeAsset(asset);
        try {
            List<Map<String, Object>> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/metrics/transactions/transfers_volume_sum")
                            .queryParam("a", ticker)
                            .queryParam("api_key", apiKey)
                            .queryParam("s", from.getEpochSecond())
                            .queryParam("u", to.getEpochSecond())
                            .queryParam("i", "24h")
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
                metric.setMetricName("whale_transaction_count");
                metric.setTimestamp(Instant.ofEpochSecond(((Number) t).longValue()));
                metric.setValue(new BigDecimal(String.valueOf(v)));
                result.add(metric);
            }
            log.debug("Fetched {} Glassnode whale metrics for {}", result.size(), ticker);
            return result;

        } catch (Exception e) {
            log.error("Failed to fetch Glassnode whale data for {}", ticker, e);
            return List.of();
        }
    }

    /**
     * 将内部交易对符号映射为 Glassnode 资产代码。
     * BTCUSDT -> BTC，ETHUSDT -> ETH，依此类推。
     */
    private String toGlassnodeAsset(String symbol) {
        if (symbol == null) return null;
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4);
        if (symbol.endsWith("USDC")) return symbol.substring(0, symbol.length() - 4);
        if (symbol.endsWith("BTC"))  return symbol.substring(0, symbol.length() - 3);
        return symbol;
    }
}
