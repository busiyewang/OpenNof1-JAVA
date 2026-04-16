package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.repository.SignalRepository;
import com.crypto.trader.service.indicator.Ta4jIndicatorService;
import com.crypto.trader.service.indicator.Ta4jIndicatorService.IndicatorSnapshot;
import com.crypto.trader.service.notifier.Notifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 策略执行器（加权投票版）。
 *
 * <p>改进：旧版取最高置信度的单个信号，新版采用加权投票：</p>
 * <ul>
 *   <li>每个策略的"投票权重" = 置信度（0~1）</li>
 *   <li>BUY 方向的加权得分 = 所有 BUY 信号的置信度之和</li>
 *   <li>SELL 方向的加权得分 = 所有 SELL 信号的置信度之和</li>
 *   <li>最终方向取得分高的；置信度 = 方向得分 / 总得分</li>
 *   <li>如果 BUY 和 SELL 得分接近（差距<20%）→ HOLD（信号矛盾）</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StrategyExecutor {

    private final List<TradingStrategy> strategies;

    private final List<Notifier> notifiers;

    private final KlineRepository klineRepository;

    private final OnChainMetricRepository onChainRepository;

    private final SignalRepository signalRepository;

    private final Ta4jIndicatorService ta4jService;

    /** BUY/SELL 得分差距低于此比例视为矛盾 → HOLD */
    private static final double CONFLICT_THRESHOLD = 0.2;

    public void execute(String symbol) {
        log.info("[策略执行] -------- {} 开始策略评估 --------", symbol);

        List<Kline> klinesDesc = klineRepository.findLatestKlines(symbol, "1h", 100);
        List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, "whale_transfer_volume");

        if (klinesDesc == null || klinesDesc.isEmpty()) {
            log.warn("[策略执行] {} 数据库中无K线数据，跳过", symbol);
            return;
        }

        List<Kline> klines = klinesDesc.stream()
                .sorted(Comparator.comparing(Kline::getTimestamp))
                .collect(Collectors.toList());

        // 收集所有策略信号
        List<Signal> allSignals = strategies.stream()
                .map(s -> {
                    try {
                        return s.evaluate(symbol, klines, metrics);
                    } catch (Exception e) {
                        log.warn("[策略执行] {} 策略 {} 异常: {}", symbol, s.getName(), e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());

        if (allSignals.isEmpty()) return;

        // 检测市场状态，动态调整策略权重
        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        MarketRegimeDetector.Regime regime = MarketRegimeDetector.detect(snap);
        log.info("[策略执行] {} 市场状态: {} (ADX={}, BBW={}, ATR%={})",
                symbol, regime,
                snap != null ? String.format("%.1f", snap.adx14) : "N/A",
                snap != null ? String.format("%.4f", snap.bbWidth) : "N/A",
                snap != null ? String.format("%.2f", snap.atrPercent) : "N/A");

        // 加权投票（市场状态感知）
        double buyScore = 0, sellScore = 0;
        int buyCount = 0, sellCount = 0, holdCount = 0;

        for (Signal s : allSignals) {
            double regimeWeight = MarketRegimeDetector.getWeight(regime, s.getStrategyName());
            double adjustedConfidence = s.getConfidence() * regimeWeight;

            switch (s.getAction()) {
                case BUY -> {
                    buyScore += adjustedConfidence;
                    buyCount++;
                    log.debug("[策略执行] {} {} BUY 原始={} 状态权重={} 调整后={}",
                            symbol, s.getStrategyName(),
                            String.format("%.2f", s.getConfidence()),
                            String.format("%.1f", regimeWeight),
                            String.format("%.2f", adjustedConfidence));
                }
                case SELL -> {
                    sellScore += adjustedConfidence;
                    sellCount++;
                    log.debug("[策略执行] {} {} SELL 原始={} 状态权重={} 调整后={}",
                            symbol, s.getStrategyName(),
                            String.format("%.2f", s.getConfidence()),
                            String.format("%.1f", regimeWeight),
                            String.format("%.2f", adjustedConfidence));
                }
                case HOLD -> holdCount++;
            }
        }

        double totalDirectional = buyScore + sellScore;

        log.info("[策略执行] {} 投票({}): BUY={} (权重={}) | SELL={} (权重={}) | HOLD={}",
                symbol, regime, buyCount, String.format("%.2f", buyScore),
                sellCount, String.format("%.2f", sellScore), holdCount);

        // 全是HOLD或者没有方向性信号
        if (totalDirectional == 0) {
            log.info("[策略执行] {} 所有策略结果为 HOLD", symbol);
            return;
        }

        // 检查信号矛盾：BUY 和 SELL 得分接近
        double dominance = Math.abs(buyScore - sellScore) / totalDirectional;
        if (dominance < CONFLICT_THRESHOLD && buyCount > 0 && sellCount > 0) {
            log.info("[策略执行] {} 信号矛盾 (优势度={}<{}), BUY={} vs SELL={} → HOLD",
                    symbol, String.format("%.2f", dominance),
                    String.format("%.2f", CONFLICT_THRESHOLD),
                    String.format("%.2f", buyScore), String.format("%.2f", sellScore));
            return;
        }

        // 确定最终方向和置信度
        Signal.Action finalAction = buyScore >= sellScore ? Signal.Action.BUY : Signal.Action.SELL;
        double winnerScore = Math.max(buyScore, sellScore);
        double finalConfidence = totalDirectional > 0 ? winnerScore / totalDirectional : 0;

        // 找最高置信度（调整后）的同方向信号作为代表（取其价格和reason）
        Signal representative = allSignals.stream()
                .filter(s -> s.getAction() == finalAction)
                .max(Comparator.comparingDouble(s ->
                        s.getConfidence() * MarketRegimeDetector.getWeight(regime, s.getStrategyName())))
                .orElse(null);

        if (representative == null) return;

        // 构建最终信号
        String voteDetail = String.format("加权投票(%s): BUY=%.2f(%d票) SELL=%.2f(%d票) HOLD=%d | 优势度=%.0f%%",
                regime, buyScore, buyCount, sellScore, sellCount, holdCount, dominance * 100);

        Signal finalSignal = Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(finalAction)
                .price(representative.getPrice())
                .confidence(Math.min(finalConfidence, 0.95))
                .strategyName("VOTE:" + representative.getStrategyName())
                .reason(voteDetail)
                .build();

        log.info("[策略执行] {} 最终: {} | 置信度={} | {}",
                symbol, finalSignal.getAction(),
                String.format("%.2f", finalSignal.getConfidence()), voteDetail);

        try {
            signalRepository.save(finalSignal);
        } catch (Exception e) {
            log.error("[策略执行] {} 信号保存失败: {}", symbol, e.getMessage(), e);
        }

        notifiers.forEach(n -> {
            try {
                n.notify(finalSignal);
            } catch (Exception e) {
                log.error("[策略执行] {} 通知发送失败: {}", symbol, e.getMessage());
            }
        });
    }
}
