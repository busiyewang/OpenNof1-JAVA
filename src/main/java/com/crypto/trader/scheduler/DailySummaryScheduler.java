package com.crypto.trader.scheduler;

import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.SignalRepository;
import com.crypto.trader.service.notifier.EmailNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定时信号摘要邮件调度器（简化版 - 仅包含信号统计）。
 */
@Component
@Slf4j
public class DailySummaryScheduler {

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private EmailNotifier emailNotifier;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Scheduled(cron = "0 0 */2 * * ?", zone = "Asia/Shanghai")
    public void sendPeriodicSummary() {
        try {
            Instant end = Instant.now();
            Instant start = end.minusSeconds(2 * 3600);

            LocalDateTime now = LocalDateTime.now(ZONE_SHANGHAI);
            String periodLabel = now.minusHours(2).format(DATETIME_FMT) + " ~ " + now.format(DATETIME_FMT);

            String report = buildReport(periodLabel, start, end);
            String subject = String.format("[信号摘要] %s", periodLabel);

            emailNotifier.notify(subject, report);
            log.info("[Summary] 摘要邮件已发送: {}", periodLabel);
        } catch (Exception e) {
            log.error("[Summary] 摘要邮件发送失败", e);
        }
    }

    private String buildReport(String periodLabel, Instant start, Instant end) {
        StringBuilder sb = new StringBuilder();

        sb.append("====================================\n");
        sb.append("  加密货币信号摘要\n");
        sb.append("  时段: ").append(periodLabel).append("\n");
        sb.append("====================================\n\n");

        List<Signal> signals = signalRepository.findByTimestampBetweenAndActionNotOrderByTimestampDesc(
                start, end, Signal.Action.HOLD);

        if (signals.isEmpty()) {
            sb.append("  本时段无交易信号\n");
        } else {
            Map<Signal.Action, Long> actionCounts = signals.stream()
                    .collect(Collectors.groupingBy(Signal::getAction, Collectors.counting()));
            sb.append(String.format("  BUY 信号: %d 次\n", actionCounts.getOrDefault(Signal.Action.BUY, 0L)));
            sb.append(String.format("  SELL 信号: %d 次\n", actionCounts.getOrDefault(Signal.Action.SELL, 0L)));
            sb.append("\n  信号明细:\n");

            for (Signal signal : signals) {
                String time = signal.getTimestamp().atZone(ZONE_SHANGHAI).format(TIME_FMT);
                sb.append(String.format("  [%s] %s %s | 价格: %.2f | 置信度: %.2f | 策略: %s\n",
                        time, signal.getSymbol(), signal.getAction(),
                        signal.getPrice(), signal.getConfidence(), signal.getStrategyName()));
            }
        }

        sb.append("\n====================================\n");
        sb.append("  OpenNof1 加密货币分析系统\n");
        sb.append("====================================\n");

        return sb.toString();
    }
}
