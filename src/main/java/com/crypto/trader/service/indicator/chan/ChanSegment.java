package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 缠论线段：由至少3笔组成。
 *
 * <p>线段破坏条件：出现与线段方向相反的笔，其起点突破线段内部的某一笔的起点。</p>
 */
@Data
public class ChanSegment {

    public enum Direction { UP, DOWN }

    private Direction direction;
    private List<ChanBi> biList;
    private BigDecimal startPrice;
    private BigDecimal endPrice;
    private Instant startTime;
    private Instant endTime;
}
