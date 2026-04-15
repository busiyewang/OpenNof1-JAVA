package com.crypto.trader.scheduler;

import com.crypto.trader.backtest.BacktestService;
import com.crypto.trader.backtest.model.BacktestReport;
import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.service.ml.MlModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ML 模型定时训练调度器 + 自动回测验证。
 *
 * <p>流程：训练模型 → 自动回测最近3个月 → 输出各策略胜率/盈亏比/Sharpe</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MlTrainScheduler {

    private final MlModelService mlModelService;
    private final BacktestService backtestService;

    @Value("${crypto.watch-list:BTCUSDT}")
    private List<String> watchList;

    @Value("${crypto.ml.train-interval:1h}")
    private String trainInterval;

    /**
     * 每天凌晨3点重训练 + 回测验证。
     */
    @Scheduled(cron = "${crypto.ml.train-cron:0 0 3 * * ?}")
    public void scheduledTrain() {
        log.info("[ML调度] 开始定时模型训练...");
        trainAndValidate();
    }

    /**
     * 启动后延迟5分钟执行首次训练 + 回测。
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = Long.MAX_VALUE)
    public void initialTrain() {
        log.info("[ML调度] 启动后首次模型训练...");
        trainAndValidate();
    }

    private void trainAndValidate() {
        for (String symbol : watchList) {
            // 1. 训练
            try {
                String report = mlModelService.train(symbol, trainInterval);
                log.info("[ML调度] {}", report);
            } catch (Exception e) {
                log.error("[ML调度] {} 训练失败: {}", symbol, e.getMessage(), e);
                continue;
            }

            // 2. 自动回测验证（最近3个月）
            try {
                runAutoBacktest(symbol);
            } catch (Exception e) {
                log.warn("[ML调度] {} 自动回测失败: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * 自动回测：用所有策略跑最近3个月数据，输出收益对比。
     */
    private void runAutoBacktest(String symbol) {
        String endDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String startDate = LocalDate.now().minusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE);

        BacktestRequest request = new BacktestRequest();
        request.setSymbol(symbol);
        request.setInterval(trainInterval);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setInitialCapital(10000);
        request.setPositionSizePercent(50);
        request.setStopLossPercent(3.0);
        request.setTakeProfitPercent(6.0);
        request.setWindowSize(100);
        request.setFeePercent(0.1);

        List<BacktestReport> reports = backtestService.runBacktest(request);

        if (reports.isEmpty()) {
            log.info("[回测验证] {} 无足够数据回测", symbol);
            return;
        }

        log.info("[回测验证] ========== {} 策略对比 ({} ~ {}) ==========", symbol, startDate, endDate);
        log.info("[回测验证] {}", String.format("%-20s %8s %8s %8s %8s %8s",
                "策略", "收益%", "胜率%", "盈亏比", "Sharpe", "最大回撤%"));
        log.info("[回测验证] {}", "-".repeat(70));

        for (BacktestReport r : reports) {
            log.info("[回测验证] {}", String.format("%-20s %+7.2f%% %7.1f%% %7.2f %7.2f %7.2f%%",
                    r.getStrategyName(),
                    r.getTotalReturnPercent(),
                    r.getWinRate(),
                    r.getProfitLossRatio(),
                    r.getSharpeRatio(),
                    r.getMaxDrawdownPercent()));
        }

        // 找最佳策略
        BacktestReport best = reports.get(0); // 已按收益排序
        if (best.getTotalReturnPercent() > 0) {
            log.info("[回测验证] 最佳策略: {} (收益={}%, Sharpe={})",
                    best.getStrategyName(),
                    String.format("%.2f", best.getTotalReturnPercent()),
                    String.format("%.2f", best.getSharpeRatio()));
        } else {
            log.warn("[回测验证] 所有策略近3个月均为亏损，请谨慎使用");
        }
    }
}
