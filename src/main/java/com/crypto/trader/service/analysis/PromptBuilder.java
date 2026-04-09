package com.crypto.trader.service.analysis;

import com.crypto.trader.client.mcp.dto.TimeframeAnalysis;
import com.crypto.trader.model.OnChainMetric;
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
     * 构建完整的分析 prompt。
     *
     * @param symbol             交易对
     * @param timeframeAnalyses  各时间框架的技术分析数据
     * @param onChainMetrics     链上指标（按指标名分组）
     * @param currentPrice       当前价格
     */
    public String build(String symbol, List<TimeframeAnalysis> timeframeAnalyses,
                        Map<String, List<OnChainMetric>> onChainMetrics, BigDecimal currentPrice) {
        StringBuilder sb = new StringBuilder();

        // ========== 0. 历史表现反馈（进化核心） ==========
        appendPerformanceFeedback(sb, symbol);

        // ========== 1. 系统指令 ==========
        sb.append("你是一个专业的加密货币分析师。请基于以下数据给出严谨的市场分析。\n");
        sb.append("重要规则：\n");
        sb.append("- 如果数据不足以得出结论，降低置信度而不是强行给出方向\n");
        sb.append("- 支撑位和阻力位必须基于实际的技术分析结果，不要凭空推测\n");
        sb.append("- 明确区分短期（1-3天）和中期（1-2周）的展望\n");
        sb.append("- 风险评估要具体，列出可量化的风险因子\n\n");

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
        }

        // 链上数据
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

        // ========== 3. 输出要求 ==========
        sb.append("\n== 输出要求 ==\n");
        sb.append("请严格按以下 JSON 格式输出分析结果：\n");
        sb.append("{\n");
        sb.append("  \"trendDirection\": \"BULLISH|BEARISH|NEUTRAL|STRONGLY_BULLISH|STRONGLY_BEARISH\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"supportLevel\": 价格数值,\n");
        sb.append("  \"resistanceLevel\": 价格数值,\n");
        sb.append("  \"riskLevel\": \"LOW|MODERATE|HIGH|EXTREME\",\n");
        sb.append("  \"shortTermOutlook\": \"1-3天展望文字\",\n");
        sb.append("  \"mediumTermOutlook\": \"1-2周展望文字\",\n");
        sb.append("  \"riskFactors\": [\"风险1\", \"风险2\"],\n");
        sb.append("  \"keyIndicatorAnalysis\": {\"MACD\": \"分析\", \"BollingerBands\": \"分析\"},\n");
        sb.append("  \"onChainInsights\": {\"整体\": \"分析\"},\n");
        sb.append("  \"reasoning\": \"完整推理过程\"\n");
        sb.append("}\n");

        return sb.toString();
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
