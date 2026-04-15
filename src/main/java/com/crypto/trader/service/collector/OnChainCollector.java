package com.crypto.trader.service.collector;

import com.crypto.trader.client.glassnode.GlassnodeClient;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.OnChainMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OnChainCollector {

    private final GlassnodeClient glassnodeClient;

    private final OnChainMetricRepository metricRepository;

    /**
     * 拉取并保存所有链上指标（最近 24 小时）。
     */
    public void collectAllMetrics(String symbol) {
        log.info("[链上采集] {} 开始采集全部链上指标...", symbol);
        long t0 = System.currentTimeMillis();

        Instant to = Instant.now();
        Instant from = to.minusSeconds(24 * 60 * 60);

        int total = 0;
        total += saveMetrics(glassnodeClient.getWhaleTransferVolume(symbol, from, to), symbol, "whale_transfer_volume");
        total += saveMetrics(glassnodeClient.getNUPL(symbol, from, to), symbol, "nupl");
        total += saveMetrics(glassnodeClient.getSOPR(symbol, from, to), symbol, "sopr");
        total += saveMetrics(glassnodeClient.getExchangeNetFlow(symbol, from, to), symbol, "exchange_net_flow");
        total += saveMetrics(glassnodeClient.getExchangeInflow(symbol, from, to), symbol, "exchange_inflow");
        total += saveMetrics(glassnodeClient.getExchangeOutflow(symbol, from, to), symbol, "exchange_outflow");
        total += saveMetrics(glassnodeClient.getActiveAddresses(symbol, from, to), symbol, "active_addresses");

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[链上采集] {} 全部指标采集完成，共保存 {} 条数据，耗时: {}ms", symbol, total, elapsed);
    }

    /** 保留旧方法兼容调用 */
    public void collectWhaleMetrics(String symbol) {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(24 * 60 * 60);
        saveMetrics(glassnodeClient.getWhaleTransferVolume(symbol, from, to), symbol, "whale_transfer_volume");
    }

    private int saveMetrics(List<OnChainMetric> metrics, String symbol, String metricName) {
        try {
            if (metrics != null && !metrics.isEmpty()) {
                metricRepository.saveAll(metrics);
                log.debug("[链上采集] {} [{}] 保存 {} 条", symbol, metricName, metrics.size());
                return metrics.size();
            }
        } catch (Exception e) {
            log.warn("[链上采集] {} [{}] 保存失败: {}", symbol, metricName, e.getMessage());
        }
        return 0;
    }
}
