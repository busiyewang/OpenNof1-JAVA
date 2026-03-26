package com.crypto.trader.service.executor;

import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.TradeRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 风控管理器。
 *
 * <p>当前实现的约束：</p>
 * <ul>
 *   <li>最大日内交易次数（max-daily-trades）</li>
 *   <li>BUY 信号须满足最低置信度阈值（0.55）</li>
 * </ul>
 */
@Service
@Slf4j
public class RiskManager {

    @Autowired
    private TradeRecordRepository tradeRecordRepository;

    @Value("${crypto.trading.max-daily-trades:10}")
    private int maxDailyTrades;

    @Value("${crypto.trading.max-position-size-usdt:1000}")
    private double maxPositionSizeUsdt;

    private static final double MIN_CONFIDENCE = 0.55;

    /**
     * 判断当前信号是否允许下单。
     *
     * @param signal 待评估的交易信号
     * @return true 表示允许下单，false 表示被风控拦截
     */
    public boolean isTradeAllowed(Signal signal) {
        log.info("[风控] {} 检查信号: {} 置信度={} 策略={}",
                signal.getSymbol(), signal.getAction(), signal.getConfidence(), signal.getStrategyName());

        // 1. 置信度过滤
        if (signal.getConfidence() < MIN_CONFIDENCE) {
            log.warn("[风控] {} 置信度 {} < 阈值 {}，拦截", signal.getSymbol(), signal.getConfidence(), MIN_CONFIDENCE);
            return false;
        }
        log.info("[风控] {} 置信度检查通过: {} >= {}", signal.getSymbol(), signal.getConfidence(), MIN_CONFIDENCE);

        // 2. 日内交易次数限制
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long todayTrades = tradeRecordRepository.countByTimestampAfter(startOfDay);
        if (todayTrades >= maxDailyTrades) {
            log.warn("[风控] {} 今日交易次数 {}/{} 已达上限，拦截", signal.getSymbol(), todayTrades, maxDailyTrades);
            return false;
        }
        log.info("[风控] {} 日内交易次数检查通过: {}/{}", signal.getSymbol(), todayTrades, maxDailyTrades);

        log.info("[风控] {} 所有风控检查通过，允许交易", signal.getSymbol());
        return true;
    }

    /**
     * 返回单笔最大下单金额（USDT）。
     */
    public double getMaxPositionSizeUsdt() {
        return maxPositionSizeUsdt;
    }
}
