package com.crypto.trader.scheduler;

import com.crypto.trader.service.collector.KlineCollector;
import com.crypto.trader.service.collector.OnChainCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class DataCollectionScheduler {

    @Autowired
    private KlineCollector klineCollector;

    @Autowired
    private OnChainCollector onChainCollector;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    /**
     * 周期性采集 K 线并落库。
     *
     * <p>默认每 60 秒触发一次，为 watch list 中每个交易对抓取 1m 级别数据。
     * 单个交易对采集失败会被捕获并记录日志，不影响其他交易对。</p>
     *
     * <p>数据采集与策略执行是两个独立的定时任务：如果你对“策略只能用到最新采集的数据”有强一致要求，
     * 需要在调度编排、锁或消息队列层面做协调（当前实现偏“尽力而为”的弱一致）。</p>
     */
    @Scheduled(fixedDelay = 60000)
    public void collectKlines() {
        for (String symbol : watchList) {
            try {
                klineCollector.collect(symbol, "1m");
            } catch (Exception e) {
                log.error("Error collecting klines for {}", symbol, e);
            }
        }
    }

    /**
     * 周期性采集链上指标（当前为巨鲸相关指标）并落库。
     *
     * <p>按 cron 每小时触发一次。单个交易对采集失败会被捕获并记录日志，不影响其他交易对。</p>
     *
     * <p>链上数据通常频率更低/延迟更大；策略使用时建议允许“数据缺失”并降级处理。</p>
     */
    @Scheduled(cron = "0 0 */1 * * ?")
    public void collectOnChainMetrics() {
        for (String symbol : watchList) {
            try {
                onChainCollector.collectWhaleMetrics(symbol);
            } catch (Exception e) {
                log.error("Error collecting on-chain metrics for {}", symbol, e);
            }
        }
    }
}
