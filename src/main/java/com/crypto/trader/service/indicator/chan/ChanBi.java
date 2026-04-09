package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 缠论笔：连接相邻顶分型和底分型的线段。
 *
 * <p>一笔的成立条件：</p>
 * <ul>
 *   <li>顶分型和底分型之间至少有1根独立K线（即至少5根K线）</li>
 *   <li>顶底分型必须交替出现</li>
 * </ul>
 */
@Data
public class ChanBi {

    public enum Direction { UP, DOWN }

    private Direction direction;
    private ChanFractal startFractal;
    private ChanFractal endFractal;

    /** 笔的起点价格 */
    public BigDecimal getStartPrice() {
        return startFractal.getExtremePrice();
    }

    /** 笔的终点价格 */
    public BigDecimal getEndPrice() {
        return endFractal.getExtremePrice();
    }

    public Instant getStartTime() {
        return startFractal.getTimestamp();
    }

    public Instant getEndTime() {
        return endFractal.getTimestamp();
    }

    /** 笔覆盖的K线根数 */
    public int getLength() {
        return endFractal.getIndex() - startFractal.getIndex();
    }
}
