package com.crypto.trader.util;

import java.util.List;

public class IndicatorUtils {

    public static double sma(List<Double> values, int period) {
        if (values.size() < period) return 0;
        return values.subList(values.size() - period, values.size()).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
