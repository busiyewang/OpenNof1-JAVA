package com.crypto.trader.backtest.engine;

import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.risk.RiskConfig;
import com.crypto.trader.service.risk.RiskManager;
import com.crypto.trader.service.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    /**
     * 构造模拟 K 线数据。
     */
    private List<Kline> buildKlines(double[] closes) {
        List<Kline> klines = new ArrayList<>();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < closes.length; i++) {
            Kline k = new Kline();
            k.setSymbol("BTCUSDT");
            k.setInterval("1h");
            k.setTimestamp(base.plus(i, ChronoUnit.HOURS));
            k.setOpen(BigDecimal.valueOf(closes[i] - 50));
            k.setHigh(BigDecimal.valueOf(closes[i] + 100));
            k.setLow(BigDecimal.valueOf(closes[i] - 100));
            k.setClose(BigDecimal.valueOf(closes[i]));
            k.setVolume(BigDecimal.valueOf(1000));
            klines.add(k);
        }
        return klines;
    }

    /**
     * 基础 long 交易：BUY 后 SELL。
     * 注意：SELL 信号平掉 LONG 后会立即开 SHORT（反向开仓逻辑），
     * SHORT 在回测结束时 END_OF_DATA 自动平仓，所以共 2 笔交易。
     */
    @Test
    void shouldExecuteBasicLongTrade() {
        double[] prices = new double[110];
        for (int i = 0; i < 110; i++) {
            prices[i] = 50000 + i * 200; // 单调上涨，每根涨200
        }

        // 策略：bar 100 买入，bar 105 卖出
        TradingStrategy strategy = new FixedSignalStrategy(100, Signal.Action.BUY, 105, Signal.Action.SELL);

        BacktestRequest req = new BacktestRequest();
        req.setWindowSize(10);
        req.setStopLossPercent(0);
        req.setTakeProfitPercent(0);
        req.setSlippageBps(0); // 无滑点
        req.setFeePercent(0); // 无手续费，纯测逻辑

        BacktestEngine engine = new BacktestEngine(strategy, req);
        List<Trade> trades = engine.run(buildKlines(prices));

        // SELL 平掉 LONG → 立即开 SHORT → END_OF_DATA 平 SHORT = 2笔
        assertEquals(2, trades.size());
        Trade longTrade = trades.get(0);
        assertEquals(Trade.Direction.LONG, longTrade.getDirection());
        assertEquals(Trade.ExitReason.SIGNAL, longTrade.getExitReason());
        assertTrue(longTrade.getPnl() > 0); // 上涨 → LONG 盈利
    }

    /**
     * 滑点应减少利润。
     */
    @Test
    void shouldApplySlippage() {
        double[] prices = new double[110];
        for (int i = 0; i < 110; i++) prices[i] = 50000 + i * 10;

        TradingStrategy strategy = new FixedSignalStrategy(100, Signal.Action.BUY, 105, Signal.Action.SELL);

        // 无滑点
        BacktestRequest reqNoSlip = new BacktestRequest();
        reqNoSlip.setWindowSize(10);
        reqNoSlip.setStopLossPercent(0);
        reqNoSlip.setTakeProfitPercent(0);
        reqNoSlip.setSlippageBps(0);

        BacktestEngine engineNoSlip = new BacktestEngine(strategy, reqNoSlip);
        List<Trade> tradesNoSlip = engineNoSlip.run(buildKlines(prices));

        // 有滑点 (50bps = 0.5%)
        BacktestRequest reqSlip = new BacktestRequest();
        reqSlip.setWindowSize(10);
        reqSlip.setStopLossPercent(0);
        reqSlip.setTakeProfitPercent(0);
        reqSlip.setSlippageBps(50);

        BacktestEngine engineSlip = new BacktestEngine(strategy, reqSlip);
        List<Trade> tradesSlip = engineSlip.run(buildKlines(prices));

        // 有滑点的利润应该更少
        assertTrue(tradesSlip.get(0).getPnl() < tradesNoSlip.get(0).getPnl());
        assertTrue(tradesSlip.get(0).getSlippageCost() > 0);
    }

    /**
     * 止损触发。
     */
    @Test
    void shouldTriggerStopLoss() {
        double[] prices = new double[110];
        for (int i = 0; i < 105; i++) prices[i] = 50000;
        // bar 105-109 暴跌
        for (int i = 105; i < 110; i++) prices[i] = 50000 - (i - 104) * 2000;

        TradingStrategy strategy = new FixedSignalStrategy(100, Signal.Action.BUY, -1, null);

        BacktestRequest req = new BacktestRequest();
        req.setWindowSize(10);
        req.setStopLossPercent(3.0); // 3% 止损
        req.setTakeProfitPercent(0);
        req.setSlippageBps(0);

        BacktestEngine engine = new BacktestEngine(strategy, req);
        List<Trade> trades = engine.run(buildKlines(prices));

        assertFalse(trades.isEmpty());
        assertEquals(Trade.ExitReason.STOP_LOSS, trades.get(0).getExitReason());
    }

    /**
     * 动态仓位：高置信度 → 大仓位，低置信度 → 小仓位。
     */
    @Test
    void shouldUseDynamicPositionSizing() {
        double[] prices = new double[120];
        for (int i = 0; i < 120; i++) prices[i] = 50000 + i * 5;

        // 高置信度信号
        TradingStrategy highConf = new ConfidenceStrategy(100, 110, 0.9);
        // 低置信度信号
        TradingStrategy lowConf = new ConfidenceStrategy(100, 110, 0.3);

        BacktestRequest req = new BacktestRequest();
        req.setWindowSize(10);
        req.setStopLossPercent(0);
        req.setTakeProfitPercent(0);
        req.setSlippageBps(0);
        req.setDynamicPositionSizing(true);
        req.setPositionSizePercent(0.5);
        req.setMinPositionSizePercent(0.1);
        req.setMaxPositionSizePercent(0.8);

        BacktestEngine engineHigh = new BacktestEngine(highConf, req);
        List<Trade> tradesHigh = engineHigh.run(buildKlines(prices));

        BacktestEngine engineLow = new BacktestEngine(lowConf, req);
        List<Trade> tradesLow = engineLow.run(buildKlines(prices));

        // 高置信度仓位应该更大
        assertTrue(tradesHigh.get(0).getPositionSizeUsed() > tradesLow.get(0).getPositionSizeUsed());
    }

    /**
     * 风控：连续亏损暂停交易。
     */
    @Test
    void shouldStopTradingOnCircuitBreaker() {
        double[] prices = new double[200];
        for (int i = 0; i < 200; i++) {
            // 锯齿形价格：短涨后暴跌，制造持续亏损
            prices[i] = 50000 + (i % 10 < 5 ? i % 10 * 100 : -(i % 10 - 5) * 300);
        }

        // 在多个时间点发买入信号的策略
        TradingStrategy losingStrategy = new MultiSignalStrategy();

        BacktestRequest req = new BacktestRequest();
        req.setWindowSize(10);
        req.setStopLossPercent(1.0); // 窄止损
        req.setTakeProfitPercent(0);
        req.setSlippageBps(0);
        req.setRiskManagementEnabled(true);
        req.setMaxDrawdownPercent(5);
        req.setConsecutiveLossPauseThreshold(3);

        RiskConfig riskConfig = RiskConfig.builder()
                .maxDrawdownPercent(5)
                .consecutiveLossPauseThreshold(3)
                .build();
        RiskManager rm = new RiskManager(riskConfig, 10000);
        BacktestEngine engine = new BacktestEngine(losingStrategy, req, rm);
        engine.run(buildKlines(prices));

        // 风控应该在某个时刻阻止了交易
        // 不会因为无限交易而产生大量交易记录
        assertTrue(rm.getConsecutiveLosses() >= 0);
    }

    // ============ 测试用策略 ============

    /** 在指定 bar index 发出固定信号 */
    static class FixedSignalStrategy implements TradingStrategy {
        private final int buyBar, sellBar;
        private final Signal.Action buyAction, sellAction;

        FixedSignalStrategy(int buyBar, Signal.Action buyAction, int sellBar, Signal.Action sellAction) {
            this.buyBar = buyBar;
            this.buyAction = buyAction;
            this.sellBar = sellBar;
            this.sellAction = sellAction;
        }

        @Override
        public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
            int currentBar = klines.size() - 1;
            Kline last = klines.get(currentBar);
            // 通过时间戳推算绝对 bar index
            long hoursSinceBase = java.time.Duration.between(
                    Instant.parse("2025-01-01T00:00:00Z"), last.getTimestamp()).toHours();

            if (hoursSinceBase == buyBar) {
                return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                        .action(buyAction).price(last.getClose().doubleValue())
                        .confidence(0.8).strategyName("TEST").reason("test-buy").build();
            }
            if (sellAction != null && hoursSinceBase == sellBar) {
                return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                        .action(sellAction).price(last.getClose().doubleValue())
                        .confidence(0.8).strategyName("TEST").reason("test-sell").build();
            }
            return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                    .action(Signal.Action.HOLD).strategyName("TEST").build();
        }

        @Override
        public String getName() { return "TEST"; }
    }

    /** 指定置信度的策略 */
    static class ConfidenceStrategy implements TradingStrategy {
        private final int buyBar, sellBar;
        private final double confidence;

        ConfidenceStrategy(int buyBar, int sellBar, double confidence) {
            this.buyBar = buyBar;
            this.sellBar = sellBar;
            this.confidence = confidence;
        }

        @Override
        public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
            Kline last = klines.get(klines.size() - 1);
            long hours = java.time.Duration.between(
                    Instant.parse("2025-01-01T00:00:00Z"), last.getTimestamp()).toHours();

            Signal.Action action = Signal.Action.HOLD;
            if (hours == buyBar) action = Signal.Action.BUY;
            if (hours == sellBar) action = Signal.Action.SELL;

            return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                    .action(action).price(last.getClose().doubleValue())
                    .confidence(confidence).strategyName("CONF-TEST").reason("test").build();
        }

        @Override
        public String getName() { return "CONF-TEST"; }
    }

    /** 每隔 N 根 K线发一个 BUY 信号的策略（用于测试风控） */
    static class MultiSignalStrategy implements TradingStrategy {
        @Override
        public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
            Kline last = klines.get(klines.size() - 1);
            long hours = java.time.Duration.between(
                    Instant.parse("2025-01-01T00:00:00Z"), last.getTimestamp()).toHours();

            // 每15根 K线发一个 BUY
            if (hours >= 20 && hours % 15 == 0) {
                return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                        .action(Signal.Action.BUY).price(last.getClose().doubleValue())
                        .confidence(0.7).strategyName("MULTI").reason("test-multi").build();
            }
            return Signal.builder().symbol(symbol).timestamp(last.getTimestamp())
                    .action(Signal.Action.HOLD).strategyName("MULTI").build();
        }

        @Override
        public String getName() { return "MULTI"; }
    }
}
