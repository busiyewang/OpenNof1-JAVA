package com.crypto.trader.service.risk;

import lombok.Builder;
import lombok.Data;

/**
 * 风控参数配置。
 */
@Data
@Builder
public class RiskConfig {

    /** 最大回撤百分比（超过则熔断暂停交易） */
    @Builder.Default
    private double maxDrawdownPercent = 15.0;

    /** 单日最大亏损百分比（占初始资金，超过则暂停当日交易） */
    @Builder.Default
    private double dailyLossLimitPercent = 5.0;

    /** 开始缩减仓位的连续亏损次数 */
    @Builder.Default
    private int consecutiveLossThreshold = 3;

    /** 仓位缩减因子（每超过阈值一次，乘以此因子） */
    @Builder.Default
    private double positionReductionFactor = 0.5;

    /** 暂停交易的连续亏损次数 */
    @Builder.Default
    private int consecutiveLossPauseThreshold = 5;

    /**
     * 默认配置。
     */
    public static RiskConfig defaults() {
        return RiskConfig.builder().build();
    }

    /**
     * 从 BacktestRequest 的字段构建（禁用风控时返回极宽松配置）。
     */
    public static RiskConfig disabled() {
        return RiskConfig.builder()
                .maxDrawdownPercent(100)
                .dailyLossLimitPercent(100)
                .consecutiveLossThreshold(Integer.MAX_VALUE)
                .consecutiveLossPauseThreshold(Integer.MAX_VALUE)
                .build();
    }
}
