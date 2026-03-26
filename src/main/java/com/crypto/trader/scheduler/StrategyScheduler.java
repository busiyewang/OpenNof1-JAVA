package com.crypto.trader.scheduler;

import com.crypto.trader.service.strategy.StrategyExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class StrategyScheduler {

    @Autowired
    private StrategyExecutor strategyExecutor;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    /**
     * 周期性对关注列表中的交易对执行策略。
     *
     * <p>默认以 fixedDelay 方式每 60 秒触发一次：上一轮执行完成后再延迟 60 秒触发下一轮，
     * 可以减少“任务堆积”的风险（但单轮执行时间过长仍会导致调度频率下降）。</p>
     *
     * <p>对 watch list 使用并行流并发执行，意味着：</p>
     * <ul>
     *   <li>同一时刻可能并发执行多个 symbol 的策略</li>
     *   <li>{@link StrategyExecutor#execute(String)} 及其依赖（DB、通知、下单等）需要具备并发安全性</li>
     *   <li>如需限制并发度/线程池，建议不要使用 parallelStream，改用自定义线程池或调度器</li>
     * </ul>
     * </p>
     */
    /**
     * 每 5 分钟执行一次策略评估（上一轮完成后延迟 5 分钟）。
     */
    @Scheduled(fixedDelay = 300000)
    public void runStrategies() {
        watchList.parallelStream().forEach(strategyExecutor::execute);
    }
}
