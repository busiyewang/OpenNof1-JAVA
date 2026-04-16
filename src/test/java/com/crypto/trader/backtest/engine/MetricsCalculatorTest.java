package com.crypto.trader.backtest.engine;

import com.crypto.trader.backtest.model.BacktestReport;
import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCalculatorTest {

    private BacktestRequest defaultRequest() {
        BacktestRequest req = new BacktestRequest();
        req.setInterval("1h");
        req.setStartDate("2025-01-01");
        req.setEndDate("2025-02-01");
        return req;
    }

    @Test
    void shouldCalculateForZeroTrades() {
        BacktestReport report = MetricsCalculator.calculate(
                "TEST", defaultRequest(), List.of(), 10000, 100);

        assertEquals(0, report.getTotalTrades());
        assertEquals(0, report.getWinRate());
        assertEquals(0, report.getMaxDrawdownPercent());
        assertEquals(10000, report.getFinalCapital());
    }

    @Test
    void shouldCalculateForAllWinningTrades() {
        List<Trade> trades = List.of(
                Trade.builder().tradeNo(1).pnl(500).pnlPercent(5).holdBars(10)
                        .direction(Trade.Direction.LONG).entryPrice(50000).exitPrice(52500)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.SIGNAL).strategyName("T").build(),
                Trade.builder().tradeNo(2).pnl(300).pnlPercent(3).holdBars(8)
                        .direction(Trade.Direction.LONG).entryPrice(52000).exitPrice(53560)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.SIGNAL).strategyName("T").build()
        );

        BacktestReport report = MetricsCalculator.calculate(
                "TEST", defaultRequest(), trades, 10800, 100);

        assertEquals(2, report.getTotalTrades());
        assertEquals(2, report.getWinTrades());
        assertEquals(0, report.getLossTrades());
        assertEquals(100.0, report.getWinRate());
        assertEquals(0, report.getMaxDrawdownPercent());
        assertEquals(0, report.getMaxConsecutiveLosses());
    }

    @Test
    void shouldCalculateForAllLosingTrades() {
        List<Trade> trades = List.of(
                Trade.builder().tradeNo(1).pnl(-200).pnlPercent(-2).holdBars(5)
                        .direction(Trade.Direction.LONG).entryPrice(50000).exitPrice(49000)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.STOP_LOSS).strategyName("T").build(),
                Trade.builder().tradeNo(2).pnl(-300).pnlPercent(-3).holdBars(4)
                        .direction(Trade.Direction.SHORT).entryPrice(49000).exitPrice(50470)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.STOP_LOSS).strategyName("T").build(),
                Trade.builder().tradeNo(3).pnl(-100).pnlPercent(-1).holdBars(3)
                        .direction(Trade.Direction.LONG).entryPrice(48000).exitPrice(47520)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.STOP_LOSS).strategyName("T").build()
        );

        BacktestReport report = MetricsCalculator.calculate(
                "TEST", defaultRequest(), trades, 9400, 100);

        assertEquals(0, report.getWinRate());
        assertEquals(3, report.getMaxConsecutiveLosses());
        assertTrue(report.getMaxDrawdownPercent() > 0);
    }

    @Test
    void shouldCalculateMaxDrawdownCorrectly() {
        List<Trade> trades = List.of(
                Trade.builder().tradeNo(1).pnl(1000).pnlPercent(10).holdBars(5)
                        .direction(Trade.Direction.LONG).entryPrice(50000).exitPrice(55000)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.SIGNAL).strategyName("T").build(),
                // 资金: 11000 (peak)
                Trade.builder().tradeNo(2).pnl(-2200).pnlPercent(-20).holdBars(5)
                        .direction(Trade.Direction.LONG).entryPrice(55000).exitPrice(44000)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.STOP_LOSS).strategyName("T").build()
                // 资金: 8800, drawdown = (11000-8800)/11000 = 20%
        );

        BacktestReport report = MetricsCalculator.calculate(
                "TEST", defaultRequest(), trades, 8800, 100);

        assertEquals(20.0, report.getMaxDrawdownPercent());
    }

    @Test
    void shouldIncludeSlippageAndPositionStats() {
        List<Trade> trades = List.of(
                Trade.builder().tradeNo(1).pnl(100).pnlPercent(1).holdBars(5)
                        .direction(Trade.Direction.LONG).entryPrice(50000).exitPrice(50500)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.SIGNAL).strategyName("T")
                        .slippageCost(25).positionSizeUsed(0.4).build(),
                Trade.builder().tradeNo(2).pnl(200).pnlPercent(2).holdBars(8)
                        .direction(Trade.Direction.LONG).entryPrice(50500).exitPrice(51510)
                        .entryTime(Instant.now()).exitTime(Instant.now())
                        .exitReason(Trade.ExitReason.SIGNAL).strategyName("T")
                        .slippageCost(30).positionSizeUsed(0.6).build()
        );

        BacktestReport report = MetricsCalculator.calculate(
                "TEST", defaultRequest(), trades, 10300, 100);

        assertEquals(55.0, report.getTotalSlippageCost());
        assertEquals(0.5, report.getAvgPositionSizePercent());
    }
}
