package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandFacade;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class BollingerBandsCalculator implements IndicatorCalculator<BollingerBandsCalculator.BollingerValues> {

    public static class BollingerValues {
        public final double middle;
        public final double upper;
        public final double lower;
        public BollingerValues(double middle, double upper, double lower) {
            this.middle = middle;
            this.upper = upper;
            this.lower = lower;
        }
    }

    @Override
    public BollingerValues calculate(List<Kline> klines) {
        if (klines.size() < 20) return null;

        BarSeries series = new BaseBarSeries();
        klines.forEach(k -> series.addBar(k.getTimestamp().atZone(ZoneOffset.UTC),
                k.getOpen().doubleValue(), k.getHigh().doubleValue(),
                k.getLow().doubleValue(), k.getClose().doubleValue(), k.getVolume().doubleValue()));

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(close, 20);
        BollingerBandFacade bollinger = new BollingerBandFacade(close, 20, 2);

        int lastIndex = series.getEndIndex();
        double middle = sma.getValue(lastIndex).doubleValue();
        double upper = bollinger.upper().getValue(lastIndex).doubleValue();
        double lower = bollinger.lower().getValue(lastIndex).doubleValue();

        return new BollingerValues(middle, upper, lower);
    }
}
