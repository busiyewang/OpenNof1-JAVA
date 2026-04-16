package com.crypto.trader.backtest.model;

import lombok.Data;
import java.util.List;

/**
 * 回测请求参数。
 */
@Data
public class BacktestRequest {

    /** 交易对 */
    private String symbol = "BTCUSDT";

    /** K线周期 */
    private String interval = "1h";

    /** 起始日期 (yyyy-MM-dd) */
    private String startDate;

    /** 结束日期 (yyyy-MM-dd)，默认今天 */
    private String endDate;

    /** 要回测的策略名列表，为空则回测所有策略 */
    private List<String> strategies;

    /** 是否使用多策略投票模式（默认 false = 逐个策略独立回测） */
    private boolean voteMode = false;

    /** 投票模式下，多少个策略同意才执行（默认过半） */
    private int voteThreshold = 0;

    /** 初始资金 (USDT) */
    private double initialCapital = 10000;

    /** 每次开仓占总资金的比例 (0-1) */
    private double positionSizePercent = 0.5;

    /** 止损百分比（如 3.0 表示亏 3% 止损），0 表示不设止损 */
    private double stopLossPercent = 3.0;

    /** 止盈百分比（如 5.0 表示赚 5% 止盈），0 表示不设止盈 */
    private double takeProfitPercent = 5.0;

    /** 策略滚动窗口大小（传给 evaluate 的 K 线数量） */
    private int windowSize = 100;

    /** 手续费率（单边，如 0.1 表示 0.1%） */
    private double feePercent = 0.1;

    // ========== 滑点 ==========

    /** 滑点（基点，5 = 0.05%），默认 5bps */
    private double slippageBps = 5.0;

    // ========== 动态仓位 ==========

    /** 是否启用动态仓位（根据置信度和波动率调整） */
    private boolean dynamicPositionSizing = false;

    /** 动态仓位上限 (0-1) */
    private double maxPositionSizePercent = 0.8;

    /** 动态仓位下限 (0-1) */
    private double minPositionSizePercent = 0.1;

    // ========== 风控 ==========

    /** 是否启用风控 */
    private boolean riskManagementEnabled = false;

    /** 最大回撤百分比（超过则熔断） */
    private double maxDrawdownPercent = 15.0;

    /** 单日最大亏损百分比 */
    private double dailyLossLimitPercent = 5.0;

    /** 暂停交易的连续亏损次数 */
    private int consecutiveLossPauseThreshold = 5;
}
