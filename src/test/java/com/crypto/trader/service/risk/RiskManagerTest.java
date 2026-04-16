package com.crypto.trader.service.risk;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RiskManagerTest {

    @Test
    void shouldAllowTradeWithinLimits() {
        RiskManager rm = new RiskManager(RiskConfig.defaults(), 10000);
        assertTrue(rm.canOpenPosition());
        assertEquals(1.0, rm.getPositionScaleFactor());
    }

    @Test
    void shouldTripDrawdownBreaker() {
        RiskConfig config = RiskConfig.builder().maxDrawdownPercent(10).build();
        RiskManager rm = new RiskManager(config, 10000);

        // 亏损 1500 → 回撤 15% > 10% 阈值
        rm.recordTrade(-1500, Instant.now());

        assertTrue(rm.isDrawdownBreakerTripped());
        assertFalse(rm.canOpenPosition());
        assertEquals(1, rm.getDrawdownTriggerCount());
    }

    @Test
    void shouldNotTripBreakerBelowThreshold() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(20)
                .dailyLossLimitPercent(100) // 不限日亏损
                .consecutiveLossPauseThreshold(100)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        rm.recordTrade(-500, Instant.now());

        assertFalse(rm.isDrawdownBreakerTripped());
        assertTrue(rm.canOpenPosition());
    }

    @Test
    void shouldTrackConsecutiveLosses() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(100) // 不会触发
                .consecutiveLossThreshold(3)
                .positionReductionFactor(0.5)
                .consecutiveLossPauseThreshold(5)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        // 3笔亏损
        rm.recordTrade(-100, Instant.now());
        rm.recordTrade(-100, Instant.now());
        rm.recordTrade(-100, Instant.now());
        assertEquals(3, rm.getConsecutiveLosses());
        assertEquals(0.5, rm.getPositionScaleFactor()); // 0.5^1

        // 第4笔亏损
        rm.recordTrade(-100, Instant.now());
        assertEquals(0.25, rm.getPositionScaleFactor()); // 0.5^2

        // 一笔盈利重置连败
        rm.recordTrade(200, Instant.now());
        assertEquals(0, rm.getConsecutiveLosses());
        assertEquals(1.0, rm.getPositionScaleFactor());
    }

    @Test
    void shouldPauseAfterConsecutiveLossThreshold() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(100)
                .consecutiveLossPauseThreshold(3)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        rm.recordTrade(-100, Instant.now());
        rm.recordTrade(-100, Instant.now());
        assertTrue(rm.canOpenPosition());

        rm.recordTrade(-100, Instant.now());
        assertFalse(rm.canOpenPosition()); // 连败3次，暂停
    }

    @Test
    void shouldEnforceDailyLossLimit() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(100)
                .dailyLossLimitPercent(5)
                .consecutiveLossPauseThreshold(100)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        // 亏损 600 → 日亏损 6% > 5%
        rm.recordTrade(-600, Instant.now());
        assertFalse(rm.canOpenPosition());
    }

    @Test
    void shouldResetDailyLimit() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(100)
                .dailyLossLimitPercent(5)
                .consecutiveLossPauseThreshold(100)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        rm.recordTrade(-600, Instant.now());
        assertFalse(rm.canOpenPosition());

        // 新的一天
        rm.resetDaily(Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1));
        assertTrue(rm.canOpenPosition());
    }

    @Test
    void shouldScalePositionMinimum() {
        RiskConfig config = RiskConfig.builder()
                .maxDrawdownPercent(100)
                .consecutiveLossThreshold(1)
                .positionReductionFactor(0.1) // 极端缩仓
                .consecutiveLossPauseThreshold(100)
                .build();
        RiskManager rm = new RiskManager(config, 10000);

        // 连败10次
        for (int i = 0; i < 10; i++) {
            rm.recordTrade(-10, Instant.now());
        }

        // 缩仓因子不低于 0.1
        assertTrue(rm.getPositionScaleFactor() >= 0.1);
    }
}
