package com.crypto.trader.service.analysis;

import com.crypto.trader.client.mcp.dto.TimeframeAnalysis;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.chan.*;
import com.crypto.trader.service.ml.MlPrediction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 构建 DeepSeek 分析用的 Prompt，包含多时间框架数据、链上指标和历史表现反馈。
 *
 * <p>Prompt 进化机制：每次分析都会注入历史预测的评分和错误教训，
 * 使 AI 能从过去的错误中学习，逐步提升预测准确率。</p>
 */
@Component
public class PromptBuilder {

    @Autowired(required = false)
    private PredictionScorerService predictionScorerService;

    /**
     * 构建完整的分析 prompt（含策略结论、缠论分析和 ML 预测）。
     */
    public String build(String symbol, List<TimeframeAnalysis> timeframeAnalyses,
                        Map<String, List<OnChainMetric>> onChainMetrics,
                        Map<String, List<OnChainMetric>> marketMetrics,
                        BigDecimal currentPrice,
                        List<Signal> strategySignals, ChanResult chanResult,
                        MlPrediction mlPrediction) {
        StringBuilder sb = new StringBuilder();

        // ========== 0. 历史表现反馈（进化核心） ==========
        appendPerformanceFeedback(sb, symbol);

        // ========== 1. 系统指令 ==========
        sb.append("你是一个专业的加密货币分析师。请基于以下数据给出严谨的市场分析。\n");
        sb.append("重要规则：\n");
        sb.append("- 下面提供了多个策略引擎的独立判断结论，请综合参考但不要盲目跟随\n");
        sb.append("- 当多个策略方向一致时，可适当提高置信度；方向矛盾时，需给出更审慎的判断\n");
        sb.append("- 缠论分析提供了笔、中枢、走势类型和买卖点信息，请结合技术指标综合判断\n");
        sb.append("- 如果数据不足以得出结论，降低置信度而不是强行给出方向\n");
        sb.append("- 支撑位和阻力位必须基于实际的技术分析结果（如中枢上下沿、布林带），不要凭空推测\n");
        sb.append("- 明确区分短期（1-3天）和中期（1-2周）的展望\n");
        sb.append("- 风险评估要具体，列出可量化的风险因子\n");
        sb.append("- 重点关注以下指标组合信号：\n");
        sb.append("  * RSI+KDJ 双重超买超卖确认\n");
        sb.append("  * ADX 趋势强度判断（是否适合趋势跟踪策略）\n");
        sb.append("  * ATR 波动率评估（用于止损止盈距离计算）\n");
        sb.append("  * OBV 量价配合度（量价背离=趋势不健康）\n");
        sb.append("  * CCI+WilliamsR 作为辅助超买超卖确认\n\n");

        // ========== 2. 市场数据 ==========
        sb.append("=== ").append(symbol).append(" 市场分析数据 ===\n\n");
        sb.append("当前价格: ").append(currentPrice).append("\n\n");

        // 多时间框架技术分析
        sb.append("== 多时间框架技术分析 ==\n");
        for (TimeframeAnalysis tf : timeframeAnalyses) {
            sb.append("\n--- ").append(tf.getTimeframe()).append(" 周期 ---\n");
            sb.append("价格: ").append(formatDouble(tf.getCurrentPrice())).append("\n");
            sb.append("涨跌幅: ").append(formatPercent(tf.getPriceChangePercent())).append("\n");
            sb.append("成交量: ").append(formatDouble(tf.getVolume())).append("\n");
            sb.append("成交量变化: ").append(formatPercent(tf.getVolumeChangePercent())).append("\n");

            sb.append("MACD: value=").append(formatDouble(tf.getMacdValue()))
              .append(", signal=").append(formatDouble(tf.getMacdSignal()))
              .append(", histogram=").append(formatDouble(tf.getMacdHistogram())).append("\n");

            sb.append("布林带: upper=").append(formatDouble(tf.getBollingerUpper()))
              .append(", middle=").append(formatDouble(tf.getBollingerMiddle()))
              .append(", lower=").append(formatDouble(tf.getBollingerLower())).append("\n");

            // TA4J 新增指标
            sb.append("RSI: RSI14=").append(formatDouble(tf.getRsi14()))
              .append(", RSI7=").append(formatDouble(tf.getRsi7()))
              .append("  (>70超买, <30超卖)\n");

            sb.append("ATR: ATR14=").append(formatDouble(tf.getAtr14()))
              .append(", 波动率=").append(formatPercent(tf.getAtrPercent()))
              .append("  (衡量波动幅度)\n");

            sb.append("ADX: ").append(formatDouble(tf.getAdx14()))
              .append("  (<20横盘, 20-40趋势形成, >40强趋势)\n");

            sb.append("KDJ: K=").append(formatDouble(tf.getStochK()))
              .append(", D=").append(formatDouble(tf.getStochD()))
              .append("  (>80超买, <20超卖, K上穿D=金叉)\n");

            sb.append("CCI: ").append(formatDouble(tf.getCci20()))
              .append("  (>100超买, <-100超卖)\n");

            sb.append("OBV趋势: ").append(formatPercent(tf.getObvSlope5()))
              .append("  (量价背离=趋势可能反转)\n");

            sb.append("WilliamsR: ").append(formatDouble(tf.getWilliamsR14()))
              .append("  (>-20超买, <-80超卖)\n");
        }

        // ========== 3. 策略引擎结论 ==========
        appendStrategySignals(sb, strategySignals);

        // ========== 3.5 XGBoost ML 模型预测 ==========
        appendMlPrediction(sb, mlPrediction);

        // ========== 4. 缠论详细分析 ==========
        appendChanAnalysis(sb, chanResult);

        // ========== 5. 市场情绪数据 ==========
        appendMarketData(sb, marketMetrics);

        // ========== 6. 链上数据 ==========
        sb.append("\n== 链上数据指标 ==\n");
        for (Map.Entry<String, List<OnChainMetric>> entry : onChainMetrics.entrySet()) {
            String metricName = entry.getKey();
            List<OnChainMetric> values = entry.getValue();
            sb.append("\n--- ").append(metricName).append(" ---\n");
            if (values.isEmpty()) {
                sb.append("暂无数据\n");
                continue;
            }
            int showCount = Math.min(5, values.size());
            for (int i = 0; i < showCount; i++) {
                OnChainMetric m = values.get(i);
                sb.append(m.getTimestamp()).append(": ").append(m.getValue()).append("\n");
            }
            if (values.size() >= 2) {
                BigDecimal latest = values.get(0).getValue();
                BigDecimal previous = values.get(values.size() - 1).getValue();
                if (latest != null && previous != null && previous.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal change = latest.subtract(previous)
                            .divide(previous.abs().max(BigDecimal.ONE), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    sb.append("趋势: ").append(change.compareTo(BigDecimal.ZERO) > 0 ? "上升" : "下降")
                      .append(" ").append(change.abs()).append("%\n");
                }
            }
        }

        // ========== 7. 输出要求 ==========
        sb.append("\n== 输出要求 ==\n");
        sb.append("请综合以上所有数据，给出【具体可执行的交易计划】，严格按以下 JSON 格式输出：\n\n");
        sb.append("重要：交易计划必须明确告诉用户「现在该做什么、在哪个价格买、在哪个价格卖、亏了怎么办」。\n");
        sb.append("如果判断应该观望，action 填 HOLD 并说明等待什么条件再入场。\n\n");
        sb.append("{\n");
        sb.append("  \"trendDirection\": \"BULLISH|BEARISH|NEUTRAL|STRONGLY_BULLISH|STRONGLY_BEARISH\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"supportLevel\": 价格数值,\n");
        sb.append("  \"resistanceLevel\": 价格数值,\n");
        sb.append("  \"riskLevel\": \"LOW|MODERATE|HIGH|EXTREME\",\n");
        sb.append("  \"shortTermOutlook\": \"1-3天展望\",\n");
        sb.append("  \"mediumTermOutlook\": \"1-2周展望\",\n");
        sb.append("  \"riskFactors\": [\"风险1\", \"风险2\"],\n");
        sb.append("  \"keyIndicatorAnalysis\": {\"MACD\": \"分析\", \"BollingerBands\": \"分析\", \"缠论\": \"分析\"},\n");
        sb.append("  \"onChainInsights\": {\"整体\": \"分析\"},\n");
        sb.append("  \"reasoning\": \"完整推理过程\",\n");
        sb.append("\n");
        sb.append("  // ====== 交易计划（核心，必须填写） ======\n");
        sb.append("  \"action\": \"BUY_LONG|SELL_SHORT|HOLD|CLOSE\",\n");
        sb.append("  \"entryPriceLow\": 建议入场价格下限（挂单价）,\n");
        sb.append("  \"entryPriceHigh\": 建议入场价格上限（市价追入的最高可接受价）,\n");
        sb.append("  \"stopLoss\": 止损价格（必须设置，亏损超过此价立即卖出）,\n");
        sb.append("  \"takeProfit1\": 止盈目标1（保守目标，到达后可卖出50%仓位）,\n");
        sb.append("  \"takeProfit2\": 止盈目标2（激进目标，到达后全部卖出）,\n");
        sb.append("  \"positionPercent\": 建议仓位占总资金百分比（10-100的整数）,\n");
        sb.append("  \"entryCondition\": \"入场条件：描述在什么情况下执行买入，例如'价格回调到81500-82000区间时分批买入'\",\n");
        sb.append("  \"exitCondition\": \"出场条件：描述在什么情况下卖出，例如'价格触及85000或跌破80500时卖出'\",\n");
        sb.append("  \"holdDuration\": \"预计持仓时间，例如'1-3天'或'观望至明日'\",\n");
        sb.append("  \"riskRewardRatio\": \"风险收益比，例如'1:2.5（亏1500赚3750）'\",\n");
        sb.append("  \"tradingNotes\": [\"注意事项1（如：不要追高）\", \"注意事项2（如：分批建仓）\"]\n");
        sb.append("}\n\n");
        sb.append("注意：\n");
        sb.append("- 所有价格必须是具体数值，不能用'大约'、'附近'等模糊表述\n");
        sb.append("- 止损必须设置，这是保护资金的底线\n");
        sb.append("- positionPercent 根据置信度和风险等级调整：高置信度+低风险=50-80%, 低置信度+高风险=10-20%\n");
        sb.append("- 如果 action=HOLD，入场价格和止损止盈可以填0，但必须在 entryCondition 说明等待什么信号再入场\n");

        return sb.toString();
    }

    /**
     * 兼容旧调用（含策略和缠论，无 ML 预测）。
     */
    public String build(String symbol, List<TimeframeAnalysis> timeframeAnalyses,
                        Map<String, List<OnChainMetric>> onChainMetrics,
                        Map<String, List<OnChainMetric>> marketMetrics,
                        BigDecimal currentPrice,
                        List<Signal> strategySignals, ChanResult chanResult) {
        return build(symbol, timeframeAnalyses, onChainMetrics, marketMetrics, currentPrice,
                strategySignals, chanResult, null);
    }

    /**
     * 兼容旧调用（无策略结论和市场数据）。
     */
    public String build(String symbol, List<TimeframeAnalysis> timeframeAnalyses,
                        Map<String, List<OnChainMetric>> onChainMetrics, BigDecimal currentPrice) {
        return build(symbol, timeframeAnalyses, onChainMetrics, Map.of(), currentPrice,
                List.of(), null, null);
    }

    // =========================================================================
    // 策略引擎结论渲染
    // =========================================================================

    private void appendStrategySignals(StringBuilder sb, List<Signal> signals) {
        if (signals == null || signals.isEmpty()) return;

        sb.append("\n== 策略引擎实时结论 ==\n");
        sb.append("以下是各独立策略引擎对当前行情的判断（基于最新1h K线数据）：\n\n");

        long buyCount = 0, sellCount = 0, holdCount = 0;

        for (Signal signal : signals) {
            String action = signal.getAction() != null ? signal.getAction().name() : "HOLD";
            sb.append("- ").append(signal.getStrategyName()).append(": ")
              .append(action);

            if (signal.getAction() != Signal.Action.HOLD) {
                sb.append(" | 置信度=").append(String.format("%.0f%%", signal.getConfidence() * 100));
                if (signal.getPrice() > 0) {
                    sb.append(" | 价格=").append(formatDouble(signal.getPrice()));
                }
            }
            if (signal.getReason() != null && !signal.getReason().isBlank()) {
                // 截断过长的 reason
                String reason = signal.getReason().length() > 150
                        ? signal.getReason().substring(0, 150) + "..."
                        : signal.getReason();
                sb.append(" | 原因: ").append(reason);
            }
            sb.append("\n");

            if (signal.getAction() == Signal.Action.BUY) buyCount++;
            else if (signal.getAction() == Signal.Action.SELL) sellCount++;
            else holdCount++;
        }

        sb.append("\n策略共识: BUY=").append(buyCount)
          .append(" SELL=").append(sellCount)
          .append(" HOLD=").append(holdCount);

        if (buyCount > sellCount && buyCount > holdCount) {
            sb.append(" → 多数策略偏多");
        } else if (sellCount > buyCount && sellCount > holdCount) {
            sb.append(" → 多数策略偏空");
        } else if (buyCount > 0 && sellCount > 0) {
            sb.append(" → 策略信号矛盾，需谨慎判断");
        } else {
            sb.append(" → 无明确方向");
        }
        sb.append("\n");
    }

    // =========================================================================
    // XGBoost ML 模型预测渲染
    // =========================================================================

    private void appendMlPrediction(StringBuilder sb, MlPrediction prediction) {
        if (prediction == null) return;

        sb.append("\n== XGBoost 专用预测模型结果 ==\n");
        sb.append("（基于28维量化特征训练的机器学习模型，非LLM推测）\n\n");
        sb.append("预测方向: ").append(prediction.getDirection()).append("\n");
        sb.append("模型置信度: ").append(String.format("%.1f%%", prediction.getConfidence() * 100)).append("\n");

        float[] probs = prediction.getProbabilities();
        if (probs != null && probs.length == 3) {
            sb.append("概率分布: 下跌=").append(String.format("%.1f%%", probs[0] * 100))
              .append(", 横盘=").append(String.format("%.1f%%", probs[1] * 100))
              .append(", 上涨=").append(String.format("%.1f%%", probs[2] * 100)).append("\n");
        }

        sb.append("\n重要提示: 此模型基于历史数据量化训练，其预测结果应作为核心参考依据。\n");
        sb.append("当 ML 模型与技术指标方向一致时，可提高置信度；方向矛盾时，需更审慎判断。\n");
    }

    // =========================================================================
    // 缠论分析渲染
    // =========================================================================

    private void appendChanAnalysis(StringBuilder sb, ChanResult chanResult) {
        if (chanResult == null) return;

        sb.append("\n== 缠论分析 ==\n");

        // 走势类型
        sb.append("走势类型: ").append(trendTypeChinese(chanResult.getTrendType())).append("\n");
        sb.append("背驰状态: ").append(divergenceTypeChinese(chanResult.getDivergenceType())).append("\n");

        // 笔的概况
        sb.append("笔数: ").append(chanResult.getBiList().size());
        if (!chanResult.getBiList().isEmpty()) {
            ChanBi lastBi = chanResult.getBiList().get(chanResult.getBiList().size() - 1);
            sb.append(" | 最新笔: ").append(lastBi.getDirection() == ChanBi.Direction.UP ? "向上" : "向下")
              .append(" ").append(lastBi.getStartPrice()).append("→").append(lastBi.getEndPrice());
        }
        sb.append("\n");

        // 中枢信息
        sb.append("中枢数: ").append(chanResult.getZhongshuList().size()).append("\n");
        for (int i = 0; i < chanResult.getZhongshuList().size(); i++) {
            ChanZhongshu zh = chanResult.getZhongshuList().get(i);
            sb.append(String.format("  中枢%d: 上沿ZG=%.2f, 下沿ZD=%.2f, 最高GG=%.2f, 最低DD=%.2f, 中心=%.2f, 包含%d笔\n",
                    i + 1, zh.getZg(), zh.getZd(), zh.getGg(), zh.getDd(),
                    zh.getCenter(), zh.getBiList().size()));
        }

        // 线段信息
        if (!chanResult.getSegments().isEmpty()) {
            sb.append("线段数: ").append(chanResult.getSegments().size()).append("\n");
            ChanSegment lastSeg = chanResult.getSegments().get(chanResult.getSegments().size() - 1);
            sb.append("  最新线段: ").append(lastSeg.getDirection() == ChanSegment.Direction.UP ? "上升" : "下降")
              .append(" ").append(lastSeg.getStartPrice()).append("→").append(lastSeg.getEndPrice())
              .append(", 包含").append(lastSeg.getBiList().size()).append("笔\n");
        }

        // 买卖点信号
        if (!chanResult.getSignalPoints().isEmpty()) {
            sb.append("缠论买卖点信号:\n");
            for (ChanSignalPoint sp : chanResult.getSignalPoints()) {
                sb.append(String.format("  - %s: 价格=%.2f, 置信度=%.0f%%, %s\n",
                        sp.getPointType(), sp.getPrice(),
                        sp.getConfidence() * 100, sp.getDescription()));
            }
        } else {
            sb.append("缠论买卖点: 当前无买卖点信号\n");
        }

        // 中枢对支撑阻力位的参考
        if (!chanResult.getZhongshuList().isEmpty()) {
            ChanZhongshu lastZh = chanResult.getZhongshuList().get(chanResult.getZhongshuList().size() - 1);
            sb.append(String.format("缠论参考位: 支撑=%.2f(中枢下沿ZD), 阻力=%.2f(中枢上沿ZG)\n",
                    lastZh.getZd(), lastZh.getZg()));
        }
    }

    // =========================================================================
    // 市场情绪数据渲染
    // =========================================================================

    private void appendMarketData(StringBuilder sb, Map<String, List<OnChainMetric>> marketMetrics) {
        if (marketMetrics == null || marketMetrics.isEmpty()) return;

        boolean hasData = marketMetrics.values().stream().anyMatch(list -> !list.isEmpty());
        if (!hasData) return;

        sb.append("\n== 市场情绪数据 ==\n");

        // 资金费率
        appendSingleMetric(sb, marketMetrics, "funding_rate", "资金费率",
                "正值=多头拥挤(回调风险), 负值=空头拥挤, |值|>0.1%=极端");
        appendSingleMetric(sb, marketMetrics, "funding_rate_next", "下期预测费率", null);

        // 持仓量
        appendSingleMetric(sb, marketMetrics, "open_interest", "合约持仓量(币)",
                "OI升+价升=趋势健康, OI升+价跌=空头发力, OI降+价升=上涨不持久");
        appendSingleMetric(sb, marketMetrics, "open_interest_usdt", "合约持仓量(USDT)", null);

        // 恐惧贪婪
        appendSingleMetric(sb, marketMetrics, "fear_greed_index", "恐惧贪婪指数",
                "0-24=极度恐惧(常见底部), 25-49=恐惧, 50-74=贪婪, 75-100=极度贪婪(常见顶部)");

        // 爆仓
        appendSingleMetric(sb, marketMetrics, "liquidation_long_usd", "多头爆仓(USD)",
                "大量多头爆仓=市场急跌后可能见底");
        appendSingleMetric(sb, marketMetrics, "liquidation_short_usd", "空头爆仓(USD)",
                "大量空头爆仓=市场急涨后可能见顶");
        appendSingleMetric(sb, marketMetrics, "liquidation_long_short_ratio", "多空爆仓比",
                ">1=多头爆仓多于空头, <1=空头爆仓多于多头");
    }

    private void appendSingleMetric(StringBuilder sb, Map<String, List<OnChainMetric>> metrics,
                                     String metricName, String displayName, String explanation) {
        List<OnChainMetric> values = metrics.get(metricName);
        if (values == null || values.isEmpty()) return;

        OnChainMetric latest = values.get(0);
        sb.append("  ").append(displayName).append(": ").append(latest.getValue());
        if (explanation != null) {
            sb.append("  (").append(explanation).append(")");
        }
        sb.append("\n");
    }

    private String trendTypeChinese(ChanResult.TrendType type) {
        if (type == null) return "未知";
        return switch (type) {
            case TREND_UP -> "上涨趋势（≥2个中枢依次抬高）";
            case TREND_DOWN -> "下跌趋势（≥2个中枢依次降低）";
            case CONSOLIDATION -> "盘整（1个中枢内震荡）";
            case UNKNOWN -> "未知（数据不足）";
        };
    }

    private String divergenceTypeChinese(ChanResult.DivergenceType type) {
        if (type == null) return "无背驰";
        return switch (type) {
            case TREND_DIVERGENCE -> "趋势背驰（力度衰减，可能反转）";
            case CONSOLIDATION_DIVERGENCE -> "盘整背驰（可能继续震荡）";
            case NONE -> "无背驰";
        };
    }

    /**
     * 注入历史预测表现反馈，这是 Prompt 进化的核心。
     *
     * <p>包含：</p>
     * <ul>
     *   <li>过去30天的预测准确率和平均得分</li>
     *   <li>最近的典型错误案例</li>
     *   <li>针对性的改进提示</li>
     * </ul>
     */
    private void appendPerformanceFeedback(StringBuilder sb, String symbol) {
        if (predictionScorerService == null) return;

        String feedback = predictionScorerService.getPerformanceSummary(symbol);
        if (feedback == null || feedback.isBlank()) return;

        sb.append("== 历史预测表现反馈 ==\n");
        sb.append("以下是你过去的预测评分结果，请认真参考并改进：\n\n");
        sb.append(feedback);
        sb.append("\n");
    }

    private String formatDouble(double value) {
        if (value == 0) return "0";
        if (Math.abs(value) > 1) return String.format("%.2f", value);
        return String.format("%.6f", value);
    }

    private String formatPercent(double value) {
        return String.format("%.2f%%", value);
    }
}
