package com.crypto.trader.service.ml;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.service.indicator.Ta4jIndicatorService;
import com.crypto.trader.service.indicator.Ta4jIndicatorService.IndicatorSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 多时间框架特征工程服务。
 *
 * <p>从 1h + 4h + 1d 三个时间框架提取 TA4J 指标，加上价格/成交量/K线形态/链上数据。</p>
 * <ul>
 *   <li>每个时间框架提取 22 个 TA4J 指标特征</li>
 *   <li>主时间框架(1h)额外提取 9 个价格/成交量/K线形态特征</li>
 *   <li>6 个链上数据特征</li>
 *   <li>总计: 9 + 22*3 + 6 = 81 维特征</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureEngineerService {

    private final Ta4jIndicatorService ta4jService;

    /** 每个时间框架的 TA4J 指标特征名（22个） */
    private static final List<String> TF_INDICATOR_NAMES = List.of(
            "macd_value", "macd_signal", "macd_histogram",
            "bb_position", "bb_width", "price_vs_bb_mid",
            "rsi_14", "rsi_7",
            "atr_14", "atr_percent",
            "adx_14",
            "sma5_slope", "sma20_slope", "price_vs_sma20", "ema12_vs_ema26", "sma5_vs_sma20",
            "stoch_k", "stoch_d",
            "obv_slope_5",
            "cci_20",
            "williams_r_14",
            "obv_direction"
    );

    /** 完整特征名称列表 */
    public static final List<String> FEATURE_NAMES;
    public static final int FEATURE_COUNT;

    static {
        List<String> names = new ArrayList<>();
        // 1. 主时间框架(1h)价格+成交量+K线形态 (9个)
        names.addAll(List.of(
                "return_1", "return_3", "return_5", "return_10",
                "amplitude", "close_open_ratio",
                "volume_ratio_5", "volume_change_1", "volume_change_3"
        ));
        // 2. 三个时间框架的TA4J指标 (22*3=66个)
        for (String tf : List.of("1h", "4h", "1d")) {
            for (String ind : TF_INDICATOR_NAMES) {
                names.add(tf + "_" + ind);
            }
        }
        // 3. 链上数据 (6个)
        names.addAll(List.of(
                "whale_volume", "nupl", "sopr",
                "exchange_net_flow", "exchange_inflow", "exchange_outflow"
        ));
        FEATURE_NAMES = Collections.unmodifiableList(names);
        FEATURE_COUNT = FEATURE_NAMES.size(); // 9 + 66 + 6 = 81
    }

    /** 训练所需的最小 K 线数（主时间框架） */
    public static final int MIN_KLINES = 30;

    /**
     * 多时间框架特征提取（训练和预测主入口）。
     *
     * @param klinesByTf  key=时间框架("1h","4h","1d"), value=按时间正序的K线列表
     * @param onChainData 链上指标最新值
     * @return float[FEATURE_COUNT]，数据不足返回 null
     */
    public float[] extractMultiTfFeatures(Map<String, List<Kline>> klinesByTf,
                                           Map<String, BigDecimal> onChainData) {
        List<Kline> klines1h = klinesByTf.get("1h");
        if (klines1h == null || klines1h.size() < MIN_KLINES) return null;

        float[] features = new float[FEATURE_COUNT];
        int offset = 0;

        // === 1. 主时间框架价格+成交量特征 (9个) ===
        int last = klines1h.size() - 1;
        features[offset++] = (float) calcReturn(klines1h, last, 1);
        features[offset++] = (float) calcReturn(klines1h, last, 3);
        features[offset++] = (float) calcReturn(klines1h, last, 5);
        features[offset++] = (float) calcReturn(klines1h, last, 10);
        features[offset++] = (float) calcAmplitude(klines1h.get(last));
        features[offset++] = (float) calcCloseOpenRatio(klines1h.get(last));
        features[offset++] = (float) calcVolumeRatio(klines1h, last, 5);
        features[offset++] = (float) calcVolumeChange(klines1h, last, 1);
        features[offset++] = (float) calcVolumeChange(klines1h, last, 3);

        // === 2. 三个时间框架的TA4J指标 (22*3=66个) ===
        for (String tf : List.of("1h", "4h", "1d")) {
            List<Kline> klines = klinesByTf.get(tf);
            if (klines != null && klines.size() >= MIN_KLINES) {
                offset = fillIndicatorFeatures(features, offset, klines);
            } else {
                // 数据不足，填0
                offset += TF_INDICATOR_NAMES.size();
            }
        }

        // === 3. 链上数据 (6个) ===
        if (onChainData != null) {
            features[offset++] = safeFloat(onChainData.get("whale_transfer_volume"));
            features[offset++] = safeFloat(onChainData.get("nupl"));
            features[offset++] = safeFloat(onChainData.get("sopr"));
            features[offset++] = safeFloat(onChainData.get("exchange_net_flow"));
            features[offset++] = safeFloat(onChainData.get("exchange_inflow"));
            features[offset++] = safeFloat(onChainData.get("exchange_outflow"));
        }

        // NaN/Infinity 清洗
        for (int i = 0; i < features.length; i++) {
            if (Float.isNaN(features[i]) || Float.isInfinite(features[i])) {
                features[i] = 0f;
            }
        }

        return features;
    }

    /**
     * 兼容旧接口：单时间框架特征提取（策略用）。
     */
    public float[] extractFeatures(List<Kline> klines, Map<String, BigDecimal> onChainData) {
        return extractMultiTfFeatures(Map.of("1h", klines), onChainData);
    }

    /**
     * 填充单个时间框架的22个TA4J指标到features数组。
     */
    private int fillIndicatorFeatures(float[] features, int offset, List<Kline> klines) {
        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        if (snap == null) {
            return offset + TF_INDICATOR_NAMES.size();
        }

        features[offset++] = (float) snap.macd;
        features[offset++] = (float) snap.macdSignal;
        features[offset++] = (float) snap.macdHistogram;

        features[offset++] = (float) snap.bbPosition;
        features[offset++] = (float) snap.bbWidth;
        features[offset++] = snap.bbMiddle > 0
                ? (float) ((snap.close - snap.bbMiddle) / snap.bbMiddle * 100) : 0f;

        features[offset++] = (float) snap.rsi14;
        features[offset++] = (float) snap.rsi7;

        features[offset++] = (float) snap.atr14;
        features[offset++] = (float) snap.atrPercent;

        features[offset++] = (float) snap.adx14;

        features[offset++] = snap.sma5 > 0
                ? (float) ((snap.close - snap.sma5) / snap.sma5 * 100) : 0f;
        features[offset++] = snap.sma20 > 0
                ? (float) ((snap.sma10 - snap.sma20) / snap.sma20 * 100) : 0f;
        features[offset++] = snap.sma20 > 0
                ? (float) ((snap.close - snap.sma20) / snap.sma20 * 100) : 0f;
        features[offset++] = snap.ema26 > 0
                ? (float) ((snap.ema12 - snap.ema26) / snap.ema26 * 100) : 0f;
        features[offset++] = snap.sma20 > 0
                ? (float) ((snap.sma5 - snap.sma20) / snap.sma20 * 100) : 0f;

        features[offset++] = (float) snap.stochK;
        features[offset++] = (float) snap.stochD;

        features[offset++] = (float) snap.obvSlope5;

        features[offset++] = (float) snap.cci20;

        features[offset++] = (float) snap.williamsR14;

        features[offset++] = snap.obvSlope5 > 0 ? 1f : (snap.obvSlope5 < 0 ? -1f : 0f);

        return offset;
    }

    // ====================== 标签生成 ======================

    /**
     * 根据未来 N 根 K 线的加权收益生成标签。
     */
    public int generateLabel(List<Kline> futureKlines, double currentClose, double threshold) {
        if (futureKlines == null || futureKlines.isEmpty() || currentClose <= 0) return 1;

        double decay = 0.7;
        double weightedReturn = 0;
        double totalWeight = 0;

        for (int i = 0; i < futureKlines.size(); i++) {
            double futureClose = futureKlines.get(i).getClose().doubleValue();
            double ret = (futureClose - currentClose) / currentClose * 100;
            double weight = Math.pow(decay, i);
            weightedReturn += ret * weight;
            totalWeight += weight;
        }

        double avgReturn = totalWeight > 0 ? weightedReturn / totalWeight : 0;
        if (avgReturn > threshold) return 2;
        if (avgReturn < -threshold) return 0;
        return 1;
    }

    public int generateLabel(Kline nextKline, double currentClose, double threshold) {
        return generateLabel(List.of(nextKline), currentClose, threshold);
    }

    /**
     * 从链上指标列表中提取最新值，构建 name→value 的 Map。
     */
    public Map<String, BigDecimal> buildOnChainMap(List<OnChainMetric> metrics) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (metrics == null) return map;
        for (OnChainMetric m : metrics) {
            map.putIfAbsent(m.getMetricName(), m.getValue());
        }
        return map;
    }

    // ====================== 基础计算 ======================

    private double calcReturn(List<Kline> klines, int idx, int lookback) {
        int prev = idx - lookback;
        if (prev < 0) return 0;
        double c0 = klines.get(prev).getClose().doubleValue();
        double c1 = klines.get(idx).getClose().doubleValue();
        return c0 > 0 ? (c1 - c0) / c0 * 100 : 0;
    }

    private double calcAmplitude(Kline k) {
        double open = k.getOpen().doubleValue();
        if (open <= 0) return 0;
        return (k.getHigh().doubleValue() - k.getLow().doubleValue()) / open * 100;
    }

    private double calcCloseOpenRatio(Kline k) {
        double open = k.getOpen().doubleValue();
        if (open <= 0) return 1;
        return k.getClose().doubleValue() / open;
    }

    private double calcVolumeRatio(List<Kline> klines, int idx, int lookback) {
        double current = klines.get(idx).getVolume() != null ? klines.get(idx).getVolume().doubleValue() : 0;
        double sum = 0;
        int count = 0;
        for (int i = idx - lookback; i < idx; i++) {
            if (i >= 0) {
                sum += klines.get(i).getVolume() != null ? klines.get(i).getVolume().doubleValue() : 0;
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0;
        return avg > 0 ? current / avg : 1;
    }

    private double calcVolumeChange(List<Kline> klines, int idx, int lookback) {
        int prev = idx - lookback;
        if (prev < 0) return 0;
        double v0 = klines.get(prev).getVolume() != null ? klines.get(prev).getVolume().doubleValue() : 0;
        double v1 = klines.get(idx).getVolume() != null ? klines.get(idx).getVolume().doubleValue() : 0;
        return v0 > 0 ? (v1 - v0) / v0 * 100 : 0;
    }

    private float safeFloat(BigDecimal val) {
        if (val == null) return 0f;
        return val.floatValue();
    }
}
