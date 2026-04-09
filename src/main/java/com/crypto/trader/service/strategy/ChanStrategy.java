package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.ChanCalculator;
import com.crypto.trader.service.indicator.chan.ChanResult;
import com.crypto.trader.service.indicator.chan.ChanSignalPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 缠论交易策略。
 *
 * <p>基于缠论的笔、线段、中枢和背驰判断，识别三类买卖点并生成交易信号。</p>
 *
 * <p>买卖点优先级（置信度从高到低）：一买/一卖 > 三买/三卖 > 二买/二卖</p>
 */
@Component
@Slf4j
public class ChanStrategy implements TradingStrategy {

    @Autowired
    private ChanCalculator chanCalculator;

    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        ChanResult result = chanCalculator.calculate(klines);
        if (result == null) {
            log.debug("[缠论策略] {} K线数据不足，返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        List<ChanSignalPoint> signalPoints = result.getSignalPoints();
        if (signalPoints.isEmpty()) {
            log.debug("[缠论策略] {} 未检测到买卖点，返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        // 取置信度最高的信号
        ChanSignalPoint best = signalPoints.stream()
                .max(Comparator.comparingDouble(ChanSignalPoint::getConfidence))
                .orElse(null);

        if (best == null) return holdSignal(symbol);

        Signal.Action action = isBuyPoint(best.getPointType()) ? Signal.Action.BUY : Signal.Action.SELL;

        log.info("[缠论策略] {} 检测到 {} 信号: type={}, price={}, confidence={}, desc={}",
                symbol, action, best.getPointType(), best.getPrice(),
                best.getConfidence(), best.getDescription());

        // 附加上下文信息
        String reason = buildReason(best, result);

        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(action)
                .price(best.getPrice().doubleValue())
                .confidence(best.getConfidence())
                .strategyName(getName())
                .reason(reason)
                .build();
    }

    private boolean isBuyPoint(ChanSignalPoint.PointType type) {
        return type == ChanSignalPoint.PointType.BUY_1
                || type == ChanSignalPoint.PointType.BUY_2
                || type == ChanSignalPoint.PointType.BUY_3;
    }

    private String buildReason(ChanSignalPoint point, ChanResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(point.getPointType()).append("] ").append(point.getDescription());

        // 补充中枢信息
        if (!result.getZhongshuList().isEmpty()) {
            var lastZh = result.getZhongshuList().get(result.getZhongshuList().size() - 1);
            sb.append(String.format(" | 最近中枢[%.2f-%.2f]", lastZh.getZd(), lastZh.getZg()));
        }

        sb.append(String.format(" | 笔数=%d, 线段=%d, 中枢=%d",
                result.getBiList().size(), result.getSegments().size(), result.getZhongshuList().size()));

        // 截断以适应数据库字段长度
        if (sb.length() > 490) {
            sb.setLength(490);
            sb.append("...");
        }

        return sb.toString();
    }

    private Signal holdSignal(String symbol) {
        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(Signal.Action.HOLD)
                .strategyName(getName())
                .build();
    }

    @Override
    public String getName() {
        return "CHAN";
    }
}
