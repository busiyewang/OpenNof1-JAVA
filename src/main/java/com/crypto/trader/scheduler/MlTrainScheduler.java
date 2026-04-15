package com.crypto.trader.scheduler;

import com.crypto.trader.service.ml.MlModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ML 模型定时训练调度器。
 *
 * <p>每天凌晨3点自动重训练模型，使用最新数据更新特征权重。
 * 应用启动5分钟后也会触发首次训练（确保有可用模型）。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MlTrainScheduler {

    private final MlModelService mlModelService;

    @Value("${crypto.watch-list:BTCUSDT}")
    private List<String> watchList;

    @Value("${crypto.ml.train-interval:1h}")
    private String trainInterval;

    /**
     * 每天凌晨3点重训练。
     */
    @Scheduled(cron = "${crypto.ml.train-cron:0 0 3 * * ?}")
    public void scheduledTrain() {
        log.info("[ML调度] 开始定时模型训练...");
        trainAll();
    }

    /**
     * 启动后延迟5分钟执行首次训练。
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = Long.MAX_VALUE)
    public void initialTrain() {
        log.info("[ML调度] 启动后首次模型训练...");
        trainAll();
    }

    private void trainAll() {
        for (String symbol : watchList) {
            try {
                String report = mlModelService.train(symbol, trainInterval);
                log.info("[ML调度] {}", report);
            } catch (Exception e) {
                log.error("[ML调度] {} 训练失败: {}", symbol, e.getMessage(), e);
            }
        }
    }
}
