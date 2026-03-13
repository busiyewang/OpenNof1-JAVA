package com.crypto.trader.service.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存仓位管理器。
 *
 * <p>跟踪每个交易对当前持有的基础货币数量。
 * 注意：仓位数据保存在内存中，服务重启后将清零；如需持久化，可扩展为读取 trade_records 表恢复状态。</p>
 *
 * <p>该类被并行策略调用，内部使用 {@link ConcurrentHashMap} 保证线程安全。</p>
 */
@Service
@Slf4j
public class PositionManager {

    /** symbol -> 持仓基础货币数量（如 BTC 数量） */
    private final ConcurrentHashMap<String, BigDecimal> positions = new ConcurrentHashMap<>();

    /**
     * 获取指定交易对当前持仓数量。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @return 持仓基础货币数量；无持仓时返回 ZERO
     */
    public BigDecimal getPosition(String symbol) {
        return positions.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /**
     * 是否持有非零仓位。
     */
    public boolean hasPosition(String symbol) {
        return getPosition(symbol).compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 增加持仓（买入后调用）。
     *
     * @param symbol   交易对
     * @param quantity 买入的基础货币数量
     */
    public void addPosition(String symbol, BigDecimal quantity) {
        positions.merge(symbol, quantity, BigDecimal::add);
        log.info("[PositionManager] {} position after buy: {}", symbol, getPosition(symbol));
    }

    /**
     * 清空仓位（卖出全部后调用）。
     *
     * @param symbol 交易对
     */
    public void clearPosition(String symbol) {
        BigDecimal prev = positions.put(symbol, BigDecimal.ZERO);
        log.info("[PositionManager] {} position cleared (was: {})", symbol, prev);
    }
}
