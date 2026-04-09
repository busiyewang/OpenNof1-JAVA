package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.util.List;

/**
 * 缠论完整分析结果。
 */
@Data
public class ChanResult {

    /** 经过包含处理后的K线数据（高/低点序列） */
    private List<BigDecimalPair> mergedBars;

    /** 识别出的分型列表 */
    private List<ChanFractal> fractals;

    /** 构建出的笔列表 */
    private List<ChanBi> biList;

    /** 构建出的线段列表 */
    private List<ChanSegment> segments;

    /** 识别出的中枢列表 */
    private List<ChanZhongshu> zhongshuList;

    /** 检测到的买卖点信号 */
    private List<ChanSignalPoint> signalPoints;

    /** 简单高低点对，用于存储包含处理后的K线 */
    @Data
    public static class BigDecimalPair {
        private final java.math.BigDecimal high;
        private final java.math.BigDecimal low;
        private final java.time.Instant timestamp;
        private final int originalIndex;
    }
}
