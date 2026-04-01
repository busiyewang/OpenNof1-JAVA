package com.crypto.trader.service.analysis;

import com.crypto.trader.client.mcp.dto.TimeframeAnalysis;
import com.crypto.trader.model.OnChainMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 构建 DeepSeek 分析用的 Prompt，包含多时间框架数据和链上指标。
 */
@Component
public class PromptBuilder {

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

        sb.append("=== ").append(symbol).append(" 市场分析数据 ===\n\n");
        sb.append("当前价格: ").append(currentPrice).append("\n\n");

        // 1. 多时间框架技术分析
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

        // 2. 链上数据
        sb.append("\n== 链上数据指标 ==\n");
        for (Map.Entry<String, List<OnChainMetric>> entry : onChainMetrics.entrySet()) {
            String metricName = entry.getKey();
            List<OnChainMetric> values = entry.getValue();
            sb.append("\n--- ").append(metricName).append(" ---\n");
            if (values.isEmpty()) {
                sb.append("暂无数据\n");
                continue;
            }
            // 显示最近 5 个数据点
            int showCount = Math.min(5, values.size());
            for (int i = 0; i < showCount; i++) {
                OnChainMetric m = values.get(i);
                sb.append(m.getTimestamp()).append(": ").append(m.getValue()).append("\n");
            }
            // 趋势描述
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

        sb.append("\n请基于以上多时间框架技术分析和链上数据，给出完整的市场分析报告（JSON格式）。");
        return sb.toString();
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
