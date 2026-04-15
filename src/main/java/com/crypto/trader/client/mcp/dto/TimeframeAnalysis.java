package com.crypto.trader.client.mcp.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimeframeAnalysis {
    private String timeframe;
    private String trendDirection;
    private double currentPrice;
    private double priceChangePercent;
    private double volume;
    private double volumeChangePercent;

    // MACD
    private double macdValue;
    private double macdSignal;
    private double macdHistogram;

    // Bollinger Bands
    private double bollingerUpper;
    private double bollingerMiddle;
    private double bollingerLower;

    // RSI (TA4J 新增)
    private double rsi14;
    private double rsi7;

    // ATR (TA4J 新增)
    private double atr14;
    private double atrPercent;

    // ADX 趋势强度 (TA4J 新增)
    private double adx14;

    // Stochastic KDJ (TA4J 新增)
    private double stochK;
    private double stochD;

    // OBV (TA4J 新增)
    private double obvSlope5;

    // CCI (TA4J 新增)
    private double cci20;

    // Williams %R (TA4J 新增)
    private double williamsR14;
}
