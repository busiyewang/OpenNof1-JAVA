package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.Ta4jIndicatorService;
import com.crypto.trader.service.indicator.Ta4jIndicatorService.IndicatorSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MACD 策略（改进版）— 结合 RSI 过滤 + ADX 趋势强度 + ATR 动态置信度。
 *
 * <p>对比旧版改进：</p>
 * <ul>
 *   <li>旧版: 金叉=BUY, 死叉=SELL, 固定 0.7 置信度</li>
 *   <li>新版: 金叉+RSI未超买+ADX确认趋势 → BUY，置信度根据趋势强度动态调整</li>
 *   <li>增加 RSI 背离检测：MACD金叉但RSI>75 → 降低置信度（可能假突破）</li>
 *   <li>增加 ADX 过滤：ADX<20 表示横盘，不适合趋势策略 → HOLD</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MacdStrategy implements TradingStrategy {

    private final Ta4jIndicatorService ta4jService;

    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        if (snap == null) {
            log.debug("[MACD] {} 指标计算失败（数据不足），返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        log.info("[MACD] {} MACD={} Signal={} Hist={} | RSI14={} ADX={}",
                symbol,
                fmt(snap.macd), fmt(snap.macdSignal), fmt(snap.macdHistogram),
                fmt(snap.rsi14), fmt(snap.adx14));

        boolean goldenCross = snap.macd > snap.macdSignal && snap.macdHistogram > 0;
        boolean deathCross = snap.macd < snap.macdSignal && snap.macdHistogram < 0;

        // ADX < 20: 横盘市场，趋势策略不适用
        if (snap.adx14 > 0 && snap.adx14 < 20) {
            log.info("[MACD] {} ADX={} < 20，横盘市场，趋势策略不适用 → HOLD", symbol, fmt(snap.adx14));
            return holdSignal(symbol);
        }

        double price = snap.close;

        if (goldenCross) {
            double confidence = calcBuyConfidence(snap);
            String reason = buildReason("金叉", snap);
            log.info("[MACD] {} 金叉 → BUY (置信度={}, RSI={}, ADX={})",
                    symbol, fmt(confidence), fmt(snap.rsi14), fmt(snap.adx14));
            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.BUY).price(price)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();
        }

        if (deathCross) {
            double confidence = calcSellConfidence(snap);
            String reason = buildReason("死叉", snap);
            log.info("[MACD] {} 死叉 → SELL (置信度={}, RSI={}, ADX={})",
                    symbol, fmt(confidence), fmt(snap.rsi14), fmt(snap.adx14));
            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.SELL).price(price)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();
        }

        log.debug("[MACD] {} 无明确信号 → HOLD", symbol);
        return holdSignal(symbol);
    }

    /**
     * 买入置信度动态计算。
     * 基础 0.60, ADX强趋势 +0.10, RSI适中 +0.05, Histogram加速 +0.05
     * RSI超买(>75)惩罚 -0.15
     */
    private double calcBuyConfidence(IndicatorSnapshot snap) {
        double conf = 0.60;

        // ADX 趋势强度加成
        if (snap.adx14 >= 30) conf += 0.10;
        else if (snap.adx14 >= 25) conf += 0.05;

        // RSI 合理区间加成
        if (snap.rsi14 >= 40 && snap.rsi14 <= 60) conf += 0.05;
        // RSI 超买惩罚（金叉但已超买 = 可能假突破）
        if (snap.rsi14 > 75) conf -= 0.15;

        // MACD Histogram 加速
        if (snap.macdHistogram > 0 && snap.macd > 0) conf += 0.05;

        // OBV 量价配合
        if (snap.obvSlope5 > 0) conf += 0.05;

        return Math.max(0.35, Math.min(0.95, conf));
    }

    /**
     * 卖出置信度动态计算。
     */
    private double calcSellConfidence(IndicatorSnapshot snap) {
        double conf = 0.60;

        if (snap.adx14 >= 30) conf += 0.10;
        else if (snap.adx14 >= 25) conf += 0.05;

        if (snap.rsi14 >= 40 && snap.rsi14 <= 60) conf += 0.05;
        // RSI 超卖惩罚（死叉但已超卖 = 可能假跌破）
        if (snap.rsi14 < 25) conf -= 0.15;

        if (snap.macdHistogram < 0 && snap.macd < 0) conf += 0.05;

        if (snap.obvSlope5 < 0) conf += 0.05;

        return Math.max(0.35, Math.min(0.95, conf));
    }

    private String buildReason(String crossType, IndicatorSnapshot snap) {
        return String.format("MACD%s (MACD=%.4f, Hist=%.4f) RSI=%.1f ADX=%.1f OBV趋势=%s",
                crossType, snap.macd, snap.macdHistogram,
                snap.rsi14, snap.adx14,
                snap.obvSlope5 > 0 ? "上升" : "下降");
    }

    @Override
    public String getName() { return "MACD"; }

    private Signal holdSignal(String symbol) {
        return Signal.builder().symbol(symbol).timestamp(Instant.now())
                .action(Signal.Action.HOLD).strategyName(getName()).build();
    }

    private String fmt(double v) { return String.format("%.4f", v); }
}
