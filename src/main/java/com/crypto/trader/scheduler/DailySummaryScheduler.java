package com.crypto.trader.scheduler;

import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.AnalysisReportRepository;
import com.crypto.trader.repository.PredictionScoreRepository;
import com.crypto.trader.repository.SignalRepository;
import com.crypto.trader.service.notifier.Notifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每日消息通知调度器。
 *
 * <p>每天 9:00 和 13:00 发送综合消息通知（邮件 + Telegram），包含：</p>
 * <ul>
 *   <li>最近时段的策略信号摘要</li>
 *   <li>最新 AI 分析报告要点</li>
 *   <li>预测准确率统计</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DailySummaryScheduler {

    private final SignalRepository signalRepository;

    private final AnalysisReportRepository reportRepository;

    private final PredictionScoreRepository scoreRepository;

    private final List<Notifier> notifiers;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    @Value("${crypto.analysis.timezone:Asia/Shanghai}")
    private String timezone;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 早间通知（9:00）：覆盖昨日 13:00 ~ 今日 9:00 的信号。
     */
    @Scheduled(cron = "${crypto.summary.morning-cron:0 0 9 * * ?}", zone = "${crypto.analysis.timezone:Asia/Shanghai}")
    public void morningSummary() {
        sendSummary("早间", 20); // 回顾过去20小时
    }

    /**
     * 午间通知（13:00）：覆盖今日 9:00 ~ 13:00 的信号。
     */
    @Scheduled(cron = "${crypto.summary.noon-cron:0 0 13 * * ?}", zone = "${crypto.analysis.timezone:Asia/Shanghai}")
    public void noonSummary() {
        sendSummary("午间", 4); // 回顾过去4小时
    }

    private void sendSummary(String label, int lookbackHours) {
        log.info("[消息通知] ========== {} 通知开始 ==========", label);

        try {
            ZoneId zone = ZoneId.of(timezone);
            LocalDateTime now = LocalDateTime.now(zone);
            Instant end = Instant.now();
            Instant start = end.minus(lookbackHours, ChronoUnit.HOURS);
            String periodLabel = now.minusHours(lookbackHours).format(DATETIME_FMT) + " ~ " + now.format(DATETIME_FMT);

            String report = buildFullReport(label, periodLabel, start, end);
            String subject = String.format("[%s通知] 加密货币分析摘要 - %s", label, now.format(DATETIME_FMT));

            // 通过所有通知渠道发送（邮件 + Telegram）
            for (Notifier notifier : notifiers) {
                try {
                    notifier.notify(subject, report);
                } catch (Exception e) {
                    log.error("[消息通知] {} 发送失败: {}", notifier.getClass().getSimpleName(), e.getMessage());
                }
            }

            log.info("[消息通知] ========== {} 通知发送完成 ==========", label);
        } catch (Exception e) {
            log.error("[消息通知] {} 通知异常: {}", label, e.getMessage(), e);
        }
    }

    private String buildFullReport(String label, String periodLabel, Instant start, Instant end) {
        ZoneId zone = ZoneId.of(timezone);
        StringBuilder sb = new StringBuilder();

        sb.append("====================================\n");
        sb.append("  ").append(label).append("市场通知\n");
        sb.append("  时段: ").append(periodLabel).append("\n");
        sb.append("====================================\n\n");

        // ========== 1. 策略信号摘要 ==========
        sb.append("【策略信号】\n");

        List<Signal> signals = signalRepository.findByTimestampBetweenAndActionNotOrderByTimestampDesc(
                start, end, Signal.Action.HOLD);

        if (signals.isEmpty()) {
            sb.append("  本时段无交易信号\n");
        } else {
            Map<Signal.Action, Long> actionCounts = signals.stream()
                    .collect(Collectors.groupingBy(Signal::getAction, Collectors.counting()));
            sb.append(String.format("  BUY: %d 次 | SELL: %d 次\n",
                    actionCounts.getOrDefault(Signal.Action.BUY, 0L),
                    actionCounts.getOrDefault(Signal.Action.SELL, 0L)));

            // 按策略分组统计
            Map<String, Map<Signal.Action, Long>> byStrategy = signals.stream()
                    .collect(Collectors.groupingBy(Signal::getStrategyName,
                            Collectors.groupingBy(Signal::getAction, Collectors.counting())));

            sb.append("  各策略信号:\n");
            for (Map.Entry<String, Map<Signal.Action, Long>> entry : byStrategy.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(": ");
                entry.getValue().forEach((action, count) ->
                        sb.append(action).append("=").append(count).append(" "));
                sb.append("\n");
            }

            sb.append("\n  最近信号明细:\n");
            int showCount = Math.min(10, signals.size());
            for (int i = 0; i < showCount; i++) {
                Signal signal = signals.get(i);
                String time = signal.getTimestamp().atZone(zone).format(TIME_FMT);
                sb.append(String.format("  [%s] %s %s | 价格:%.2f | 置信度:%.0f%% | %s",
                        time, signal.getSymbol(), signal.getAction(),
                        signal.getPrice(), signal.getConfidence() * 100, signal.getStrategyName()));
                if (signal.getReason() != null) {
                    String reason = signal.getReason().length() > 80
                            ? signal.getReason().substring(0, 80) + "..."
                            : signal.getReason();
                    sb.append(" | ").append(reason);
                }
                sb.append("\n");
            }
            if (signals.size() > showCount) {
                sb.append("  ... 还有 ").append(signals.size() - showCount).append(" 条信号\n");
            }
        }

        // ========== 2. 最新分析报告摘要 ==========
        sb.append("\n【AI分析摘要】\n");

        for (String symbol : watchList) {
            reportRepository.findTopBySymbolOrderByCreatedAtDesc(symbol).ifPresentOrElse(report -> {
                String reportTime = report.getCreatedAt().atZone(zone).format(DATETIME_FMT);
                sb.append("  ").append(symbol).append(" (").append(reportTime).append(")\n");
                sb.append("    趋势: ").append(trendChinese(report.getTrendDirection()));
                sb.append(" | 置信度: ").append(String.format("%.0f%%", report.getTrendConfidence() * 100));
                sb.append(" | 风险: ").append(riskChinese(report.getRiskAssessment())).append("\n");

                if (report.getPriceCurrent() != null) {
                    sb.append("    当前价: ").append(report.getPriceCurrent());
                }
                if (report.getPriceSupport() != null) {
                    sb.append(" | 支撑: ").append(report.getPriceSupport());
                }
                if (report.getPriceResistance() != null) {
                    sb.append(" | 阻力: ").append(report.getPriceResistance());
                }
                sb.append("\n");

                if (report.getShortTermOutlook() != null) {
                    String outlook = report.getShortTermOutlook().length() > 100
                            ? report.getShortTermOutlook().substring(0, 100) + "..."
                            : report.getShortTermOutlook();
                    sb.append("    短期展望: ").append(outlook).append("\n");
                }
            }, () -> {
                sb.append("  ").append(symbol).append(": 暂无分析报告\n");
            });
        }

        // ========== 3. 预测准确率 ==========
        sb.append("\n【预测表现】\n");
        Instant since30d = Instant.now().minus(30, ChronoUnit.DAYS);

        for (String symbol : watchList) {
            long total = scoreRepository.countTotalSince(symbol, since30d);
            if (total == 0) {
                sb.append("  ").append(symbol).append(": 暂无评分数据\n");
                continue;
            }

            long correct = scoreRepository.countCorrectTrendSince(symbol, since30d);
            Double avgScore = scoreRepository.findAverageScoreSince(symbol, since30d);
            double accuracy = (double) correct / total * 100;

            sb.append(String.format("  %s: 近30天 %d 次预测, 趋势准确率 %.0f%%, 平均得分 %.0f/100\n",
                    symbol, total, accuracy, avgScore != null ? avgScore : 0));
        }

        sb.append("\n====================================\n");
        sb.append("  OpenNof1 加密货币智能分析系统\n");
        sb.append("====================================\n");

        return sb.toString();
    }

    private String trendChinese(AnalysisReport.TrendDirection dir) {
        if (dir == null) return "未知";
        return switch (dir) {
            case STRONGLY_BULLISH -> "强烈看多";
            case BULLISH -> "看多";
            case NEUTRAL -> "中性";
            case BEARISH -> "看空";
            case STRONGLY_BEARISH -> "强烈看空";
        };
    }

    private String riskChinese(AnalysisReport.RiskLevel level) {
        if (level == null) return "未知";
        return switch (level) {
            case LOW -> "低";
            case MODERATE -> "中等";
            case HIGH -> "高";
            case EXTREME -> "极高";
        };
    }
}
