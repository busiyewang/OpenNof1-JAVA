package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 缠论中枢：至少3个连续笔的价格重叠区间。
 *
 * <p>中枢由 ZG（上沿）和 ZD（下沿）定义：</p>
 * <ul>
 *   <li>ZG = min(各笔高点)：中枢区间的上边界</li>
 *   <li>ZD = max(各笔低点)：中枢区间的下边界</li>
 *   <li>中枢成立条件：ZG > ZD（即存在重叠区间）</li>
 * </ul>
 */
@Data
public class ChanZhongshu {

    /** 中枢上沿 */
    private BigDecimal zg;
    /** 中枢下沿 */
    private BigDecimal zd;
    /** 中枢最高价（GG） */
    private BigDecimal gg;
    /** 中枢最低价（DD） */
    private BigDecimal dd;

    private List<ChanBi> biList;
    private Instant startTime;
    private Instant endTime;

    /** 中枢中心价格 */
    public BigDecimal getCenter() {
        return zg.add(zd).divide(BigDecimal.valueOf(2), zg.scale(), BigDecimal.ROUND_HALF_UP);
    }
}
