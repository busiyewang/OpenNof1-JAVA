package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 缠论分型（顶分型 / 底分型）。
 *
 * <p>分型由连续三根经过包含处理的K线构成：</p>
 * <ul>
 *   <li>顶分型：中间K线的高点最高且低点最高</li>
 *   <li>底分型：中间K线的低点最低且高点最低</li>
 * </ul>
 *
 * <p>分型强弱：</p>
 * <ul>
 *   <li>强顶分型：第三根K线收盘价低于第一根K线的低点</li>
 *   <li>强底分型：第三根K线收盘价高于第一根K线的高点</li>
 * </ul>
 */
@Data
public class ChanFractal {

    public enum Type { TOP, BOTTOM }
    public enum Strength { STRONG, WEAK }

    private Type type;
    private Strength strength;
    private int index;
    private Instant timestamp;
    private BigDecimal high;
    private BigDecimal low;
    /** 分型的极值点：顶分型取 high，底分型取 low */
    private BigDecimal extremePrice;
    /** 分型第一根K线的高点（用于强弱判断） */
    private BigDecimal firstBarHigh;
    /** 分型第一根K线的低点（用于强弱判断） */
    private BigDecimal firstBarLow;
    /** 分型第三根K线的收盘价（用于强弱判断） */
    private BigDecimal thirdBarClose;
}
