package com.crypto.trader.scheduler;

import com.crypto.trader.service.analysis.PredictionScorerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 预测评分定时任务。
 *
 * <p>每 6 小时执行一次，对 24h 和 72h 前的预测进行回溯评分。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PredictionScoreScheduler {

    private final PredictionScorerService scorerService;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    /** 每 6 小时评分一次 */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void scorePredictions() {
        log.info("[预测评分调度] ========== 开始回溯评分 ==========");

        for (String symbol : watchList) {
            try {
                // 24小时回溯
                scorerService.scoreUnscored(symbol, 24);
                // 72小时回溯
                scorerService.scoreUnscored(symbol, 72);
            } catch (Exception e) {
                log.error("[预测评分调度] {} 评分异常: {}", symbol, e.getMessage(), e);
            }
        }

        log.info("[预测评分调度] ========== 回溯评分结束 ==========");
    }
}
