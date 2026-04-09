package com.crypto.trader.backtest.engine;

import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测引擎：逐 K 线滚动执行策略，模拟开平仓。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>维护一个滚动窗口（如最近100根K线）</li>
 *   <li>每根新K线到来时，先检查止损/止盈</li>
 *   <li>然后调用策略 evaluate 获取信号</li>
 *   <li>根据信号和当前仓位决定开仓/平仓/持有</li>
 * </ol>
 */
@Slf4j
public class BacktestEngine {

    private final TradingStrategy strategy;
    private final BacktestRequest request;

    // 仓位状态
    private boolean inPosition = false;
    private Trade.Direction positionDirection;
    private double entryPrice;
    private Instant entryTime;
    private String entryReason;
    private int entryBarIndex;

    // 资金
    private double capital;
    private double positionSize; // 本次开仓金额
    private double peakCapital;

    // 结果
    private final List<Trade> trades = new ArrayList<>();
    private int tradeNo = 0;

    public BacktestEngine(TradingStrategy strategy, BacktestRequest request) {
        this.strategy = strategy;
        this.request = request;
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

            // 1. 如果持仓，先检查止损/止盈（用当前K线的 high/low 模拟盘中触发）
            if (inPosition) {
                Trade.ExitReason exitReason = checkStopLossTakeProfit(currentBar);
                if (exitReason != null) {
                    double exitPrice = getStopExitPrice(currentBar, exitReason);
                    closeTrade(exitPrice, currentBar.getTimestamp(), exitReason, i);
                    continue; // 本根 K 线已平仓，不再发信号
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
            double price = currentBar.getClose().doubleValue();

            if (!inPosition) {
                // 无仓位 → 开仓
                if (signal.getAction() == Signal.Action.BUY) {
                    openTrade(Trade.Direction.LONG, price, currentBar.getTimestamp(),
                            signal.getReason(), i);
                } else if (signal.getAction() == Signal.Action.SELL) {
                    openTrade(Trade.Direction.SHORT, price, currentBar.getTimestamp(),
                            signal.getReason(), i);
                }
            } else {
                // 有仓位 → 反向信号平仓
                boolean shouldClose = (positionDirection == Trade.Direction.LONG && signal.getAction() == Signal.Action.SELL)
                        || (positionDirection == Trade.Direction.SHORT && signal.getAction() == Signal.Action.BUY);

                if (shouldClose) {
                    closeTrade(price, currentBar.getTimestamp(), Trade.ExitReason.SIGNAL, i);

                    // 平仓后立即开反向仓
                    Trade.Direction newDir = signal.getAction() == Signal.Action.BUY
                            ? Trade.Direction.LONG : Trade.Direction.SHORT;
                    openTrade(newDir, price, currentBar.getTimestamp(), signal.getReason(), i);
                }
            }
        }

        // 回测结束，强制平仓
        if (inPosition && !klines.isEmpty()) {
            Kline lastBar = klines.get(klines.size() - 1);
            closeTrade(lastBar.getClose().doubleValue(), lastBar.getTimestamp(),
                    Trade.ExitReason.END_OF_DATA, klines.size() - 1);
        }

        return trades;
    }

    public double getFinalCapital() {
        return capital;
    }

    // =========================================================================
    // 仓位管理
    // =========================================================================

    private void openTrade(Trade.Direction direction, double price, Instant time,
                            String reason, int barIndex) {
        inPosition = true;
        positionDirection = direction;
        entryPrice = price;
        entryTime = time;
        entryReason = reason;
        entryBarIndex = barIndex;

        // 扣除手续费
        positionSize = capital * request.getPositionSizePercent();
        double fee = positionSize * request.getFeePercent() / 100;
        capital -= fee;
    }

    private void closeTrade(double exitPrice, Instant exitTime, Trade.ExitReason reason, int barIndex) {
        // 计算收益
        double pnlPercent;
        if (positionDirection == Trade.Direction.LONG) {
            pnlPercent = (exitPrice - entryPrice) / entryPrice * 100;
        } else {
            pnlPercent = (entryPrice - exitPrice) / entryPrice * 100;
        }

        double pnl = positionSize * pnlPercent / 100;

        // 扣除平仓手续费
        double fee = positionSize * request.getFeePercent() / 100;
        pnl -= fee;
        capital += pnl;

        tradeNo++;
        trades.add(Trade.builder()
                .tradeNo(tradeNo)
                .direction(positionDirection)
                .entryTime(entryTime)
                .exitTime(exitTime)
                .entryPrice(entryPrice)
                .exitPrice(exitPrice)
                .pnl(pnl)
                .pnlPercent(pnlPercent - request.getFeePercent() * 2) // 双边手续费
                .exitReason(reason)
                .strategyName(strategy.getName())
                .entryReason(entryReason != null && entryReason.length() > 100
                        ? entryReason.substring(0, 100) : entryReason)
                .holdBars(barIndex - entryBarIndex)
                .build());

        // 更新峰值
        if (capital > peakCapital) peakCapital = capital;

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
            // 多仓：low 触及止损，high 触及止盈
            if (request.getStopLossPercent() > 0) {
                double stopPrice = entryPrice * (1 - request.getStopLossPercent() / 100);
                if (low <= stopPrice) return Trade.ExitReason.STOP_LOSS;
            }
            if (request.getTakeProfitPercent() > 0) {
                double tpPrice = entryPrice * (1 + request.getTakeProfitPercent() / 100);
                if (high >= tpPrice) return Trade.ExitReason.TAKE_PROFIT;
            }
        } else {
            // 空仓：high 触及止损，low 触及止盈
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
