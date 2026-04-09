package com.crypto.trader.backtest.engine;

import com.crypto.trader.backtest.model.BacktestReport;
import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测指标计算器。
 */
public class MetricsCalculator {

    /**
     * 从交易记录列表计算完整的回测报告。
     */
    public static BacktestReport calculate(String strategyName, BacktestRequest request,
                                            List<Trade> trades, double finalCapital,
                                            int totalBars) {
        double initialCapital = request.getInitialCapital();
        double totalReturn = (finalCapital - initialCapital) / initialCapital * 100;

        int winTrades = 0, lossTrades = 0;
        double totalWinPnl = 0, totalLossPnl = 0;
        int totalHoldBars = 0;

        for (Trade t : trades) {
            if (t.getPnl() > 0) {
                winTrades++;
                totalWinPnl += t.getPnl();
            } else if (t.getPnl() < 0) {
                lossTrades++;
                totalLossPnl += Math.abs(t.getPnl());
            }
            totalHoldBars += t.getHoldBars();
        }

        double winRate = trades.isEmpty() ? 0 : (double) winTrades / trades.size() * 100;
        double avgWin = winTrades > 0 ? totalWinPnl / winTrades : 0;
        double avgLoss = lossTrades > 0 ? totalLossPnl / lossTrades : 1;
        double profitLossRatio = avgLoss > 0 ? avgWin / avgLoss : avgWin;
        double avgHoldBars = trades.isEmpty() ? 0 : (double) totalHoldBars / trades.size();

        // 最大回撤
        double maxDrawdown = calculateMaxDrawdown(trades, initialCapital);

        // 夏普比率
        double sharpeRatio = calculateSharpe(trades, initialCapital);

        // 最大连续亏损
        int maxConsecutiveLosses = calculateMaxConsecutiveLosses(trades);

        // 年化收益率（按 K 线数量估算）
        double annualized = 0;
        if (totalBars > 0) {
            long intervalMs = estimateIntervalMs(request.getInterval());
            double days = (double) totalBars * intervalMs / (86400_000L);
            if (days > 0) {
                annualized = totalReturn * 365 / days;
            }
        }

        return BacktestReport.builder()
                .strategyName(strategyName)
                .symbol(request.getSymbol())
                .interval(request.getInterval())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .totalBars(totalBars)
                .initialCapital(initialCapital)
                .finalCapital(Math.round(finalCapital * 100.0) / 100.0)
                .totalReturnPercent(Math.round(totalReturn * 100.0) / 100.0)
                .annualizedReturnPercent(Math.round(annualized * 100.0) / 100.0)
                .totalTrades(trades.size())
                .winTrades(winTrades)
                .lossTrades(lossTrades)
                .winRate(Math.round(winRate * 100.0) / 100.0)
                .profitLossRatio(Math.round(profitLossRatio * 100.0) / 100.0)
                .avgHoldBars(Math.round(avgHoldBars * 10.0) / 10.0)
                .maxDrawdownPercent(Math.round(maxDrawdown * 100.0) / 100.0)
                .sharpeRatio(Math.round(sharpeRatio * 100.0) / 100.0)
                .maxConsecutiveLosses(maxConsecutiveLosses)
                .trades(trades)
                .build();
    }

    /**
     * 计算最大回撤（基于逐笔交易后的资金曲线）。
     */
    private static double calculateMaxDrawdown(List<Trade> trades, double initialCapital) {
        double capital = initialCapital;
        double peak = capital;
        double maxDrawdown = 0;

        for (Trade t : trades) {
            capital += t.getPnl();
            if (capital > peak) peak = capital;
            double drawdown = (peak - capital) / peak * 100;
            if (drawdown > maxDrawdown) maxDrawdown = drawdown;
        }

        return maxDrawdown;
    }

    /**
     * 计算年化夏普比率。
     * Sharpe = (平均收益率 - 无风险利率) / 收益率标准差 * sqrt(年化因子)
     */
    private static double calculateSharpe(List<Trade> trades, double initialCapital) {
        if (trades.size() < 2) return 0;

        List<Double> returns = new ArrayList<>();
        for (Trade t : trades) {
            returns.add(t.getPnlPercent());
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream()
                .mapToDouble(r -> (r - mean) * (r - mean))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) return 0;

        // 假设无风险利率 = 0，年化因子按每天1次交易估算
        double annualFactor = Math.sqrt(252);
        return (mean / stdDev) * annualFactor;
    }

    private static int calculateMaxConsecutiveLosses(List<Trade> trades) {
        int max = 0, current = 0;
        for (Trade t : trades) {
            if (t.getPnl() < 0) {
                current++;
                if (current > max) max = current;
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static long estimateIntervalMs(String interval) {
        return switch (interval) {
            case "1m"  -> 60_000L;
            case "5m"  -> 300_000L;
            case "15m" -> 900_000L;
            case "1h"  -> 3_600_000L;
            case "4h"  -> 14_400_000L;
            case "1d"  -> 86_400_000L;
            default    -> 3_600_000L;
        };
    }
}
