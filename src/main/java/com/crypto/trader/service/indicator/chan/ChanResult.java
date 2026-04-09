package com.crypto.trader.service.indicator.chan;

import lombok.Data;
import java.util.List;

/**
 * 缠论完整分析结果。
 */
@Data
public class ChanResult {

    /**
     * 走势类型。
     * <ul>
     *   <li>TREND_UP: 上涨趋势（至少两个依次抬高的中枢）</li>
     *   <li>TREND_DOWN: 下跌趋势（至少两个依次降低的中枢）</li>
     *   <li>CONSOLIDATION: 盘整（只有一个中枢）</li>
     *   <li>UNKNOWN: 数据不足无法判断</li>
     * </ul>
     */
    public enum TrendType { TREND_UP, TREND_DOWN, CONSOLIDATION, UNKNOWN }

    /**
     * 背驰类型。
     */
    public enum DivergenceType {
        /** 趋势背驰：趋势中最后一段力度衰减，最可靠的转折信号 */
        TREND_DIVERGENCE,
        /** 盘整背驰：盘整中离开中枢的力度衰减，可能继续震荡 */
        CONSOLIDATION_DIVERGENCE,
        /** 无背驰 */
        NONE
    }

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

    /** 当前走势类型 */
    private TrendType trendType;

    /** 当前背驰类型 */
    private DivergenceType divergenceType;

    /** 简单高低点对，用于存储包含处理后的K线 */
    @Data
    public static class BigDecimalPair {
        private final java.math.BigDecimal high;
        private final java.math.BigDecimal low;
        private final java.time.Instant timestamp;
        private final int originalIndex;
    }
}
