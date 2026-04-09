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
}
