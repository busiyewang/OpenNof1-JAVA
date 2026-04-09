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
 */
@Data
public class ChanFractal {

    public enum Type { TOP, BOTTOM }

    private Type type;
    private int index;
    private Instant timestamp;
    private BigDecimal high;
    private BigDecimal low;
    /** 分型的极值点：顶分型取 high，底分型取 low */
    private BigDecimal extremePrice;
}
