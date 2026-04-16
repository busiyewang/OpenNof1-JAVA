package com.crypto.trader.service.ml;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeatureEngineerServiceTest {

    @Test
    void shouldGenerateBullishLabel() {
        // 未来 K 线价格上涨
        List<Kline> future = List.of(
                buildKline(52000), buildKline(53000), buildKline(54000)
        );
        FeatureEngineerService service = new FeatureEngineerService(null);
        int label = service.generateLabel(future, 50000, 0.3);
        assertEquals(2, label); // 涨
    }

    @Test
    void shouldGenerateBearishLabel() {
        List<Kline> future = List.of(
                buildKline(48000), buildKline(47000), buildKline(46000)
        );
        FeatureEngineerService service = new FeatureEngineerService(null);
        int label = service.generateLabel(future, 50000, 0.3);
        assertEquals(0, label); // 跌
    }

    @Test
    void shouldGenerateNeutralLabel() {
        List<Kline> future = List.of(
                buildKline(50010), buildKline(49990), buildKline(50005)
        );
        FeatureEngineerService service = new FeatureEngineerService(null);
        int label = service.generateLabel(future, 50000, 0.3);
        assertEquals(1, label); // 横盘
    }

    @Test
    void shouldHandleNullFutureKlines() {
        FeatureEngineerService service = new FeatureEngineerService(null);
        assertEquals(1, service.generateLabel((List<Kline>) null, 50000, 0.3));
        assertEquals(1, service.generateLabel(List.of(), 50000, 0.3));
        assertEquals(1, service.generateLabel(List.of(buildKline(50000)), 0, 0.3));
    }

    @Test
    void shouldHandleSingleKlineLabel() {
        FeatureEngineerService service = new FeatureEngineerService(null);
        Kline kline = buildKline(55000);
        int label = service.generateLabel(kline, 50000, 0.3);
        assertEquals(2, label); // 涨 10% > 0.3%
    }

    @Test
    void shouldBuildOnChainMap() {
        FeatureEngineerService service = new FeatureEngineerService(null);

        // null 输入
        Map<String, BigDecimal> emptyMap = service.buildOnChainMap(null);
        assertTrue(emptyMap.isEmpty());

        // 正常输入
        OnChainMetric metric = new OnChainMetric();
        metric.setMetricName("nupl");
        metric.setValue(BigDecimal.valueOf(0.65));
        Map<String, BigDecimal> map = service.buildOnChainMap(List.of(metric));
        assertEquals(BigDecimal.valueOf(0.65), map.get("nupl"));
    }

    @Test
    void shouldReturnNullForInsufficientData() {
        FeatureEngineerService service = new FeatureEngineerService(null);

        // 少于 MIN_KLINES 的数据
        List<Kline> tooFew = new ArrayList<>();
        for (int i = 0; i < 10; i++) tooFew.add(buildKline(50000));

        float[] features = service.extractMultiTfFeatures(
                Map.of("1h", tooFew), new HashMap<>());
        assertNull(features);
    }

    @Test
    void shouldApplyDecayWeighting() {
        FeatureEngineerService service = new FeatureEngineerService(null);

        // 第一根涨很多，后面跌
        List<Kline> future = List.of(
                buildKline(55000), // +10%
                buildKline(45000), // -10%
                buildKline(45000)  // -10%
        );
        // decay=0.7: 第一根权重最大(1.0)，第二根0.7，第三根0.49
        // 加权: (10*1.0 + (-10)*0.7 + (-10)*0.49) / (1.0 + 0.7 + 0.49) = (10 - 7 - 4.9) / 2.19 = -0.87%
        int label = service.generateLabel(future, 50000, 0.3);
        assertEquals(0, label); // 跌（衰减后第一根的涨不够抵消后面的跌）
    }

    private Kline buildKline(double close) {
        Kline k = new Kline();
        k.setClose(BigDecimal.valueOf(close));
        k.setOpen(BigDecimal.valueOf(close - 50));
        k.setHigh(BigDecimal.valueOf(close + 100));
        k.setLow(BigDecimal.valueOf(close - 100));
        k.setVolume(BigDecimal.valueOf(1000));
        k.setTimestamp(Instant.now());
        return k;
    }
}
