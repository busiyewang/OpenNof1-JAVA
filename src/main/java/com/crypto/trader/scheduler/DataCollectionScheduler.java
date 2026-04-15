package com.crypto.trader.scheduler;

import com.crypto.trader.service.collector.KlineCollector;
import com.crypto.trader.service.collector.MarketDataCollector;
import com.crypto.trader.service.collector.OnChainCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataCollectionScheduler {

    private final KlineCollector klineCollector;

    private final OnChainCollector onChainCollector;

    private final MarketDataCollector marketDataCollector;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    @Value("${crypto.analysis.timeframes:1h,4h,1d}")
    private List<String> analysisTimeframes;

    /** 每 60 秒回补 1m K线 */
    @Scheduled(fixedDelay = 60000)
    public void collectKlines1m() {
        collectKlinesForInterval("1m");
    }

    /** 每 5 分钟回补分析用的多时间框架 K线 (1h, 4h, 1d) */
    @Scheduled(fixedDelay = 300000)
    public void collectKlinesMultiTimeframe() {
        for (String interval : analysisTimeframes) {
            collectKlinesForInterval(interval);
        }
    }

    private void collectKlinesForInterval(String interval) {
        log.info("[数据采集] K线回补: {} 交易对: {}", interval, watchList);
        for (String symbol : watchList) {
            try {
                klineCollector.collect(symbol, interval);
            } catch (Exception e) {
                log.error("[数据采集] K线回补失败: {} {} 错误: {}", symbol, interval, e.getMessage(), e);
            }
        }
    }

    /** 每小时采集所有链上指标 */
    @Scheduled(cron = "0 0 */1 * * ?")
    public void collectOnChainMetrics() {
        log.info("[数据采集] 链上指标采集任务开始，交易对: {}", watchList);
        for (String symbol : watchList) {
            try {
                onChainCollector.collectAllMetrics(symbol);
            } catch (Exception e) {
                log.error("[数据采集] 链上指标采集失败: {} 错误: {}", symbol, e.getMessage(), e);
            }
        }
    }

    /** 每 30 分钟采集市场数据（资金费率、持仓量、恐惧贪婪、爆仓） */
    @Scheduled(fixedDelay = 1800000)
    public void collectMarketData() {
        log.info("[数据采集] 市场数据采集开始，交易对: {}", watchList);
        for (String symbol : watchList) {
            try {
                marketDataCollector.collectAll(symbol);
            } catch (Exception e) {
                log.error("[数据采集] 市场数据采集失败: {} 错误: {}", symbol, e.getMessage(), e);
            }
        }
    }
}
