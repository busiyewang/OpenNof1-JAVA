package com.crypto.trader.service.notifier;

import com.crypto.trader.client.mcp.dto.TimeframeAnalysis;
import com.crypto.trader.model.AnalysisReport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 发送 HTML 格式的分析报告邮件。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisEmailSender {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    private final TemplateEngine templateEngine;

    private final ObjectMapper objectMapper;

    @Value("${crypto.notifier.email.from:}")
    private String from;

    @Value("${crypto.notifier.email.to:}")
    private String to;

    @Value("${crypto.analysis.timezone:Asia/Shanghai}")
    private String timezone;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 发送分析报告 HTML 邮件。
     */
    public void sendAnalysisReport(AnalysisReport report) {
        if (mailSender == null || !StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            log.info("[AnalysisEmail] 邮件未配置，跳过发送: {} {}", report.getSymbol(), report.getReportType());
            return;
        }

        try {
            Context ctx = buildContext(report);
            String html = templateEngine.process("analysis-report", ctx);

            String subject = String.format("[%s分析] %s 市场报告 - %s",
                    report.getReportType() == AnalysisReport.ReportType.WEEKLY ? "周" : "日",
                    report.getSymbol(),
                    report.getCreatedAt().atZone(ZoneId.of(timezone)).format(DATE_FMT));

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("[AnalysisEmail] 分析报告邮件已发送: {} -> {}", subject, to);
        } catch (Exception e) {
            log.error("[AnalysisEmail] 邮件发送失败: {}", e.getMessage(), e);
        }
    }

    private Context buildContext(AnalysisReport report) {
        Context ctx = new Context();
        String dateStr = report.getCreatedAt().atZone(ZoneId.of(timezone)).format(DATE_FMT);

        ctx.setVariable("reportTitle", report.getSymbol() + " 市场分析报告");
        ctx.setVariable("reportDate", dateStr);

        // 趋势
        ctx.setVariable("trendDirectionCn", trendToChinese(report.getTrendDirection()));
        ctx.setVariable("trendColor", trendToColor(report.getTrendDirection()));
        ctx.setVariable("confidence", String.format("%.0f%%", report.getTrendConfidence() * 100));

        // 风险
        ctx.setVariable("riskLevelCn", riskToChinese(report.getRiskAssessment()));
        ctx.setVariable("riskColor", riskToColor(report.getRiskAssessment()));

        // 价格
        ctx.setVariable("priceCurrent", formatPrice(report.getPriceCurrent()));
        ctx.setVariable("priceSupport", report.getPriceSupport() != null ? formatPrice(report.getPriceSupport()) : "-");
        ctx.setVariable("priceResistance", report.getPriceResistance() != null ? formatPrice(report.getPriceResistance()) : "-");

        // 时间框架分析
        ctx.setVariable("timeframes", parseTimeframes(report.getTimeframesSummary()));

        // 链上洞察
        ctx.setVariable("onChainInsights", parseJsonMap(report.getOnChainSummary()));

        // 技术指标
        ctx.setVariable("keyIndicators", parseJsonMap(report.getKeyIndicators()));

        // 展望
        ctx.setVariable("shortTermOutlook", report.getShortTermOutlook() != null ? report.getShortTermOutlook() : "暂无");
        ctx.setVariable("mediumTermOutlook", report.getMediumTermOutlook() != null ? report.getMediumTermOutlook() : "暂无");

        // 风险因子
        ctx.setVariable("riskFactors", parseJsonList(report.getRiskFactors()));

        // AI 分析
        ctx.setVariable("deepseekAnalysis", report.getDeepseekAnalysis());

        // 交易计划
        ctx.setVariable("tradeAction", report.getTradeAction());
        ctx.setVariable("tradeActionCn", actionToChinese(report.getTradeAction()));
        ctx.setVariable("tradeActionColor", actionToColor(report.getTradeAction()));

        // 入场价格
        if (report.getEntryPriceRange() != null) {
            try {
                Map<String, Object> range = objectMapper.readValue(report.getEntryPriceRange(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Object low = range.get("low");
                Object high = range.get("high");
                ctx.setVariable("entryPriceDisplay",
                        String.format("$%s - $%s", formatNum(low), formatNum(high)));
            } catch (Exception e) {
                ctx.setVariable("entryPriceDisplay", "-");
            }
        } else {
            ctx.setVariable("entryPriceDisplay", "-");
        }

        ctx.setVariable("stopLossDisplay", report.getStopLoss() != null ? formatPrice(report.getStopLoss()) : "-");
        ctx.setVariable("tp1Display", report.getTakeProfit1() != null ? formatPrice(report.getTakeProfit1()) : "-");
        ctx.setVariable("tp2Display", report.getTakeProfit2() != null ? formatPrice(report.getTakeProfit2()) : "-");
        ctx.setVariable("positionPercentDisplay",
                report.getPositionPercent() > 0 ? "总资金的 " + report.getPositionPercent() + "%" : "-");

        // 交易计划详情
        if (report.getTradingPlan() != null) {
            try {
                Map<String, Object> plan = objectMapper.readValue(report.getTradingPlan(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                ctx.setVariable("entryCondition", plan.getOrDefault("entryCondition", "暂无"));
                ctx.setVariable("exitCondition", plan.getOrDefault("exitCondition", "暂无"));
                ctx.setVariable("holdDuration", plan.getOrDefault("holdDuration", "-"));
                ctx.setVariable("riskRewardRatio", plan.getOrDefault("riskRewardRatio", "-"));
                Object notes = plan.get("tradingNotes");
                ctx.setVariable("tradingNotes", notes instanceof List ? notes : List.of());
            } catch (Exception e) {
                ctx.setVariable("entryCondition", "暂无");
                ctx.setVariable("exitCondition", "暂无");
                ctx.setVariable("holdDuration", "-");
                ctx.setVariable("riskRewardRatio", "-");
                ctx.setVariable("tradingNotes", List.of());
            }
        } else {
            ctx.setVariable("entryCondition", "暂无");
            ctx.setVariable("exitCondition", "暂无");
            ctx.setVariable("holdDuration", "-");
            ctx.setVariable("riskRewardRatio", "-");
            ctx.setVariable("tradingNotes", List.of());
        }

        return ctx;
    }

    /** 解析多时间框架数据，并添加显示用的格式化字段 */
    private List<Map<String, Object>> parseTimeframes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<TimeframeAnalysis> tfList = objectMapper.readValue(json, new TypeReference<>() {});
            List<Map<String, Object>> result = new ArrayList<>();
            for (TimeframeAnalysis tf : tfList) {
                Map<String, Object> map = new HashMap<>();
                map.put("timeframe", tf.getTimeframe());
                map.put("currentPrice", String.format("%.2f", tf.getCurrentPrice()));
                map.put("priceChangePercent", tf.getPriceChangePercent());
                map.put("priceChangeFormatted", String.format("%+.2f%%", tf.getPriceChangePercent()));
                map.put("macdHistogram", tf.getMacdHistogram());
                map.put("macdStatus", tf.getMacdHistogram() >= 0 ? "多头" : "空头");
                map.put("bollingerPosition", bollingerPosition(tf));
                result.add(map);
            }
            return result;
        } catch (Exception e) {
            log.warn("[AnalysisEmail] 解析时间框架 JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String bollingerPosition(TimeframeAnalysis tf) {
        if (tf.getBollingerMiddle() == 0) return "-";
        double price = tf.getCurrentPrice();
        if (price >= tf.getBollingerUpper()) return "上轨上方";
        if (price > tf.getBollingerMiddle()) return "中轨上方";
        if (price > tf.getBollingerLower()) return "中轨下方";
        return "下轨下方";
    }

    private Map<String, String> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String formatPrice(java.math.BigDecimal price) {
        if (price == null) return "-";
        return "$" + price.stripTrailingZeros().toPlainString();
    }

    private String trendToChinese(AnalysisReport.TrendDirection dir) {
        if (dir == null) return "中性";
        return switch (dir) {
            case STRONGLY_BULLISH -> "强烈看多";
            case BULLISH          -> "看多";
            case NEUTRAL          -> "中性";
            case BEARISH          -> "看空";
            case STRONGLY_BEARISH -> "强烈看空";
        };
    }

    private String trendToColor(AnalysisReport.TrendDirection dir) {
        if (dir == null) return "#f39c12";
        return switch (dir) {
            case STRONGLY_BULLISH, BULLISH -> "#27ae60";
            case NEUTRAL                   -> "#f39c12";
            case BEARISH, STRONGLY_BEARISH -> "#e74c3c";
        };
    }

    private String riskToChinese(AnalysisReport.RiskLevel level) {
        if (level == null) return "中等";
        return switch (level) {
            case LOW      -> "低";
            case MODERATE -> "中等";
            case HIGH     -> "高";
            case EXTREME  -> "极高";
        };
    }

    private String riskToColor(AnalysisReport.RiskLevel level) {
        if (level == null) return "#f39c12";
        return switch (level) {
            case LOW      -> "#27ae60";
            case MODERATE -> "#f39c12";
            case HIGH     -> "#e74c3c";
            case EXTREME  -> "#c0392b";
        };
    }

    private String actionToChinese(String action) {
        if (action == null) return "观望";
        return switch (action.toUpperCase()) {
            case "BUY_LONG"   -> "做多买入";
            case "SELL_SHORT" -> "做空卖出";
            case "CLOSE"      -> "平仓离场";
            case "HOLD"       -> "观望等待";
            default           -> action;
        };
    }

    private String actionToColor(String action) {
        if (action == null) return "#718096";
        return switch (action.toUpperCase()) {
            case "BUY_LONG"   -> "#27ae60";
            case "SELL_SHORT" -> "#e74c3c";
            case "CLOSE"      -> "#f39c12";
            default           -> "#718096";
        };
    }

    private String formatNum(Object value) {
        if (value == null) return "-";
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return String.format("%,.2f", d);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
