package com.crypto.trader.scheduler;

import com.crypto.trader.service.strategy.StrategyExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class StrategyScheduler {

    private final StrategyExecutor strategyExecutor;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    /**
     * 每 5 分钟执行一次策略评估（上一轮完成后延迟 5 分钟）。
     *
     * <p>以 fixedDelay 方式触发：上一轮执行完成后再延迟 5 分钟触发下一轮，
     * 可以减少”任务堆积”的风险。watch list 通常仅 1-3 个交易对，顺序执行即可。</p>
     */
    @Scheduled(fixedDelay = 300000)
    public void runStrategies() {
        log.info("========== [策略调度] 开始执行策略评估，交易对: {} ==========", watchList);
        long startTime = System.currentTimeMillis();

        watchList.forEach(symbol -> {
            try {
                strategyExecutor.execute(symbol);
            } catch (Exception e) {
                log.error("[策略调度] {} 策略执行异常: {}", symbol, e.getMessage(), e);
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("========== [策略调度] 策略评估结束，交易对数: {}, 耗时: {}ms ==========",
                watchList.size(), elapsed);
    }
}
