package com.crypto.trader.service.executor;

import com.crypto.trader.model.TradeRecord;
import com.crypto.trader.repository.TradeRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仓位管理器，启动时从 trade_records 表恢复仓位状态。
 *
 * <p>恢复逻辑：遍历每个交易对的所有成交记录，BUY 累加、SELL 清零，最终得出当前仓位。</p>
 */
@Service
@Slf4j
public class PositionManager {

    private final ConcurrentHashMap<String, BigDecimal> positions = new ConcurrentHashMap<>();

    @Autowired
    private TradeRecordRepository tradeRecordRepository;

    /**
     * 启动时从数据库恢复仓位。
     */
    @PostConstruct
    public void recoverPositions() {
        try {
            List<String> symbols = tradeRecordRepository.findDistinctSymbols();
            for (String symbol : symbols) {
                List<TradeRecord> records = tradeRecordRepository.findBySymbolOrderByTimestampAsc(symbol);
                BigDecimal position = BigDecimal.ZERO;
                for (TradeRecord record : records) {
                    if ("BUY".equalsIgnoreCase(record.getSide())) {
                        position = position.add(record.getQuantity() != null ? record.getQuantity() : BigDecimal.ZERO);
                    } else if ("SELL".equalsIgnoreCase(record.getSide())) {
                        // 卖出时清空仓位（当前逻辑为全仓卖出）
                        position = BigDecimal.ZERO;
                    }
                }
                if (position.compareTo(BigDecimal.ZERO) > 0) {
                    positions.put(symbol, position);
                    log.info("[PositionManager] Recovered position for {}: {}", symbol, position);
                }
            }
            log.info("[PositionManager] Position recovery complete. Active positions: {}", positions.size());
        } catch (Exception e) {
            log.error("[PositionManager] Failed to recover positions from DB, starting with empty positions", e);
        }
    }

    public BigDecimal getPosition(String symbol) {
        return positions.getOrDefault(symbol, BigDecimal.ZERO);
    }

    public boolean hasPosition(String symbol) {
        return getPosition(symbol).compareTo(BigDecimal.ZERO) > 0;
    }

    public void addPosition(String symbol, BigDecimal quantity) {
        positions.merge(symbol, quantity, BigDecimal::add);
        log.info("[PositionManager] {} position after buy: {}", symbol, getPosition(symbol));
    }

    public void clearPosition(String symbol) {
        BigDecimal prev = positions.put(symbol, BigDecimal.ZERO);
        log.info("[PositionManager] {} position cleared (was: {})", symbol, prev);
    }
}
