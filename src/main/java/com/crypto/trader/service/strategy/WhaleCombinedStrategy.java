package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.analyzer.WhaleAnalyzer;
import com.crypto.trader.service.indicator.Ta4jIndicatorService;
import com.crypto.trader.service.indicator.Ta4jIndicatorService.IndicatorSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 巨鲸行为策略（改进版）— 结合 TA4J 全量指标确认。
 *
 * <p>对比旧版改进：</p>
 * <ul>
 *   <li>旧版: 巨鲸积累+MACD偏多=BUY, 固定 0.85 置信度</li>
 *   <li>新版: 巨鲸信号 + MACD + RSI + OBV + ADX 多重确认，动态置信度</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WhaleCombinedStrategy implements TradingStrategy {

    private final WhaleAnalyzer whaleAnalyzer;
    private final Ta4jIndicatorService ta4jService;

    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        log.info("[WhaleCombined] {} 开始分析鲸鱼活动...", symbol);
        int whaleSignal = whaleAnalyzer.analyzeWhaleActivity(symbol);

        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        if (snap == null) {
            log.debug("[WhaleCombined] {} 指标计算失败，返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        String whaleDesc = whaleSignal == 1 ? "积累" : whaleSignal == -1 ? "派发" : "中性";
        String macdDesc = snap.macd > snap.macdSignal ? "偏多" : "偏空";

        log.info("[WhaleCombined] {} 鲸鱼: {} | MACD: {} | RSI={} ADX={} OBV趋势={}",
                symbol, whaleDesc, macdDesc,
                String.format("%.1f", snap.rsi14),
                String.format("%.1f", snap.adx14),
                String.format("%.2f%%", snap.obvSlope5));

        if (whaleSignal == 1 && snap.macd > snap.macdSignal) {
            double confidence = 0.75;
            // RSI 确认: 不在超买区
            if (snap.rsi14 < 65) confidence += 0.05;
            // ADX 趋势确认
            if (snap.adx14 >= 25) confidence += 0.05;
            // OBV 量价配合（量在增长）
            if (snap.obvSlope5 > 0) confidence += 0.05;
            // Stochastic 确认
            if (snap.stochK > snap.stochD && snap.stochK < 80) confidence += 0.05;

            confidence = Math.min(0.95, confidence);

            String reason = String.format("巨鲸积累+MACD偏多 RSI=%.1f ADX=%.1f OBV=%+.2f%%",
                    snap.rsi14, snap.adx14, snap.obvSlope5);
            log.info("[WhaleCombined] {} → BUY (置信度={})", symbol, String.format("%.2f", confidence));

            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.BUY).price(snap.close)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();

        } else if (whaleSignal == -1 && snap.macd < snap.macdSignal) {
            double confidence = 0.75;
            if (snap.rsi14 > 35) confidence += 0.05;
            if (snap.adx14 >= 25) confidence += 0.05;
            if (snap.obvSlope5 < 0) confidence += 0.05;
            if (snap.stochK < snap.stochD && snap.stochK > 20) confidence += 0.05;

            confidence = Math.min(0.95, confidence);

            String reason = String.format("巨鲸派发+MACD偏空 RSI=%.1f ADX=%.1f OBV=%+.2f%%",
                    snap.rsi14, snap.adx14, snap.obvSlope5);
            log.info("[WhaleCombined] {} → SELL (置信度={})", symbol, String.format("%.2f", confidence));

            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.SELL).price(snap.close)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();
        }

        log.debug("[WhaleCombined] {} 鲸鱼与技术面未形成共振 → HOLD", symbol);
        return holdSignal(symbol);
    }

    @Override
    public String getName() { return "WhaleCombined"; }

    private Signal holdSignal(String symbol) {
        return Signal.builder().symbol(symbol).timestamp(Instant.now())
                .action(Signal.Action.HOLD).strategyName(getName()).build();
    }
}
