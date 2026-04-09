package com.crypto.trader.client.market;

import com.crypto.trader.model.OnChainMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coinglass 爆仓数据客户端。
 *
 * <p>数据来源：<a href="https://www.coinglass.com/">Coinglass</a></p>
 * <p>需要 API Key（免费注册即可获取）。</p>
 *
 * <p>爆仓数据含义：</p>
 * <ul>
 *   <li>大量多头爆仓：市场急跌，可能见底（多头被清洗完毕）</li>
 *   <li>大量空头爆仓：市场急涨，可能见顶（空头被清洗完毕）</li>
 *   <li>爆仓量骤增：波动率增大的信号</li>
 * </ul>
 */
@Service
@Slf4j
public class CoinglassClient {

    private static final String BASE_URL = "https://open-api.coinglass.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Value("${crypto.coinglass.api-key:}")
    private String apiKey;

    @Autowired
    private WebClient.Builder webClientBuilder;

    /**
     * 获取最近 24 小时的爆仓数据。
     */
    public List<OnChainMetric> getLiquidations(String symbol) {
        if (!StringUtils.hasText(apiKey) || apiKey.startsWith("your-")) {
            log.debug("[Coinglass] API Key 未配置，跳过爆仓数据");
            return List.of();
        }

        String coin = toCoin(symbol);
        try {
            WebClient client = webClientBuilder.build();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.get()
                    .uri(BASE_URL + "/public/v2/liquidation?symbol=" + coin + "&time_type=1")
                    .header("coinglassSecret", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(TIMEOUT)
                    .block();

            if (response == null) {
                return List.of();
            }

            String code = String.valueOf(response.get("code"));
            if (!"0".equals(code)) {
                log.warn("[Coinglass] 爆仓数据响应异常: code={}, msg={}", code, response.get("msg"));
                return List.of();
            }

            Object dataObj = response.get("data");
            if (!(dataObj instanceof List)) return List.of();

            List<?> data = (List<?>) dataObj;
            List<OnChainMetric> result = new ArrayList<>();
            Instant now = Instant.now();

            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> entry = (Map<?, ?>) item;

                // 多头爆仓量
                Object longLiq = entry.get("longVolUsd");
                if (longLiq != null) {
                    OnChainMetric longMetric = new OnChainMetric();
                    longMetric.setSymbol(symbol);
                    longMetric.setMetricName("liquidation_long_usd");
                    longMetric.setTimestamp(now);
                    longMetric.setValue(new BigDecimal(String.valueOf(longLiq)));
                    result.add(longMetric);
                }

                // 空头爆仓量
                Object shortLiq = entry.get("shortVolUsd");
                if (shortLiq != null) {
                    OnChainMetric shortMetric = new OnChainMetric();
                    shortMetric.setSymbol(symbol);
                    shortMetric.setMetricName("liquidation_short_usd");
                    shortMetric.setTimestamp(now);
                    shortMetric.setValue(new BigDecimal(String.valueOf(shortLiq)));
                    result.add(shortMetric);
                }

                // 多空爆仓比
                if (longLiq != null && shortLiq != null) {
                    BigDecimal longVal = new BigDecimal(String.valueOf(longLiq));
                    BigDecimal shortVal = new BigDecimal(String.valueOf(shortLiq));
                    if (shortVal.compareTo(BigDecimal.ZERO) > 0) {
                        OnChainMetric ratio = new OnChainMetric();
                        ratio.setSymbol(symbol);
                        ratio.setMetricName("liquidation_long_short_ratio");
                        ratio.setTimestamp(now);
                        ratio.setValue(longVal.divide(shortVal, 4, BigDecimal.ROUND_HALF_UP));
                        result.add(ratio);
                    }
                }

                break; // 只取第一条（当前币种）
            }

            log.info("[Coinglass] {} 爆仓数据: {} 条", symbol, result.size());
            return result;

        } catch (Exception e) {
            log.error("[Coinglass] 爆仓数据获取失败: {} {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** BTCUSDT -> BTC */
    private String toCoin(String symbol) {
        if (symbol == null) return null;
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4);
        return symbol;
    }
}
