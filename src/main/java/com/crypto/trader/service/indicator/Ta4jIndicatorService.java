package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandFacade;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 统一的 TA4J 指标计算服务。
 *
 * <p>核心改进：</p>
 * <ul>
 *   <li>一次构建 BarSeries，计算所有指标 — 避免重复转换开销</li>
 *   <li>提供 {@link IndicatorSnapshot} 一次返回全量指标值</li>
 *   <li>涵盖: MACD, BB, RSI, ATR, ADX, SMA, EMA, Stochastic, OBV, CCI, WilliamsR</li>
 * </ul>
 */
@Service
@Slf4j
public class Ta4jIndicatorService {

    /**
     * 全量指标快照 — 一次计算返回所有指标。
     */
    public static class IndicatorSnapshot {
        // MACD (12/26/9)
        public final double macd;
        public final double macdSignal;
        public final double macdHistogram;

        // Bollinger Bands (20, 2σ)
        public final double bbUpper;
        public final double bbMiddle;
        public final double bbLower;
        public final double bbWidth;       // (upper - lower) / middle
        public final double bbPosition;    // (close - lower) / (upper - lower)

        // RSI
        public final double rsi14;
        public final double rsi7;

        // ATR (Average True Range)
        public final double atr14;
        public final double atrPercent;    // ATR / close * 100

        // ADX (趋势强度)
        public final double adx14;

        // SMA
        public final double sma5;
        public final double sma10;
        public final double sma20;
        public final double sma60;

        // EMA
        public final double ema12;
        public final double ema26;

        // Stochastic (KDJ)
        public final double stochK;
        public final double stochD;

        // OBV (On Balance Volume)
        public final double obv;
        public final double obvSlope5;     // OBV 5期斜率

        // CCI (Commodity Channel Index)
        public final double cci20;

        // Williams %R
        public final double williamsR14;

        // 价格相关
        public final double close;
        public final double volume;

        public IndicatorSnapshot(
                double macd, double macdSignal, double macdHistogram,
                double bbUpper, double bbMiddle, double bbLower, double bbWidth, double bbPosition,
                double rsi14, double rsi7,
                double atr14, double atrPercent,
                double adx14,
                double sma5, double sma10, double sma20, double sma60,
                double ema12, double ema26,
                double stochK, double stochD,
                double obv, double obvSlope5,
                double cci20,
                double williamsR14,
                double close, double volume) {
            this.macd = macd; this.macdSignal = macdSignal; this.macdHistogram = macdHistogram;
            this.bbUpper = bbUpper; this.bbMiddle = bbMiddle; this.bbLower = bbLower;
            this.bbWidth = bbWidth; this.bbPosition = bbPosition;
            this.rsi14 = rsi14; this.rsi7 = rsi7;
            this.atr14 = atr14; this.atrPercent = atrPercent;
            this.adx14 = adx14;
            this.sma5 = sma5; this.sma10 = sma10; this.sma20 = sma20; this.sma60 = sma60;
            this.ema12 = ema12; this.ema26 = ema26;
            this.stochK = stochK; this.stochD = stochD;
            this.obv = obv; this.obvSlope5 = obvSlope5;
            this.cci20 = cci20;
            this.williamsR14 = williamsR14;
            this.close = close; this.volume = volume;
        }
    }

    /**
     * Kline 列表转 TA4J BarSeries。
     * @param klines 按时间正序排列的 K 线
     */
    public BarSeries buildBarSeries(List<Kline> klines) {
        BarSeries series = new BaseBarSeries();
        for (Kline k : klines) {
            series.addBar(
                    k.getTimestamp().atZone(ZoneOffset.UTC),
                    k.getOpen().doubleValue(),
                    k.getHigh().doubleValue(),
                    k.getLow().doubleValue(),
                    k.getClose().doubleValue(),
                    k.getVolume() != null ? k.getVolume().doubleValue() : 0
            );
        }
        return series;
    }

    /**
     * 一次性计算全量指标快照。
     *
     * @param klines 按时间正序排列的 K 线（至少 60 根，推荐 100 根）
     * @return 指标快照，数据不足时返回 null
     */
    public IndicatorSnapshot calculateAll(List<Kline> klines) {
        if (klines == null || klines.size() < 30) return null;

        try {
            return doCalculateAll(klines);
        } catch (Exception e) {
            log.error("[TA4J] 指标计算异常: {}", e.getMessage(), e);
            return null;
        }
    }

