package com.crypto.trader.service.ml;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.service.indicator.BollingerBandsCalculator;
import com.crypto.trader.service.indicator.MacdCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 特征工程服务 — 从 K 线和链上数据中提取 XGBoost 所需的数值特征。
 *
 * <p>特征维度：</p>
 * <ul>
 *   <li>价格类: 收益率、振幅、收盘/开盘比</li>
 *   <li>成交量类: 量比、量变化率</li>
 *   <li>技术指标: MACD 三值、布林带位置、RSI</li>
 *   <li>K线形态: 上下影线比、实体占比</li>
 *   <li>链上数据: 巨鲸、NUPL、SOPR、交易所净流入</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureEngineerService {

    private final MacdCalculator macdCalculator;
    private final BollingerBandsCalculator bollingerCalculator;

    /** 特征名称列表（与 extractFeatures 返回的 float[] 顺序一一对应） */
    public static final List<String> FEATURE_NAMES = List.of(
            // 价格特征 (0-5)
            "return_1", "return_3", "return_5", "return_10", "amplitude", "close_open_ratio",
            // 成交量特征 (6-8)
            "volume_ratio_5", "volume_change_1", "volume_change_3",
            // MACD (9-11)
            "macd_value", "macd_signal", "macd_histogram",
            // 布林带 (12-14)
            "bb_position", "bb_width", "price_vs_bb_mid",
            // RSI (15)
            "rsi_14",
            // K线形态 (16-18)
            "upper_shadow_ratio", "lower_shadow_ratio", "body_ratio",
            // 移动均线 (19-21)
            "ma5_slope", "ma20_slope", "price_vs_ma20",
            // 链上数据 (22-27)
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

        float[] features = new float[FEATURE_COUNT];
        int last = klines.size() - 1;

        double closeNow = klines.get(last).getClose().doubleValue();

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

        // === MACD ===
        MacdCalculator.MacdValues macd = macdCalculator.calculate(klines);
        if (macd != null) {
            features[9] = (float) macd.macd;
            features[10] = (float) macd.signal;
            features[11] = (float) macd.histogram;
        }

        // === 布林带 ===
        BollingerBandsCalculator.BollingerValues bb = bollingerCalculator.calculate(klines);
        if (bb != null) {
            double bbWidth = bb.upper - bb.lower;
            features[12] = bbWidth > 0 ? (float) ((closeNow - bb.lower) / bbWidth) : 0.5f;
            features[13] = bb.middle > 0 ? (float) (bbWidth / bb.middle) : 0f;
            features[14] = bb.middle > 0 ? (float) ((closeNow - bb.middle) / bb.middle * 100) : 0f;
        }

        // === RSI (14周期) ===
        features[15] = (float) calcRsi(klines, 14);

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
            features[16] = (float) ((high - bodyTop) / range);
            features[17] = (float) ((bodyBottom - low) / range);
            features[18] = (float) ((bodyTop - bodyBottom) / range);
        }

        // === 移动均线 ===
        features[19] = (float) calcMaSlope(klines, last, 5);
        features[20] = (float) calcMaSlope(klines, last, 20);
        double ma20 = calcMa(klines, last, 20);
        features[21] = ma20 > 0 ? (float) ((closeNow - ma20) / ma20 * 100) : 0f;

        // === 链上数据 ===
        if (onChainData != null) {
            features[22] = safeFloat(onChainData.get("whale_transfer_volume"));
            features[23] = safeFloat(onChainData.get("nupl"));
            features[24] = safeFloat(onChainData.get("sopr"));
            features[25] = safeFloat(onChainData.get("exchange_net_flow"));
            features[26] = safeFloat(onChainData.get("exchange_inflow"));
            features[27] = safeFloat(onChainData.get("exchange_outflow"));
        }

        return features;
    }

    /**
     * 根据 K 线下一根的涨跌生成标签。
     *
     * @param nextKline 下一根 K 线
     * @param currentClose 当前收盘价
     * @param threshold 涨跌幅阈值（如 0.5 表示 0.5%）
     * @return 0=跌, 1=横盘, 2=涨
     */
    public int generateLabel(Kline nextKline, double currentClose, double threshold) {
        double nextClose = nextKline.getClose().doubleValue();
        double change = (nextClose - currentClose) / currentClose * 100;
        if (change > threshold) return 2;  // 涨
        if (change < -threshold) return 0; // 跌
        return 1; // 横盘
    }

    /**
     * 从链上指标列表中提取最新值，构建 name→value 的 Map。
     */
    public Map<String, BigDecimal> buildOnChainMap(List<OnChainMetric> metrics) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (metrics == null) return map;
        // 每种指标取第一条（最新的）
        for (OnChainMetric m : metrics) {
            map.putIfAbsent(m.getMetricName(), m.getValue());
        }
        return map;
    }

    // ====================== 内部计算方法 ======================

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

    private double calcRsi(List<Kline> klines, int period) {
        int last = klines.size() - 1;
        if (last < period) return 50;

        double gainSum = 0, lossSum = 0;
        for (int i = last - period + 1; i <= last; i++) {
            double change = klines.get(i).getClose().doubleValue() - klines.get(i - 1).getClose().doubleValue();
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double calcMa(List<Kline> klines, int idx, int period) {
        double sum = 0;
        int count = 0;
        for (int i = idx; i > idx - period && i >= 0; i--) {
            sum += klines.get(i).getClose().doubleValue();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private double calcMaSlope(List<Kline> klines, int idx, int period) {
        double ma1 = calcMa(klines, idx, period);
        double ma0 = calcMa(klines, Math.max(0, idx - 1), period);
        return ma0 > 0 ? (ma1 - ma0) / ma0 * 100 : 0;
    }

    private float safeFloat(BigDecimal val) {
        if (val == null) return 0f;
        return val.floatValue();
    }
}
