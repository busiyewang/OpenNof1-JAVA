package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class MacdCalculator implements IndicatorCalculator<MacdCalculator.MacdValues> {

    public static class MacdValues {
        public final double macd;
        public final double signal;
        public final double histogram;
        public MacdValues(double macd, double signal, double histogram) {
            this.macd = macd;
            this.signal = signal;
            this.histogram = histogram;
        }
    }

    @Override
    public MacdValues calculate(List<Kline> klines) {
        if (klines.size() < 30) return null;

        BarSeries series = new BaseBarSeries();
        klines.forEach(k -> series.addBar(k.getTimestamp().atZone(ZoneOffset.UTC),
                k.getOpen().doubleValue(), k.getHigh().doubleValue(),
                k.getLow().doubleValue(), k.getClose().doubleValue(), k.getVolume().doubleValue()));

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        org.ta4j.core.indicators.EMAIndicator signalEma = new org.ta4j.core.indicators.EMAIndicator(macd, 9);

        int lastIndex = series.getEndIndex();
        double macdValue = macd.getValue(lastIndex).doubleValue();
        double signalValue = signalEma.getValue(lastIndex).doubleValue();
        double histogram = macdValue - signalValue;

        return new MacdValues(macdValue, signalValue, histogram);
    }
}
