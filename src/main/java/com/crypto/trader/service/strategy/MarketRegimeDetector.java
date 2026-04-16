package com.crypto.trader.service.strategy;

import com.crypto.trader.service.indicator.Ta4jIndicatorService.IndicatorSnapshot;

/**
 * 市场状态分类器 — 判断当前处于趋势/震荡/高波动状态。
 *
 * <p>根据市场状态动态调整策略权重：</p>
 * <ul>
 *   <li>TRENDING: 趋势策略(MACD/CHAN)权重高，均值回归(Bollinger)权重低</li>
 *   <li>RANGING: 均值回归(Bollinger)权重高，趋势策略权重低</li>
 *   <li>VOLATILE: 所有策略降低权重，风控优先</li>
 * </ul>
 */
public class MarketRegimeDetector {

    public enum Regime {
        /** 强趋势：ADX≥25, BB带宽正常 */
        TRENDING,
        /** 震荡/盘整：ADX<20, BB带宽窄 */
        RANGING,
        /** 高波动：ATR异常高，BB带宽极宽 */
        VOLATILE
    }

    /**
     * 从技术指标快照判断市场状态。
     */
    public static Regime detect(IndicatorSnapshot snap) {
        if (snap == null) return Regime.RANGING;

        // 高波动检测: ATR 占价格比 > 3% 或 BB 带宽 > 0.08
        if (snap.atrPercent > 3.0 || snap.bbWidth > 0.08) {
            return Regime.VOLATILE;
        }

        // 趋势检测: ADX >= 25
        if (snap.adx14 >= 25) {
            return Regime.TRENDING;
        }

        // 震荡: ADX < 20 或 BB 带宽窄
        if (snap.adx14 < 20 || snap.bbWidth < 0.03) {
            return Regime.RANGING;
        }

        // 过渡区 (ADX 20-25): 偏震荡
        return Regime.RANGING;
    }

    /**
     * 根据市场状态返回策略权重乘数。
     *
     * @param regime       当前市场状态
     * @param strategyName 策略名称
     * @return 权重乘数 (0.0 ~ 2.0)
     */
    public static double getWeight(Regime regime, String strategyName) {
        return switch (regime) {
            case TRENDING -> switch (strategyName) {
                case "MACD" -> 1.5;         // 趋势策略加权
                case "CHAN" -> 1.3;          // 缠论在趋势中有效
                case "GBT-ML" -> 1.2;       // ML 自适应
                case "Bollinger" -> 0.3;     // 均值回归在趋势中危险
                case "Whale" -> 1.0;         // 巨鲸中性
                case "MCP-AI" -> 0.5;        // LLM 不可靠，降权
                default -> 1.0;
            };
            case RANGING -> switch (strategyName) {
                case "Bollinger" -> 1.5;     // 均值回归在震荡中最佳
                case "CHAN" -> 1.2;          // 缠论中枢交易
                case "GBT-ML" -> 1.2;       // ML 自适应
                case "MACD" -> 0.5;          // 趋势策略在震荡中假信号多
                case "Whale" -> 1.0;
                case "MCP-AI" -> 0.5;
                default -> 1.0;
            };
            case VOLATILE -> switch (strategyName) {
                case "GBT-ML" -> 0.8;       // 高波动时所有策略降权
                case "CHAN" -> 0.7;
                case "MACD" -> 0.6;
                case "Bollinger" -> 0.5;     // 均值回归在高波动中最危险
                case "Whale" -> 0.8;
                case "MCP-AI" -> 0.3;
                default -> 0.6;
            };
        };
    }
}
