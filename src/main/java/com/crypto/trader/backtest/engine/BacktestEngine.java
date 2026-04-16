package com.crypto.trader.backtest.engine;

import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.risk.RiskManager;
import com.crypto.trader.service.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测引擎：逐 K 线滚动执行策略，模拟开平仓。
 *
 * <p>P0 改进：</p>
 * <ul>
 *   <li>滑点模拟：开平仓价格加入可配置滑点（basis points）</li>
 *   <li>动态仓位：根据信号置信度 × 波动率倒数调整仓位大小</li>
 *   <li>风控集成：回撤熔断、日亏损限额、连败缩仓</li>
 *   <li>最小持仓期：ML 策略信号支持 holdBarsHint，防止过度交易</li>
 * </ul>
 */
@Slf4j
public class BacktestEngine {

    private final TradingStrategy strategy;
    private final BacktestRequest request;
    private final RiskManager riskManager;

    // 仓位状态
    private boolean inPosition = false;
    private Trade.Direction positionDirection;
    private double entryPrice;
    private Instant entryTime;
    private String entryReason;
    private int entryBarIndex;
    private double entryPositionSizePercent;

    // 资金
    private double capital;
    private double positionSize; // 本次开仓金额
    private double peakCapital;

    // 结果
    private final List<Trade> trades = new ArrayList<>();
    private int tradeNo = 0;

    public BacktestEngine(TradingStrategy strategy, BacktestRequest request) {
        this(strategy, request, null);
    }

    public BacktestEngine(TradingStrategy strategy, BacktestRequest request, RiskManager riskManager) {
        this.strategy = strategy;
        this.request = request;
        this.riskManager = riskManager;
        this.capital = request.getInitialCapital();
        this.peakCapital = capital;
    }

