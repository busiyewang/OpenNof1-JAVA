package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.ChanCalculator;
import com.crypto.trader.service.indicator.chan.ChanResult;
import com.crypto.trader.service.indicator.chan.ChanResult.DivergenceType;
import com.crypto.trader.service.indicator.chan.ChanResult.TrendType;
import com.crypto.trader.service.indicator.chan.ChanSignalPoint;
import com.crypto.trader.service.indicator.chan.ChanSignalPoint.PointType;
import com.crypto.trader.service.indicator.chan.ChanZhongshu;
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
 * <h3>信号选取规则</h3>
 * <ol>
 *   <li>多信号共振时提升置信度（如一买+二买同时出现）</li>
 *   <li>趋势背驰信号优先于盘整背驰信号</li>
 *   <li>强分型信号优先于弱分型信号</li>
 *   <li>结合走势类型调整：趋势末端的一买/一卖最可靠</li>
 * </ol>
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
            log.debug("[缠论策略] {} 未检测到买卖点 (走势类型={}, 笔数={}, 中枢={})",
                    symbol, result.getTrendType(), result.getBiList().size(), result.getZhongshuList().size());
            return holdSignal(symbol);
        }

        // 检查多信号共振，提升置信度
        signalPoints = applyConfluenceBoost(signalPoints);

        // 取最优信号（优先趋势背驰 > 高置信度）
        ChanSignalPoint best = selectBestSignal(signalPoints);

        if (best == null) return holdSignal(symbol);

        Signal.Action action = isBuyPoint(best.getPointType()) ? Signal.Action.BUY : Signal.Action.SELL;

        log.info("[缠论策略] {} 信号: {} | 类型={} | 走势={} | 背驰={} | 置信度={:.2f} | 分型强弱={}",
                symbol, action, best.getPointType(), result.getTrendType(),
                best.getDivergenceType(), best.getConfidence(), best.getFractalStrength());

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

    /**
     * 多信号共振加分：同方向的多个买卖点同时出现时，提升最高信号的置信度。
     * 例如一买 + 三买同时出现 → 一买置信度 +0.05
     */
    private List<ChanSignalPoint> applyConfluenceBoost(List<ChanSignalPoint> signalPoints) {
        long buyCount = signalPoints.stream().filter(sp -> isBuyPoint(sp.getPointType())).count();
        long sellCount = signalPoints.stream().filter(sp -> !isBuyPoint(sp.getPointType())).count();

        if (buyCount >= 2 || sellCount >= 2) {
            // 共振加分
            for (ChanSignalPoint sp : signalPoints) {
                boolean isBuy = isBuyPoint(sp.getPointType());
                if ((isBuy && buyCount >= 2) || (!isBuy && sellCount >= 2)) {
                    double boosted = Math.min(sp.getConfidence() + 0.05, 0.95);
                    sp.setConfidence(boosted);
                }
            }
            log.info("[缠论策略] 检测到多信号共振：买信号{}个，卖信号{}个", buyCount, sellCount);
        }

        return signalPoints;
    }

    /**
     * 选取最优信号。优先级：
     * 1. 趋势背驰信号 > 盘整背驰信号 > 无背驰信号
     * 2. 同等背驰类型下，取置信度最高的
     */
    private ChanSignalPoint selectBestSignal(List<ChanSignalPoint> signalPoints) {
        return signalPoints.stream()
                .max(Comparator
                        .comparingInt((ChanSignalPoint sp) -> divergenceTypePriority(sp.getDivergenceType()))
                        .thenComparingDouble(ChanSignalPoint::getConfidence))
                .orElse(null);
    }

    private int divergenceTypePriority(DivergenceType type) {
        if (type == null) return 0;
        return switch (type) {
            case TREND_DIVERGENCE -> 2;
            case CONSOLIDATION_DIVERGENCE -> 1;
            case NONE -> 0;
        };
    }

    private boolean isBuyPoint(PointType type) {
        return type == PointType.BUY_1 || type == PointType.BUY_2 || type == PointType.BUY_3;
    }

    private String buildReason(ChanSignalPoint point, ChanResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(point.getPointType()).append("] ").append(point.getDescription());

        // 走势类型
        sb.append(" | 走势=").append(trendTypeChinese(result.getTrendType()));

        // 中枢信息
        if (!result.getZhongshuList().isEmpty()) {
            ChanZhongshu lastZh = result.getZhongshuList().get(result.getZhongshuList().size() - 1);
            sb.append(String.format(" | 最近中枢[%.2f-%.2f]", lastZh.getZd(), lastZh.getZg()));
        }

        sb.append(String.format(" | 笔=%d 线段=%d 中枢=%d",
                result.getBiList().size(), result.getSegments().size(), result.getZhongshuList().size()));

        // 分型强弱
        if (point.getFractalStrength() != null) {
            sb.append(" | 分型=").append(point.getFractalStrength() == com.crypto.trader.service.indicator.chan.ChanFractal.Strength.STRONG ? "强" : "弱");
        }

        if (sb.length() > 490) {
            sb.setLength(490);
            sb.append("...");
        }
        return sb.toString();
    }

    private String trendTypeChinese(TrendType type) {
        if (type == null) return "未知";
        return switch (type) {
            case TREND_UP -> "上涨趋势";
            case TREND_DOWN -> "下跌趋势";
            case CONSOLIDATION -> "盘整";
            case UNKNOWN -> "未知";
        };
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
