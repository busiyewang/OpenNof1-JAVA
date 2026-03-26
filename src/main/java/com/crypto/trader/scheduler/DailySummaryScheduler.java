package com.crypto.trader.scheduler;

import com.crypto.trader.model.Signal;
import com.crypto.trader.model.TradeRecord;
import com.crypto.trader.repository.SignalRepository;
import com.crypto.trader.repository.TradeRecordRepository;
import com.crypto.trader.service.executor.PositionManager;
import com.crypto.trader.service.notifier.EmailNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定时摘要邮件调度器。
 *
 * <p>每 2 小时发送一次交易分析汇总（整点触发：0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22），包含：</p>
 * <ul>
 *   <li>过去 2 小时的信号统计（BUY/SELL 各多少次）</li>
 *   <li>过去 2 小时的实际成交记录</li>
 *   <li>当前持仓状态</li>
 * </ul>
 */
@Component
@Slf4j
public class DailySummaryScheduler {

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private TradeRecordRepository tradeRecordRepository;

    @Autowired
    private PositionManager positionManager;

    @Autowired
    private EmailNotifier emailNotifier;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    @Value("${crypto.trading.mode:paper}")
    private String tradingMode;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 每 2 小时整点触发（北京时间）。
     */
    @Scheduled(cron = "0 0 */2 * * ?", zone = "Asia/Shanghai")
    public void sendPeriodicSummary() {
        try {
            Instant end = Instant.now();
            Instant start = end.minusSeconds(2 * 3600); // 过去 2 小时

            LocalDateTime now = LocalDateTime.now(ZONE_SHANGHAI);
            String periodLabel = now.minusHours(2).format(DATETIME_FMT) + " ~ " + now.format(DATETIME_FMT);

            String report = buildReport(periodLabel, start, end);
            String subject = String.format("[交易摘要] %s", periodLabel);

            emailNotifier.notify(subject, report);
            log.info("[Summary] Periodic summary email sent for {}", periodLabel);
        } catch (Exception e) {
            log.error("[Summary] Failed to send periodic summary", e);
        }
    }

    private String buildReport(String periodLabel, Instant start, Instant end) {
        StringBuilder sb = new StringBuilder();

        sb.append("====================================\n");
        sb.append("  加密货币交易摘要\n");
        sb.append("  时段: ").append(periodLabel).append("\n");
        sb.append("  交易模式: ").append("live".equals(tradingMode) ? "实盘" : "模拟").append("\n");
        sb.append("====================================\n\n");

        // 1. 信号统计
        List<Signal> signals = signalRepository.findByTimestampBetweenAndActionNotOrderByTimestampDesc(
                start, end, Signal.Action.HOLD);
        sb.append("【信号统计】\n");
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
                        time,
                        signal.getSymbol(),
                        signal.getAction(),
                        signal.getPrice(),
                        signal.getConfidence(),
                        signal.getStrategyName()));
                if (signal.getReason() != null) {
                    sb.append(String.format("          原因: %s\n", signal.getReason()));
                }
            }
        }

        // 2. 成交记录
        sb.append("\n【成交记录】\n");
        boolean hasAnyTrade = false;
        for (String symbol : watchList) {
            List<TradeRecord> symbolTrades = tradeRecordRepository.findBySymbolOrderByTimestampAsc(symbol)
                    .stream()
                    .filter(t -> !t.getTimestamp().isBefore(start) && t.getTimestamp().isBefore(end))
                    .collect(Collectors.toList());
            for (TradeRecord trade : symbolTrades) {
                hasAnyTrade = true;
                String time = trade.getTimestamp().atZone(ZONE_SHANGHAI).format(TIME_FMT);
                sb.append(String.format("  [%s] %s %s | 价格: %s | 数量: %s | 金额: %s USDT\n",
                        time,
                        trade.getSymbol(),
                        trade.getSide(),
                        trade.getPrice() != null ? trade.getPrice().toPlainString() : "N/A",
                        trade.getQuantity() != null ? trade.getQuantity().toPlainString() : "N/A",
                        trade.getQuoteQuantity() != null ? trade.getQuoteQuantity().toPlainString() : "N/A"));
            }
        }
        if (!hasAnyTrade) {
            sb.append("  本时段无实际成交\n");
        }

        // 3. 当前持仓
        sb.append("\n【当前持仓】\n");
        boolean hasAnyPosition = false;
        for (String symbol : watchList) {
            BigDecimal position = positionManager.getPosition(symbol);
            if (position.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("  %s: %s\n", symbol, position.toPlainString()));
                hasAnyPosition = true;
            }
        }
        if (!hasAnyPosition) {
            sb.append("  当前无持仓\n");
        }

        sb.append("\n====================================\n");
        sb.append("  OpenNof1 加密货币交易系统\n");
        sb.append("====================================\n");

        return sb.toString();
    }
}