    private IndicatorSnapshot doCalculateAll(List<Kline> klines) {
        BarSeries series = buildBarSeries(klines);
        int last = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volumeInd = new VolumeIndicator(series);

        // === MACD ===
        MACDIndicator macdInd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignalInd = new EMAIndicator(macdInd, 9);
        double macdVal = macdInd.getValue(last).doubleValue();
        double macdSig = macdSignalInd.getValue(last).doubleValue();
        double macdHist = macdVal - macdSig;

        // === Bollinger Bands ===
        BollingerBandFacade bb = new BollingerBandFacade(closePrice, 20, 2);
        SMAIndicator sma20Ind = new SMAIndicator(closePrice, 20);
        double bbUp = bb.upper().getValue(last).doubleValue();
        double bbMid = sma20Ind.getValue(last).doubleValue();
        double bbLow = bb.lower().getValue(last).doubleValue();
        double closeVal = closePrice.getValue(last).doubleValue();
        double bbW = bbMid > 0 ? (bbUp - bbLow) / bbMid : 0;
        double bbPos = (bbUp - bbLow) > 0 ? (closeVal - bbLow) / (bbUp - bbLow) : 0.5;

        // === RSI ===
        RSIIndicator rsi14Ind = new RSIIndicator(closePrice, 14);
        RSIIndicator rsi7Ind = new RSIIndicator(closePrice, 7);
        double rsi14 = last >= 14 ? rsi14Ind.getValue(last).doubleValue() : 50;
        double rsi7 = last >= 7 ? rsi7Ind.getValue(last).doubleValue() : 50;

        // === ATR ===
        ATRIndicator atrInd = new ATRIndicator(series, 14);
        double atr14 = last >= 14 ? atrInd.getValue(last).doubleValue() : 0;
        double atrPct = closeVal > 0 ? atr14 / closeVal * 100 : 0;

        // === ADX ===
        ADXIndicator adxInd = new ADXIndicator(series, 14);
        double adx14 = last >= 28 ? adxInd.getValue(last).doubleValue() : 0;

        // === SMA ===
        SMAIndicator sma5Ind = new SMAIndicator(closePrice, 5);
        SMAIndicator sma10Ind = new SMAIndicator(closePrice, 10);
        SMAIndicator sma60Ind = new SMAIndicator(closePrice, Math.min(60, last + 1));
        double sma5 = sma5Ind.getValue(last).doubleValue();
        double sma10 = sma10Ind.getValue(last).doubleValue();
        double sma20 = sma20Ind.getValue(last).doubleValue();
        double sma60 = last >= 60 ? sma60Ind.getValue(last).doubleValue() : sma20;

        // === EMA ===
        EMAIndicator ema12Ind = new EMAIndicator(closePrice, 12);
        EMAIndicator ema26Ind = new EMAIndicator(closePrice, 26);
        double ema12 = ema12Ind.getValue(last).doubleValue();
        double ema26 = ema26Ind.getValue(last).doubleValue();

        // === Stochastic (KDJ) ===
        StochasticOscillatorKIndicator stochKInd = new StochasticOscillatorKIndicator(series, 14);
        StochasticOscillatorDIndicator stochDInd = new StochasticOscillatorDIndicator(stochKInd);
        double stochK = last >= 14 ? stochKInd.getValue(last).doubleValue() : 50;
        double stochD = last >= 14 ? stochDInd.getValue(last).doubleValue() : 50;

        // === OBV ===
        OnBalanceVolumeIndicator obvInd = new OnBalanceVolumeIndicator(series);
        double obvVal = obvInd.getValue(last).doubleValue();
        double obvPrev5 = last >= 5 ? obvInd.getValue(last - 5).doubleValue() : obvVal;
        double obvSlope = obvPrev5 != 0 ? (obvVal - obvPrev5) / Math.abs(obvPrev5) * 100 : 0;

        // === CCI ===
        CCIIndicator cciInd = new CCIIndicator(series, 20);
        double cci20 = last >= 20 ? cciInd.getValue(last).doubleValue() : 0;

        // === Williams %R ===
        WilliamsRIndicator wrInd = new WilliamsRIndicator(series, 14);
        double wr14 = last >= 14 ? wrInd.getValue(last).doubleValue() : -50;

        double vol = volumeInd.getValue(last).doubleValue();

        return new IndicatorSnapshot(
                macdVal, macdSig, macdHist,
                bbUp, bbMid, bbLow, bbW, bbPos,
                rsi14, rsi7,
                atr14, atrPct,
                adx14,
                sma5, sma10, sma20, sma60,
                ema12, ema26,
                stochK, stochD,
                obvVal, obvSlope,
                cci20,
                wr14,
                closeVal, vol
        );
    }
}
