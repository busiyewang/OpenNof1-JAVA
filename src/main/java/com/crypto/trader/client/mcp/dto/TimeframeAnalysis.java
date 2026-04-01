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
}
