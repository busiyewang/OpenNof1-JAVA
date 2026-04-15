package com.crypto.trader.client.market;

import com.crypto.trader.model.OnChainMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 恐惧贪婪指数客户端。
 *
 * <p>数据来源：<a href="https://alternative.me/crypto/fear-and-greed-index/">Alternative.me</a></p>
 * <p>免费，无需 API Key。</p>
 *
 * <p>指数范围 0-100：</p>
 * <ul>
 *   <li>0-24: 极度恐惧（常见于底部）</li>
 *   <li>25-49: 恐惧</li>
 *   <li>50-74: 贪婪</li>
 *   <li>75-100: 极度贪婪（常见于顶部）</li>
 * </ul>
 */
@Service
@Slf4j
public class FearGreedClient {

    private static final String API_URL = "https://api.alternative.me/fng/";

    private final WebClient.Builder webClientBuilder;

    public FearGreedClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 获取最近 N 天的恐惧贪婪指数。
     *
     * @param days 天数（最多支持约365天）
     */
    public List<OnChainMetric> getFearGreedIndex(String symbol, int days) {
        try {
            WebClient client = webClientBuilder.build();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.get()
                    .uri(API_URL + "?limit=" + days + "&format=json")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null || !response.containsKey("data")) {
                log.warn("[FearGreed] 响应为空");
                return List.of();
            }

            List<?> data = (List<?>) response.get("data");
            List<OnChainMetric> result = new ArrayList<>();

            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) item;

                String value = String.valueOf(entry.get("value"));
                String timestamp = String.valueOf(entry.get("timestamp"));

                OnChainMetric metric = new OnChainMetric();
                metric.setSymbol(symbol);
                metric.setMetricName("fear_greed_index");
                metric.setTimestamp(Instant.ofEpochSecond(Long.parseLong(timestamp)));
                metric.setValue(new BigDecimal(value));
                result.add(metric);
            }

            log.info("[FearGreed] 获取 {} 条恐惧贪婪指数", result.size());
            return result;

        } catch (Exception e) {
            log.error("[FearGreed] 获取失败: {}", e.getMessage());
            return List.of();
        }
    }
}
