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
 * 布林带策略（改进版）— 结合 RSI 超买超卖确认 + Stochastic 双重验证 + ATR 波动过滤。
 *
 * <p>对比旧版改进：</p>
 * <ul>
 *   <li>旧版: 价格>上轨=SELL, 价格<下轨=BUY, 固定 0.6 置信度</li>
 *   <li>新版: 多重验证 — BB突破 + RSI极端 + Stochastic确认 → 动态置信度</li>
 *   <li>增加 BB squeeze 检测：带宽极窄时即将大幅波动，不给信号</li>
 *   <li>增加 CCI 辅助确认超买超卖</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BollingerStrategy implements TradingStrategy {

    private final Ta4jIndicatorService ta4jService;

    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        if (snap == null) {
            log.debug("[Bollinger] {} 指标计算失败（数据不足），返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        log.info("[Bollinger] {} 上={} 中={} 下={} 收={} | BBW={} RSI={} StochK={} CCI={}",
                symbol,
                fmt(snap.bbUpper), fmt(snap.bbMiddle), fmt(snap.bbLower), fmt(snap.close),
                fmt4(snap.bbWidth), fmt(snap.rsi14), fmt(snap.stochK), fmt(snap.cci20));

        // BB squeeze 检测：带宽极窄，即将突破，不适合逆势操作
        if (snap.bbWidth < 0.02) {
            log.info("[Bollinger] {} BB squeeze (带宽={}), 即将突破方向不明 → HOLD",
                    symbol, fmt4(snap.bbWidth));
            return holdSignal(symbol);
        }

        boolean aboveUpper = snap.close > snap.bbUpper;
        boolean belowLower = snap.close < snap.bbLower;

        if (aboveUpper) {
            // 超买检测: BB上轨突破 + RSI/Stochastic/CCI 确认
            double confidence = calcSellConfidence(snap);
            String reason = buildReason("超买", snap);
            log.info("[Bollinger] {} 突破上轨(超买) → SELL (置信度={})", symbol, fmt4(confidence));
            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.SELL).price(snap.close)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();
        }

        if (belowLower) {
            // 超卖检测
            double confidence = calcBuyConfidence(snap);
            String reason = buildReason("超卖", snap);
            log.info("[Bollinger] {} 跌破下轨(超卖) → BUY (置信度={})", symbol, fmt4(confidence));
            return Signal.builder()
                    .symbol(symbol).timestamp(Instant.now())
                    .action(Signal.Action.BUY).price(snap.close)
                    .confidence(confidence).strategyName(getName())
                    .reason(reason).build();
        }

        log.debug("[Bollinger] {} 价格在布林带内 → HOLD", symbol);
        return holdSignal(symbol);
    }

    /**
     * 买入置信度: 基础 0.55, RSI超卖确认 +0.10, Stochastic超卖 +0.08, CCI<-100 +0.07
     */
    private double calcBuyConfidence(IndicatorSnapshot snap) {
        double conf = 0.55;

        // RSI 超卖确认
        if (snap.rsi14 < 30) conf += 0.10;
        else if (snap.rsi14 < 40) conf += 0.05;

        // Stochastic K 超卖确认
        if (snap.stochK < 20) conf += 0.08;
        else if (snap.stochK < 30) conf += 0.04;

        // CCI 极端超卖
        if (snap.cci20 < -100) conf += 0.07;

        // Williams %R 超卖
        if (snap.williamsR14 < -80) conf += 0.05;

        // 量价确认: OBV 上升（资金抄底）
        if (snap.obvSlope5 > 0) conf += 0.05;

        return Math.max(0.40, Math.min(0.95, conf));
    }

    /**
     * 卖出置信度: 对称逻辑。
     */
    private double calcSellConfidence(IndicatorSnapshot snap) {
        double conf = 0.55;

        if (snap.rsi14 > 70) conf += 0.10;
        else if (snap.rsi14 > 60) conf += 0.05;

        if (snap.stochK > 80) conf += 0.08;
        else if (snap.stochK > 70) conf += 0.04;

        if (snap.cci20 > 100) conf += 0.07;

        if (snap.williamsR14 > -20) conf += 0.05;

        // OBV 下降（资金离场）
        if (snap.obvSlope5 < 0) conf += 0.05;

        return Math.max(0.40, Math.min(0.95, conf));
    }

    private String buildReason(String type, IndicatorSnapshot snap) {
        return String.format("BB%s (BBpos=%.2f, BBW=%.4f) RSI=%.1f StochK=%.1f CCI=%.1f WR=%.1f",
                type, snap.bbPosition, snap.bbWidth,
                snap.rsi14, snap.stochK, snap.cci20, snap.williamsR14);
    }

    @Override
    public String getName() { return "Bollinger"; }

    private Signal holdSignal(String symbol) {
        return Signal.builder().symbol(symbol).timestamp(Instant.now())
                .action(Signal.Action.HOLD).strategyName(getName()).build();
    }

    private String fmt(double v) { return String.format("%.2f", v); }
    private String fmt4(double v) { return String.format("%.4f", v); }
}
