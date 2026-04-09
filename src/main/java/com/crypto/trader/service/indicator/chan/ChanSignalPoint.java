package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 缠论买卖点。
 *
 * <ul>
 *   <li>第一类买点：下跌趋势背驰，趋势即将反转（置信度最高）</li>
 *   <li>第一类卖点：上涨趋势背驰，趋势即将反转（置信度最高）</li>
 *   <li>第二类买点：一买后的回调不创新低（确认信号）</li>
 *   <li>第二类卖点：一卖后的反弹不创新高（确认信号）</li>
 *   <li>第三类买点：离开中枢后的回调不回到中枢内（右侧信号）</li>
 *   <li>第三类卖点：跌离中枢后的反弹不回到中枢内（右侧信号）</li>
 * </ul>
 */
@Data
public class ChanSignalPoint {

    public enum PointType {
        /** 第一类买点（趋势背驰买点） */
        BUY_1,
        /** 第二类买点（回调不破买点） */
        BUY_2,
        /** 第三类买点（中枢突破回踩买点） */
        BUY_3,
        /** 第一类卖点（趋势背驰卖点） */
        SELL_1,
        /** 第二类卖点（反弹不破卖点） */
        SELL_2,
        /** 第三类卖点（中枢跌破反弹卖点） */
        SELL_3
    }

    private PointType pointType;
    private BigDecimal price;
    private Instant timestamp;
    private double confidence;
    private String description;

    /** 背驰类型（仅一买/一卖有值） */
    private ChanResult.DivergenceType divergenceType;

    /** 关联分型的强弱 */
    private ChanFractal.Strength fractalStrength;
}
