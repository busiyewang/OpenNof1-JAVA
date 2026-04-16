package com.crypto.trader.service.risk;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 组合级风险管理器 — 回测和实盘通用。
 *
 * <p>三重熔断机制：</p>
 * <ul>
 *   <li>最大回撤熔断：回撤超过阈值暂停交易</li>
 *   <li>日亏损限额：单日亏损超过阈值暂停当日交易</li>
 *   <li>连败缩仓：连续亏损时逐步缩减仓位</li>
 * </ul>
 */
@Slf4j
public class RiskManager {

    private final RiskConfig config;

    // 资金追踪
    private double initialCapital;
    private double peakCapital;
    private double currentCapital;

    // 日内追踪
    private double dailyPnl;
    private LocalDate currentDay;

    // 连败追踪
    private int consecutiveLosses;

    // 熔断状态
    private boolean drawdownBreaker = false;
    private int drawdownTriggerCount = 0;
    private int dailyLimitTriggerCount = 0;

    public RiskManager(RiskConfig config, double initialCapital) {
        this.config = config;
        this.initialCapital = initialCapital;
        this.peakCapital = initialCapital;
        this.currentCapital = initialCapital;
        this.dailyPnl = 0;
        this.currentDay = null;
        this.consecutiveLosses = 0;
    }

    /**
     * 是否允许开新仓位。
     */
    public boolean canOpenPosition() {
        if (drawdownBreaker) {
            return false;
        }
        if (isDailyLimitReached()) {
            return false;
        }
        if (consecutiveLosses >= config.getConsecutiveLossPauseThreshold()) {
            return false;
        }
        return true;
    }

    /**
     * 获取仓位缩放因子（0.0 ~ 1.0）。
     *
     * <p>连续亏损达到阈值后逐步缩仓：</p>
     * <ul>
     *   <li>连亏 &lt; threshold: 1.0（不缩）</li>
     *   <li>连亏 = threshold: reductionFactor</li>
     *   <li>连亏 = threshold+1: reductionFactor^2</li>
     *   <li>...</li>
     * </ul>
     */
    public double getPositionScaleFactor() {
        if (consecutiveLosses < config.getConsecutiveLossThreshold()) {
            return 1.0;
        }
        int excess = consecutiveLosses - config.getConsecutiveLossThreshold() + 1;
        double factor = Math.pow(config.getPositionReductionFactor(), excess);
        return Math.max(factor, 0.1); // 最低不低于 10%
    }

    /**
     * 记录一笔交易结果，更新所有风控状态。
     *
     * @param pnl       盈亏金额
     * @param exitTime  平仓时间
     */
    public void recordTrade(double pnl, Instant exitTime) {
        // 更新资金
        currentCapital += pnl;
        if (currentCapital > peakCapital) {
            peakCapital = currentCapital;
        }

        // 更新连败
        if (pnl < 0) {
            consecutiveLosses++;
        } else {
            consecutiveLosses = 0;
        }

        // 更新日内PnL
        LocalDate tradeDay = exitTime.atZone(ZoneOffset.UTC).toLocalDate();
        if (currentDay == null || !currentDay.equals(tradeDay)) {
            currentDay = tradeDay;
            dailyPnl = 0;
        }
        dailyPnl += pnl;

        // 检查最大回撤熔断
        double drawdownPercent = getDrawdownPercent();
        if (drawdownPercent >= config.getMaxDrawdownPercent()) {
            drawdownBreaker = true;
            drawdownTriggerCount++;
            log.warn("[风控] 最大回撤熔断触发! 回撤={}% >= 阈值={}%, 当前资金={}",
                    String.format("%.1f", drawdownPercent),
                    String.format("%.1f", config.getMaxDrawdownPercent()),
                    String.format("%.2f", currentCapital));
        }

        // 检查日亏损限额
        if (isDailyLimitReached()) {
            dailyLimitTriggerCount++;
        }
    }

    /**
     * 日期切换时重置日内限额（回测引擎在检测到日期变化时调用）。
     */
    public void resetDaily(LocalDate newDay) {
        if (currentDay == null || !currentDay.equals(newDay)) {
            currentDay = newDay;
            dailyPnl = 0;
        }
    }

    /**
     * 当前回撤百分比。
     */
    public double getDrawdownPercent() {
        if (peakCapital <= 0) return 0;
        return (peakCapital - currentCapital) / peakCapital * 100;
    }

    /**
     * 当前最大回撤（历史峰值到当前资金）。
     */
    public double getPeakDrawdownPercent() {
        return getDrawdownPercent();
    }

    public boolean isDrawdownBreakerTripped() {
        return drawdownBreaker;
    }

    public int getDrawdownTriggerCount() {
        return drawdownTriggerCount;
    }

    public int getDailyLimitTriggerCount() {
        return dailyLimitTriggerCount;
    }

    public int getConsecutiveLosses() {
        return consecutiveLosses;
    }

    public double getCurrentCapital() {
        return currentCapital;
    }

    private boolean isDailyLimitReached() {
        if (config.getDailyLossLimitPercent() <= 0) return false;
        double dailyLossPercent = -dailyPnl / initialCapital * 100;
        return dailyLossPercent >= config.getDailyLossLimitPercent();
    }
}
