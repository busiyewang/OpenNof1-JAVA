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
 * 特征工程服务 — 基于 TA4J 从 K 线和链上数据中提取 ML 模型所需的数值特征。
 *
 * <p>改进点（对比旧版手写计算）：</p>
 * <ul>
 *   <li>所有技术指标统一由 TA4J 计算，精度更高、维护成本低</li>
 *   <li>新增 ATR(波动率)、ADX(趋势强度)、Stochastic(KDJ)、OBV(量价)、CCI、WilliamsR</li>
 *   <li>特征从 28 维扩展到 40 维，信息密度显著提升</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureEngineerService {

    private final Ta4jIndicatorService ta4jService;

    /** 特征名称列表（与 extractFeatures 返回的 float[] 顺序一一对应） */
    public static final List<String> FEATURE_NAMES = List.of(
            // 价格特征 (0-5)
            "return_1", "return_3", "return_5", "return_10", "amplitude", "close_open_ratio",
            // 成交量特征 (6-8)
            "volume_ratio_5", "volume_change_1", "volume_change_3",
            // MACD — TA4J (9-11)
            "macd_value", "macd_signal", "macd_histogram",
            // 布林带 — TA4J (12-14)
            "bb_position", "bb_width", "price_vs_bb_mid",
            // RSI — TA4J (15-16)
            "rsi_14", "rsi_7",
            // ATR — TA4J 新增 (17-18)
            "atr_14", "atr_percent",
            // ADX — TA4J 新增 (19)
            "adx_14",
            // K线形态 (20-22)
            "upper_shadow_ratio", "lower_shadow_ratio", "body_ratio",
            // 移动均线 — TA4J (23-27)
            "sma5_slope", "sma20_slope", "price_vs_sma20", "ema12_vs_ema26", "sma5_vs_sma20",
            // Stochastic KDJ — TA4J 新增 (28-29)
            "stoch_k", "stoch_d",
            // OBV — TA4J 新增 (30-31)
            "obv_slope_5", "obv_direction",
            // CCI — TA4J 新增 (32)
            "cci_20",
            // Williams %R — TA4J 新增 (33)
            "williams_r_14",
            // 链上数据 (34-39)
            "whale_volume", "nupl", "sopr", "exchange_net_flow", "exchange_inflow", "exchange_outflow"
    );

    public static final int FEATURE_COUNT = FEATURE_NAMES.size();

    /** 训练所需的最小 K 线数 */
    public static final int MIN_KLINES = 30;

    /**
     * 从一组 K 线数据的最后一根位置提取特征向量。
     *
     * @param klines      按时间正序排列的 K 线（至少 30 根）
     * @param onChainData 链上指标（可为空 map）
     * @return float[FEATURE_COUNT]，或 null（数据不足时）
     */
    public float[] extractFeatures(List<Kline> klines, Map<String, BigDecimal> onChainData) {
        if (klines == null || klines.size() < MIN_KLINES) return null;

        // 一次性通过 TA4J 计算所有指标
        IndicatorSnapshot snap = ta4jService.calculateAll(klines);
        if (snap == null) return null;

        float[] features = new float[FEATURE_COUNT];
        int last = klines.size() - 1;

        // === 价格特征 ===
        features[0] = (float) calcReturn(klines, last, 1);
        features[1] = (float) calcReturn(klines, last, 3);
        features[2] = (float) calcReturn(klines, last, 5);
        features[3] = (float) calcReturn(klines, last, 10);
        features[4] = (float) calcAmplitude(klines.get(last));
        features[5] = (float) calcCloseOpenRatio(klines.get(last));

        // === 成交量特征 ===
        features[6] = (float) calcVolumeRatio(klines, last, 5);
        features[7] = (float) calcVolumeChange(klines, last, 1);
        features[8] = (float) calcVolumeChange(klines, last, 3);

        // === MACD (TA4J) ===
        features[9] = (float) snap.macd;
        features[10] = (float) snap.macdSignal;
        features[11] = (float) snap.macdHistogram;

        // === 布林带 (TA4J) ===
        features[12] = (float) snap.bbPosition;
        features[13] = (float) snap.bbWidth;
        features[14] = snap.bbMiddle > 0
                ? (float) ((snap.close - snap.bbMiddle) / snap.bbMiddle * 100) : 0f;

        // === RSI (TA4J) ===
        features[15] = (float) snap.rsi14;
        features[16] = (float) snap.rsi7;

        // === ATR (TA4J) — 波动率衡量 ===
        features[17] = (float) snap.atr14;
        features[18] = (float) snap.atrPercent;

        // === ADX (TA4J) — 趋势强度 ===
        features[19] = (float) snap.adx14;

        // === K线形态 ===
        Kline bar = klines.get(last);
        double high = bar.getHigh().doubleValue();
        double low = bar.getLow().doubleValue();
        double open = bar.getOpen().doubleValue();
        double close = bar.getClose().doubleValue();
        double range = high - low;
        if (range > 0) {
            double bodyTop = Math.max(open, close);
            double bodyBottom = Math.min(open, close);
            features[20] = (float) ((high - bodyTop) / range);
            features[21] = (float) ((bodyBottom - low) / range);
            features[22] = (float) ((bodyTop - bodyBottom) / range);
        }

        // === 移动均线 (TA4J) ===
        // SMA5 斜率: 用当前 SMA5 vs 前一根的近似
        features[23] = snap.sma5 > 0
                ? (float) ((snap.close - snap.sma5) / snap.sma5 * 100) : 0f;
        // SMA20 斜率
        features[24] = snap.sma20 > 0
                ? (float) ((snap.sma10 - snap.sma20) / snap.sma20 * 100) : 0f;
        // 价格偏离 SMA20
        features[25] = snap.sma20 > 0
                ? (float) ((snap.close - snap.sma20) / snap.sma20 * 100) : 0f;
        // EMA12 vs EMA26（趋势方向）
        features[26] = snap.ema26 > 0
                ? (float) ((snap.ema12 - snap.ema26) / snap.ema26 * 100) : 0f;
        // SMA5 vs SMA20（短期 vs 中期）
        features[27] = snap.sma20 > 0
                ? (float) ((snap.sma5 - snap.sma20) / snap.sma20 * 100) : 0f;

        // === Stochastic KDJ (TA4J) ===
        features[28] = (float) snap.stochK;
        features[29] = (float) snap.stochD;

        // === OBV (TA4J) ===
        features[30] = (float) snap.obvSlope5;
        features[31] = snap.obvSlope5 > 0 ? 1f : (snap.obvSlope5 < 0 ? -1f : 0f);

        // === CCI (TA4J) ===
        features[32] = (float) snap.cci20;

        // === Williams %R (TA4J) ===
        features[33] = (float) snap.williamsR14;

        // === 链上数据 ===
        if (onChainData != null) {
            features[34] = safeFloat(onChainData.get("whale_transfer_volume"));
            features[35] = safeFloat(onChainData.get("nupl"));
            features[36] = safeFloat(onChainData.get("sopr"));
            features[37] = safeFloat(onChainData.get("exchange_net_flow"));
            features[38] = safeFloat(onChainData.get("exchange_inflow"));
            features[39] = safeFloat(onChainData.get("exchange_outflow"));
        }

        // NaN/Infinity 清洗：防止异常值导致 ML 训练失败
        for (int i = 0; i < features.length; i++) {
            if (Float.isNaN(features[i]) || Float.isInfinite(features[i])) {
                features[i] = 0f;
            }
        }

        return features;
    }

    /**
     * 根据未来 N 根 K 线的加权收益生成标签。
     *
     * <p>改进点：旧版只看下一根K线（噪声大），新版看未来多根K线加权平均，
     * 近期权重大、远期权重小（指数衰减），信号更稳定。</p>
     *
     * @param futureKlines 未来 N 根 K 线列表（按时间正序）
     * @param currentClose 当前收盘价
     * @param threshold    涨跌幅阈值（%）
     * @return 0=跌, 1=横盘, 2=涨
     */
    public int generateLabel(List<Kline> futureKlines, double currentClose, double threshold) {
        if (futureKlines == null || futureKlines.isEmpty() || currentClose <= 0) return 1;

        // 指数衰减加权：第1根权重=1.0, 第2根=0.7, 第3根=0.49, ...
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

        if (avgReturn > threshold) return 2;  // 涨
        if (avgReturn < -threshold) return 0; // 跌
        return 1; // 横盘
    }

    /**
     * 兼容旧接口：单根 K 线标签（内部转为 list 调用）。
     */
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

    // ====================== 价格/成交量基础计算 ======================

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
        double current = klines.get(idx).getVolume().doubleValue();
        double sum = 0;
        int count = 0;
        for (int i = idx - lookback; i < idx; i++) {
            if (i >= 0) {
                sum += klines.get(i).getVolume().doubleValue();
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0;
        return avg > 0 ? current / avg : 1;
    }

    private double calcVolumeChange(List<Kline> klines, int idx, int lookback) {
        int prev = idx - lookback;
        if (prev < 0) return 0;
        double v0 = klines.get(prev).getVolume().doubleValue();
        double v1 = klines.get(idx).getVolume().doubleValue();
        return v0 > 0 ? (v1 - v0) / v0 * 100 : 0;
    }

    private float safeFloat(BigDecimal val) {
        if (val == null) return 0f;
        return val.floatValue();
    }
}
