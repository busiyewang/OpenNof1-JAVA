package com.crypto.trader.backtest.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * 单笔回测交易记录。
 */
@Data
@Builder
public class Trade {

    public enum Direction { LONG, SHORT }
    public enum ExitReason { SIGNAL, STOP_LOSS, TAKE_PROFIT, END_OF_DATA, CIRCUIT_BREAKER }

    private int tradeNo;
    private Direction direction;
    private Instant entryTime;
    private Instant exitTime;
    private double entryPrice;
    private double exitPrice;
    /** 收益 (USDT) */
    private double pnl;
    /** 收益率 (%) */
    private double pnlPercent;
    private ExitReason exitReason;
    private String strategyName;
    private String entryReason;
    /** 持仓 K 线数 */
    private int holdBars;
    /** 本笔滑点成本 (USDT) */
    @Builder.Default
    private double slippageCost = 0;
    /** 实际使用的仓位比例 */
    @Builder.Default
    private double positionSizeUsed = 0;
}
