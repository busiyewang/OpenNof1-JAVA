package com.crypto.trader.backtest.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 回测结果报告。
 */
@Data
@Builder
public class BacktestReport {

    /** 策略名 */
    private String strategyName;
    private String symbol;
    private String interval;
    private String startDate;
    private String endDate;

    /** 回测 K 线总数 */
    private int totalBars;

    // ========== 资金指标 ==========

    /** 初始资金 */
    private double initialCapital;
    /** 最终资金 */
    private double finalCapital;
    /** 总收益率 (%) */
    private double totalReturnPercent;
    /** 年化收益率 (%) */
    private double annualizedReturnPercent;

    // ========== 交易指标 ==========

    /** 总交易次数 */
    private int totalTrades;
    /** 盈利交易次数 */
    private int winTrades;
    /** 亏损交易次数 */
    private int lossTrades;
    /** 胜率 (%) */
    private double winRate;
    /** 盈亏比（平均盈利 / 平均亏损） */
    private double profitLossRatio;
    /** 平均持仓 K 线数 */
    private double avgHoldBars;

    // ========== 风险指标 ==========

    /** 最大回撤 (%) */
    private double maxDrawdownPercent;
    /** 夏普比率 (年化) */
    private double sharpeRatio;
    /** 最大连续亏损次数 */
    private int maxConsecutiveLosses;

    // ========== 滑点与仓位统计 ==========

    /** 总滑点成本 (USDT) */
    @Builder.Default
    private double totalSlippageCost = 0;
    /** 平均仓位比例 */
    @Builder.Default
    private double avgPositionSizePercent = 0;

    // ========== 风控统计 ==========

    /** 回撤熔断触发次数 */
    @Builder.Default
    private int circuitBreakerTriggers = 0;
    /** 日亏损限额触发次数 */
    @Builder.Default
    private int dailyLimitTriggers = 0;

    // ========== 交易明细 ==========
    private List<Trade> trades;
}
