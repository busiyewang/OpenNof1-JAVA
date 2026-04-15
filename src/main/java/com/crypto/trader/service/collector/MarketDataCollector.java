package com.crypto.trader.service.collector;

import com.crypto.trader.client.market.CoinglassClient;
import com.crypto.trader.client.market.FearGreedClient;
import com.crypto.trader.client.market.OkxMarketDataClient;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.OnChainMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 市场数据采集器：资金费率、合约持仓量、恐惧贪婪指数、爆仓数据。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataCollector {

    private final OkxMarketDataClient okxMarketDataClient;

    private final FearGreedClient fearGreedClient;

    private final CoinglassClient coinglassClient;

    private final OnChainMetricRepository metricRepository;

    /**
     * 采集所有市场数据指标。
     */
    public void collectAll(String symbol) {
        log.info("[市场数据] {} 开始采集...", symbol);
        long t0 = System.currentTimeMillis();
        int total = 0;

        // 1. 资金费率（当前 + 历史）
        total += saveMetrics(okxMarketDataClient.getFundingRate(symbol), "funding_rate");
        total += saveMetrics(okxMarketDataClient.getFundingRateHistory(symbol, 30), "funding_rate_history");

        // 2. 合约持仓量
        total += saveMetrics(okxMarketDataClient.getOpenInterest(symbol), "open_interest");

        // 3. 恐惧贪婪指数（最近7天）
        total += saveMetrics(fearGreedClient.getFearGreedIndex(symbol, 7), "fear_greed");

        // 4. 爆仓数据（需要 API Key）
        total += saveMetrics(coinglassClient.getLiquidations(symbol), "liquidations");

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[市场数据] {} 采集完成，共保存 {} 条，耗时 {}ms", symbol, total, elapsed);
    }

    private int saveMetrics(List<OnChainMetric> metrics, String label) {
        if (metrics == null || metrics.isEmpty()) return 0;
        try {
            // 去重保存
            List<OnChainMetric> newMetrics = metrics.stream()
                    .filter(m -> !metricRepository.existsBySymbolAndMetricNameAndTimestamp(
                            m.getSymbol(), m.getMetricName(), m.getTimestamp()))
                    .toList();
            if (!newMetrics.isEmpty()) {
                metricRepository.saveAll(newMetrics);
            }
            log.debug("[市场数据] [{}] 新增 {} 条（跳过 {} 条重复）",
                    label, newMetrics.size(), metrics.size() - newMetrics.size());
            return newMetrics.size();
        } catch (Exception e) {
            log.warn("[市场数据] [{}] 保存失败: {}", label, e.getMessage());
            return 0;
        }
    }
}