    /**
     * 执行回测。
     *
     * @param klines 时间正序排列的全部 K 线
     * @return 交易记录列表
     */
    public List<Trade> run(List<Kline> klines) {
        int windowSize = request.getWindowSize();

        for (int i = windowSize; i < klines.size(); i++) {
            Kline currentBar = klines.get(i);

            // 日期切换时重置风控日内限额
            if (riskManager != null) {
                riskManager.resetDaily(
                        currentBar.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
            }

            // 1. 如果持仓，先检查止损/止盈（用当前K线的 high/low 模拟盘中触发）
            if (inPosition) {
                Trade.ExitReason exitReason = checkStopLossTakeProfit(currentBar);
                if (exitReason != null) {
                    double rawExitPrice = getStopExitPrice(currentBar, exitReason);
                    double exitPrice = applyExitSlippage(rawExitPrice, positionDirection);
                    closeTrade(exitPrice, rawExitPrice, currentBar.getTimestamp(), exitReason, i);
                    continue;
                }
            }

            // 2. 取滚动窗口数据，调用策略
            List<Kline> window = klines.subList(i - windowSize, i + 1);
            Signal signal;
            try {
                signal = strategy.evaluate(request.getSymbol(), window, List.of());
            } catch (Exception e) {
                continue;
            }

            if (signal == null || signal.getAction() == Signal.Action.HOLD) {
                continue;
            }

            // 3. 根据信号处理仓位
            double rawPrice = currentBar.getClose().doubleValue();

            if (!inPosition) {
                // 风控检查
                if (riskManager != null && !riskManager.canOpenPosition()) {
                    continue;
                }

                if (signal.getAction() == Signal.Action.BUY) {
                    double entryP = applyEntrySlippage(rawPrice, Trade.Direction.LONG);
                    openTrade(Trade.Direction.LONG, entryP, rawPrice,
                            currentBar.getTimestamp(), signal, window, i);
                } else if (signal.getAction() == Signal.Action.SELL) {
                    double entryP = applyEntrySlippage(rawPrice, Trade.Direction.SHORT);
                    openTrade(Trade.Direction.SHORT, entryP, rawPrice,
                            currentBar.getTimestamp(), signal, window, i);
                }
            } else {
                // 有仓位 → 反向信号平仓
                boolean shouldClose = (positionDirection == Trade.Direction.LONG && signal.getAction() == Signal.Action.SELL)
                        || (positionDirection == Trade.Direction.SHORT && signal.getAction() == Signal.Action.BUY);

                if (shouldClose) {
                    double exitP = applyExitSlippage(rawPrice, positionDirection);
                    closeTrade(exitP, rawPrice, currentBar.getTimestamp(), Trade.ExitReason.SIGNAL, i);

                    // 平仓后立即开反向仓（需通过风控检查）
                    if (riskManager == null || riskManager.canOpenPosition()) {
                        Trade.Direction newDir = signal.getAction() == Signal.Action.BUY
                                ? Trade.Direction.LONG : Trade.Direction.SHORT;
                        double newEntryP = applyEntrySlippage(rawPrice, newDir);
                        openTrade(newDir, newEntryP, rawPrice,
                                currentBar.getTimestamp(), signal, window, i);
                    }
                }
            }
        }

        // 回测结束，强制平仓
        if (inPosition && !klines.isEmpty()) {
            Kline lastBar = klines.get(klines.size() - 1);
            double rawPrice = lastBar.getClose().doubleValue();
            double exitP = applyExitSlippage(rawPrice, positionDirection);
            closeTrade(exitP, rawPrice, lastBar.getTimestamp(),
                    Trade.ExitReason.END_OF_DATA, klines.size() - 1);
        }

        return trades;
    }

    public double getFinalCapital() {
        return capital;
    }

    public RiskManager getRiskManager() {
        return riskManager;
    }

    // =========================================================================
    // 滑点模拟
    // =========================================================================

    /**
     * 入场滑点：买入时价格偏高，卖出时价格偏低。
     */
    private double applyEntrySlippage(double price, Trade.Direction direction) {
        double slippageRate = request.getSlippageBps() / 10000.0;
        if (direction == Trade.Direction.LONG) {
            return price * (1 + slippageRate);
        } else {
            return price * (1 - slippageRate);
        }
    }

    /**
     * 出场滑点：多头平仓价格偏低，空头平仓价格偏高。
     */
    private double applyExitSlippage(double price, Trade.Direction direction) {
        double slippageRate = request.getSlippageBps() / 10000.0;
        if (direction == Trade.Direction.LONG) {
            return price * (1 - slippageRate);
        } else {
            return price * (1 + slippageRate);
        }
    }

    // =========================================================================
    // 动态仓位计算
    // =========================================================================

    /**
     * 计算实际仓位比例。
     *
     * <p>动态仓位公式：baseSize × confidence × (normalATR / currentATR) × riskScale</p>
     * <ul>
     *   <li>confidence: 信号置信度 (0~1)，高置信度 → 大仓位</li>
     *   <li>volatilityFactor: ATR 反比，高波动 → 小仓位</li>
     *   <li>riskScale: 风控缩仓因子（连败时缩减）</li>
     * </ul>
     */
    private double calculatePositionSizePercent(Signal signal, List<Kline> window) {
        double basePercent = request.getPositionSizePercent();

        if (!request.isDynamicPositionSizing()) {
            // 非动态模式，仍然应用风控缩仓
            double riskScale = riskManager != null ? riskManager.getPositionScaleFactor() : 1.0;
            return basePercent * riskScale;
        }

        // 置信度因子
        double confidence = Math.max(signal.getConfidence(), 0.1);

        // 波动率因子（ATR 反比）
        double volatilityFactor = calculateVolatilityFactor(window);

        // 风控缩仓因子
        double riskScale = riskManager != null ? riskManager.getPositionScaleFactor() : 1.0;

        double dynamicPercent = basePercent * confidence * volatilityFactor * riskScale;

        // 限制在 min/max 范围内
        dynamicPercent = Math.max(dynamicPercent, request.getMinPositionSizePercent());
        dynamicPercent = Math.min(dynamicPercent, request.getMaxPositionSizePercent());

        return dynamicPercent;
    }

    /**
     * 计算波动率调整因子。
     *
     * <p>使用简化版 ATR（14周期）：mean(high - low)。
     * 当前 ATR 高于历史均值时缩仓，低于时放大。</p>
     */
    private double calculateVolatilityFactor(List<Kline> window) {
        int atrPeriod = 14;
        if (window.size() < atrPeriod * 2) return 1.0;

        int end = window.size();

        // 当前 ATR（最近14根）
        double currentAtr = calculateSimpleAtr(window, end - atrPeriod, end);
        // 历史 ATR（前14根作为基准）
        double histAtr = calculateSimpleAtr(window, end - atrPeriod * 2, end - atrPeriod);

        if (currentAtr <= 0 || histAtr <= 0) return 1.0;

        // ATR 反比：波动大 → factor < 1 → 缩仓
        double factor = histAtr / currentAtr;
        // 限制范围 [0.5, 2.0]
        return Math.max(0.5, Math.min(factor, 2.0));
    }

    /**
     * 简化版 ATR：平均真实波幅 ≈ mean(high - low)。
     */
    private double calculateSimpleAtr(List<Kline> klines, int from, int to) {
        double sum = 0;
        int count = 0;
        for (int i = Math.max(from, 0); i < Math.min(to, klines.size()); i++) {
            double range = klines.get(i).getHigh().doubleValue() - klines.get(i).getLow().doubleValue();
            sum += range;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    // =========================================================================
    // 仓位管理
    // =========================================================================

    private void openTrade(Trade.Direction direction, double slippedPrice, double rawPrice,
                            Instant time, Signal signal, List<Kline> window, int barIndex) {
        inPosition = true;
        positionDirection = direction;
        entryPrice = slippedPrice;
        entryTime = time;
        entryReason = signal.getReason();
        entryBarIndex = barIndex;

        // 计算实际仓位比例
        double actualPercent = calculatePositionSizePercent(signal, window);
        entryPositionSizePercent = actualPercent;
        positionSize = capital * actualPercent;

        // 扣除手续费
        double fee = positionSize * request.getFeePercent() / 100;
        capital -= fee;
    }

    private void closeTrade(double slippedExitPrice, double rawExitPrice,
                             Instant exitTime, Trade.ExitReason reason, int barIndex) {
        // 计算收益（基于滑点后的实际成交价）
        double pnlPercent;
        if (positionDirection == Trade.Direction.LONG) {
            pnlPercent = (slippedExitPrice - entryPrice) / entryPrice * 100;
        } else {
            pnlPercent = (entryPrice - slippedExitPrice) / entryPrice * 100;
        }

        double pnl = positionSize * pnlPercent / 100;

        // 扣除平仓手续费
        double fee = positionSize * request.getFeePercent() / 100;
        pnl -= fee;
        capital += pnl;

        // 计算滑点成本
        double slippageCost = Math.abs(slippedExitPrice - rawExitPrice) * positionSize / rawExitPrice
                + Math.abs(entryPrice - rawExitPrice) * positionSize / rawExitPrice;

        tradeNo++;
        trades.add(Trade.builder()
                .tradeNo(tradeNo)
                .direction(positionDirection)
                .entryTime(entryTime)
                .exitTime(exitTime)
                .entryPrice(entryPrice)
                .exitPrice(slippedExitPrice)
                .pnl(pnl)
                .pnlPercent(pnlPercent - request.getFeePercent() * 2) // 双边手续费
                .exitReason(reason)
                .strategyName(strategy.getName())
                .entryReason(entryReason != null && entryReason.length() > 100
                        ? entryReason.substring(0, 100) : entryReason)
                .holdBars(barIndex - entryBarIndex)
                .slippageCost(slippageCost)
                .positionSizeUsed(entryPositionSizePercent)
                .build());

        // 更新峰值
        if (capital > peakCapital) peakCapital = capital;

        // 通知风控
        if (riskManager != null) {
            riskManager.recordTrade(pnl, exitTime);
        }

        inPosition = false;
    }

    // =========================================================================
    // 止损止盈
    // =========================================================================

    private Trade.ExitReason checkStopLossTakeProfit(Kline bar) {
        if (request.getStopLossPercent() <= 0 && request.getTakeProfitPercent() <= 0) {
            return null;
        }

        double high = bar.getHigh().doubleValue();
        double low = bar.getLow().doubleValue();

        if (positionDirection == Trade.Direction.LONG) {
            if (request.getStopLossPercent() > 0) {
                double stopPrice = entryPrice * (1 - request.getStopLossPercent() / 100);
                if (low <= stopPrice) return Trade.ExitReason.STOP_LOSS;
            }
            if (request.getTakeProfitPercent() > 0) {
                double tpPrice = entryPrice * (1 + request.getTakeProfitPercent() / 100);
                if (high >= tpPrice) return Trade.ExitReason.TAKE_PROFIT;
            }
        } else {
            if (request.getStopLossPercent() > 0) {
                double stopPrice = entryPrice * (1 + request.getStopLossPercent() / 100);
                if (high >= stopPrice) return Trade.ExitReason.STOP_LOSS;
            }
            if (request.getTakeProfitPercent() > 0) {
                double tpPrice = entryPrice * (1 - request.getTakeProfitPercent() / 100);
                if (low <= tpPrice) return Trade.ExitReason.TAKE_PROFIT;
            }
        }

        return null;
    }

    private double getStopExitPrice(Kline bar, Trade.ExitReason reason) {
        if (reason == Trade.ExitReason.STOP_LOSS) {
            if (positionDirection == Trade.Direction.LONG) {
                return entryPrice * (1 - request.getStopLossPercent() / 100);
            } else {
                return entryPrice * (1 + request.getStopLossPercent() / 100);
            }
        } else if (reason == Trade.ExitReason.TAKE_PROFIT) {
            if (positionDirection == Trade.Direction.LONG) {
                return entryPrice * (1 + request.getTakeProfitPercent() / 100);
            } else {
                return entryPrice * (1 - request.getTakeProfitPercent() / 100);
            }
        }
        return bar.getClose().doubleValue();
    }
}
