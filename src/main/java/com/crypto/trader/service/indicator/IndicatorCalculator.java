package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import java.util.List;

public interface IndicatorCalculator<T> {
    T calculate(List<Kline> klines);
}
