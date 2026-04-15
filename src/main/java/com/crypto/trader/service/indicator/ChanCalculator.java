package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import com.crypto.trader.service.indicator.chan.*;
import com.crypto.trader.service.indicator.chan.ChanResult.BigDecimalPair;
import com.crypto.trader.service.indicator.chan.ChanResult.DivergenceType;
import com.crypto.trader.service.indicator.chan.ChanResult.TrendType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 缠论核心计算器。
 *
 * <p>计算流程：K线包含处理 → 分型识别（含强弱） → 笔构建 → 线段构建（特征序列）
 * → 中枢识别 → 走势类型判断（+ADX确认） → 背驰判断（MACD+斜率+幅度+成交量+RSI 五重验证） → 买卖点生成</p>
 *
 * <p>TA4J 集成指标: MACD(背驰力度), RSI(背驰确认), ADX(趋势强度), OBV(量价趋势)</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChanCalculator implements IndicatorCalculator<ChanResult> {

    /** 笔内最少K线数：顶底分型各占3根，中间至少1根独立K线 = 5根最少 */
    private static final int MIN_BI_KLINE_COUNT = 5;

    @Override
    public ChanResult calculate(List<Kline> klines) {
        if (klines == null || klines.size() < 30) {
            log.debug("[缠论] K线数据不足（需>=30，当前={}），无法计算", klines == null ? 0 : klines.size());
            return null;
        }

        ChanResult result = new ChanResult();

        // 0. 通过 TA4J 计算指标数组（MACD、RSI、ADX、OBV）+ 提取成交量
        BarSeries series = buildBarSeries(klines);
        double[] macdHistogram = calcMacdHistogramArray(series);
        double[] rsiArray = calcRsiArray(series, 14);
        double adx = calcAdx(series, 14);
        double[] obvArray = calcObvArray(series);
        double[] volumes = extractVolumes(klines);

        // 1. K线包含处理
        List<BigDecimalPair> merged = mergeKlines(klines);
        result.setMergedBars(merged);

        // 2. 分型识别（含强弱判断）
        List<ChanFractal> fractals = findFractals(merged, klines);
        result.setFractals(fractals);

        if (fractals.size() < 2) {
            log.debug("[缠论] 分型数量不足（{}），无法构建笔", fractals.size());
            result.setBiList(List.of());
            result.setSegments(List.of());
            result.setZhongshuList(List.of());
            result.setSignalPoints(List.of());
            result.setTrendType(TrendType.UNKNOWN);
            result.setDivergenceType(DivergenceType.NONE);
            return result;
        }

        // 3. 笔构建
        List<ChanBi> biList = buildBiList(fractals);
        result.setBiList(biList);

        // 4. 线段构建（使用特征序列）
        List<ChanSegment> segments = buildSegments(biList);
        result.setSegments(segments);

        // 5. 中枢识别
        List<ChanZhongshu> zhongshuList = findZhongshu(biList);
        result.setZhongshuList(zhongshuList);

        // 6. 走势类型判断（结合 ADX 趋势强度）
        TrendType trendType = classifyTrend(zhongshuList, adx);
        result.setTrendType(trendType);

        // 7. 背驰判断 + 买卖点识别（五重验证：幅度+MACD+斜率+成交量+RSI）
        DivergenceType divergenceType = DivergenceType.NONE;
        List<ChanSignalPoint> signalPoints = findSignalPoints(biList, zhongshuList, klines,
                macdHistogram, volumes, rsiArray, obvArray, trendType);
        result.setSignalPoints(signalPoints);

        // 从买卖点中提取背驰类型
        for (ChanSignalPoint sp : signalPoints) {
            if (sp.getDivergenceType() != null && sp.getDivergenceType() != DivergenceType.NONE) {
                divergenceType = sp.getDivergenceType();
                break;
            }
        }
        result.setDivergenceType(divergenceType);

        log.info("[缠论] 分析完成: 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}, 走势={}, ADX={}, 背驰={}, 买卖点={}",
                merged.size(), fractals.size(), biList.size(), segments.size(),
                zhongshuList.size(), trendType, String.format("%.1f", adx),
                divergenceType, signalPoints.size());

        return result;
    }

    // =========================================================================
    // 0. TA4J 指标计算
    // =========================================================================

    /** Kline → TA4J BarSeries */
    private BarSeries buildBarSeries(List<Kline> klines) {
        BarSeries series = new BaseBarSeries();
        for (Kline k : klines) {
            series.addBar(k.getTimestamp().atZone(ZoneOffset.UTC),
                    k.getOpen().doubleValue(), k.getHigh().doubleValue(),
                    k.getLow().doubleValue(), k.getClose().doubleValue(),
                    k.getVolume() != null ? k.getVolume().doubleValue() : 0);
        }
        return series;
    }

    /** MACD Histogram 数组（TA4J 计算） */
    private double[] calcMacdHistogramArray(BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        EMAIndicator signal = new EMAIndicator(macd, 9);

        int size = series.getBarCount();
        double[] histogram = new double[size];
        for (int i = 0; i < size; i++) {
            histogram[i] = 2 * (macd.getValue(i).doubleValue() - signal.getValue(i).doubleValue());
        }
        return histogram;
    }

    /** RSI 数组（TA4J 计算），每根 K 线对应一个 RSI 值 */
    private double[] calcRsiArray(BarSeries series, int period) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(close, period);

        int size = series.getBarCount();
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) {
            arr[i] = i >= period ? rsi.getValue(i).doubleValue() : 50;
        }
        return arr;
    }

    /** ADX 当前值（TA4J 计算） */
    private double calcAdx(BarSeries series, int period) {
        if (series.getBarCount() < period * 2) return 0;
        ADXIndicator adx = new ADXIndicator(series, period);
        return adx.getValue(series.getEndIndex()).doubleValue();
    }

    /** OBV 数组（TA4J 计算） */
    private double[] calcObvArray(BarSeries series) {
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        int size = series.getBarCount();
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) {
            arr[i] = obv.getValue(i).doubleValue();
        }
        return arr;
    }

    /**
     * 计算一段K线范围内 MACD 柱状图的绝对面积之和。
     */
    private double macdArea(double[] histogram, int startIdx, int endIdx) {
        double area = 0;
        int from = Math.max(0, Math.min(startIdx, endIdx));
        int to = Math.min(histogram.length - 1, Math.max(startIdx, endIdx));
        for (int i = from; i <= to; i++) {
            area += Math.abs(histogram[i]);
        }
        return area;
    }

    /**
     * 计算一段范围内 RSI 的平均值（用于比较两段走势的 RSI 力度）。
     */
    private double rsiAvg(double[] rsiArray, int startIdx, int endIdx) {
        int from = Math.max(0, Math.min(startIdx, endIdx));
        int to = Math.min(rsiArray.length - 1, Math.max(startIdx, endIdx));
        int count = to - from + 1;
        if (count <= 0) return 50;
        double sum = 0;
        for (int i = from; i <= to; i++) sum += rsiArray[i];
        return sum / count;
    }

    /**
     * 计算一段范围内 OBV 的变化斜率（终值 - 起值）/ 区间长度。
     */
    private double obvSlope(double[] obvArray, int startIdx, int endIdx) {
        int from = Math.max(0, Math.min(startIdx, endIdx));
        int to = Math.min(obvArray.length - 1, Math.max(startIdx, endIdx));
        int count = to - from;
        if (count <= 0) return 0;
        return (obvArray[to] - obvArray[from]) / count;
    }

    /** 提取 K 线成交量数组 */
    private double[] extractVolumes(List<Kline> klines) {
        double[] volumes = new double[klines.size()];
        for (int i = 0; i < klines.size(); i++) {
            volumes[i] = klines.get(i).getVolume() != null
                    ? klines.get(i).getVolume().doubleValue() : 0;
        }
        return volumes;
    }

    /** 区间累计成交量 */
    private double volumeSum(double[] volumes, int startIdx, int endIdx) {
        double sum = 0;
        int from = Math.max(0, Math.min(startIdx, endIdx));
        int to = Math.min(volumes.length - 1, Math.max(startIdx, endIdx));
        for (int i = from; i <= to; i++) sum += volumes[i];
        return sum;
    }

    /** 区间平均成交量 */
    private double volumeAvg(double[] volumes, int startIdx, int endIdx) {
        int from = Math.max(0, Math.min(startIdx, endIdx));
        int to = Math.min(volumes.length - 1, Math.max(startIdx, endIdx));
        int count = to - from + 1;
        if (count <= 0) return 0;
        return volumeSum(volumes, startIdx, endIdx) / count;
    }

    // =========================================================================
    // 1. K线包含处理
    // =========================================================================

    /**
     * K线包含处理：当两根K线存在包含关系时，按趋势方向合并。
     * <ul>
     *   <li>上升趋势：取高点的高值、低点的高值（向上合并）</li>
     *   <li>下降趋势：取高点的低值、低点的低值（向下合并）</li>
     * </ul>
     * 合并后的K线可能继续与后面的K线存在包含关系，需继续处理。
     */
    private List<BigDecimalPair> mergeKlines(List<Kline> klines) {
        List<BigDecimalPair> merged = new ArrayList<>();
        if (klines.isEmpty()) return merged;

        merged.add(new BigDecimalPair(klines.get(0).getHigh(), klines.get(0).getLow(),
                klines.get(0).getTimestamp(), 0));

        for (int i = 1; i < klines.size(); i++) {
            BigDecimal curHigh = klines.get(i).getHigh();
            BigDecimal curLow = klines.get(i).getLow();
            BigDecimalPair last = merged.get(merged.size() - 1);

            boolean contains = (curHigh.compareTo(last.getHigh()) >= 0 && curLow.compareTo(last.getLow()) <= 0)
                    || (curHigh.compareTo(last.getHigh()) <= 0 && curLow.compareTo(last.getLow()) >= 0);

            if (contains) {
                // 趋势方向由已确定的前两根合并K线决定
                boolean upTrend = merged.size() >= 2
                        && merged.get(merged.size() - 2).getHigh().compareTo(last.getHigh()) < 0;

                BigDecimal newHigh, newLow;
                if (upTrend) {
                    newHigh = curHigh.max(last.getHigh());
                    newLow = curLow.max(last.getLow());
                } else {
                    newHigh = curHigh.min(last.getHigh());
                    newLow = curLow.min(last.getLow());
                }
                merged.set(merged.size() - 1, new BigDecimalPair(newHigh, newLow,
                        klines.get(i).getTimestamp(), i));
            } else {
                merged.add(new BigDecimalPair(curHigh, curLow, klines.get(i).getTimestamp(), i));
            }
        }
        return merged;
    }

    // =========================================================================
    // 2. 分型识别（含强弱判断）
    // =========================================================================

    /**
     * 在合并后的K线序列中识别顶分型和底分型，并判断强弱。
     *
     * <p>强弱判断规则：</p>
     * <ul>
     *   <li>强顶分型：第三根K线的收盘价低于第一根K线的低点</li>
     *   <li>强底分型：第三根K线的收盘价高于第一根K线的高点</li>
     * </ul>
     */
    private List<ChanFractal> findFractals(List<BigDecimalPair> merged, List<Kline> klines) {
        List<ChanFractal> fractals = new ArrayList<>();

        for (int i = 1; i < merged.size() - 1; i++) {
            BigDecimalPair prev = merged.get(i - 1);
            BigDecimalPair curr = merged.get(i);
            BigDecimalPair next = merged.get(i + 1);

            boolean isTop = curr.getHigh().compareTo(prev.getHigh()) > 0
                    && curr.getHigh().compareTo(next.getHigh()) > 0
                    && curr.getLow().compareTo(prev.getLow()) > 0
                    && curr.getLow().compareTo(next.getLow()) > 0;

            boolean isBottom = curr.getLow().compareTo(prev.getLow()) < 0
                    && curr.getLow().compareTo(next.getLow()) < 0
                    && curr.getHigh().compareTo(prev.getHigh()) < 0
                    && curr.getHigh().compareTo(next.getHigh()) < 0;

            if (isTop || isBottom) {
                // 确保顶底交替
                if (!fractals.isEmpty()) {
                    ChanFractal lastFractal = fractals.get(fractals.size() - 1);
                    ChanFractal.Type newType = isTop ? ChanFractal.Type.TOP : ChanFractal.Type.BOTTOM;

                    if (lastFractal.getType() == newType) {
                        // 同类型分型，保留更极端的
                        if (isTop && curr.getHigh().compareTo(lastFractal.getHigh()) > 0) {
                            fractals.set(fractals.size() - 1, buildFractal(isTop, prev, curr, next, klines));
                        } else if (isBottom && curr.getLow().compareTo(lastFractal.getLow()) < 0) {
                            fractals.set(fractals.size() - 1, buildFractal(isTop, prev, curr, next, klines));
                        }
                        continue;
                    }
                }

                fractals.add(buildFractal(isTop, prev, curr, next, klines));
            }
        }

        return fractals;
    }

    private ChanFractal buildFractal(boolean isTop, BigDecimalPair first, BigDecimalPair mid,
                                      BigDecimalPair third, List<Kline> klines) {
        ChanFractal f = new ChanFractal();
        f.setType(isTop ? ChanFractal.Type.TOP : ChanFractal.Type.BOTTOM);
        f.setIndex(mid.getOriginalIndex());
        f.setTimestamp(mid.getTimestamp());
        f.setHigh(mid.getHigh());
        f.setLow(mid.getLow());
        f.setExtremePrice(isTop ? mid.getHigh() : mid.getLow());

        // 保存第一根和第三根信息，用于强弱判断
        f.setFirstBarHigh(first.getHigh());
        f.setFirstBarLow(first.getLow());

        // 第三根K线的收盘价
        int thirdIdx = third.getOriginalIndex();
        if (thirdIdx >= 0 && thirdIdx < klines.size()) {
            f.setThirdBarClose(klines.get(thirdIdx).getClose());

            // 判断强弱
            if (isTop) {
                // 强顶分型：第三根收盘价 < 第一根低点
                f.setStrength(f.getThirdBarClose().compareTo(first.getLow()) < 0
                        ? ChanFractal.Strength.STRONG : ChanFractal.Strength.WEAK);
            } else {
                // 强底分型：第三根收盘价 > 第一根高点
                f.setStrength(f.getThirdBarClose().compareTo(first.getHigh()) > 0
                        ? ChanFractal.Strength.STRONG : ChanFractal.Strength.WEAK);
            }
        } else {
            f.setStrength(ChanFractal.Strength.WEAK);
        }

        return f;
    }

    // =========================================================================
    // 3. 笔构建
    // =========================================================================

    /**
     * 从分型序列构建笔。
     *
     * <p>成笔条件：</p>
     * <ul>
     *   <li>顶底分型交替出现</li>
     *   <li>顶底分型之间至少隔 5 根K线（含分型共享的K线）</li>
     *   <li>向上笔：底分型低点 < 顶分型高点</li>
     *   <li>向下笔：顶分型高点 > 底分型低点</li>
     * </ul>
     */
    private List<ChanBi> buildBiList(List<ChanFractal> fractals) {
        List<ChanBi> biList = new ArrayList<>();

        for (int i = 0; i < fractals.size() - 1; i++) {
            ChanFractal start = fractals.get(i);
            ChanFractal end = fractals.get(i + 1);

            // 顶底之间至少隔 MIN_BI_KLINE_COUNT 根K线
            if (end.getIndex() - start.getIndex() < MIN_BI_KLINE_COUNT) {
                continue;
            }

            ChanBi bi = new ChanBi();
            bi.setStartFractal(start);
            bi.setEndFractal(end);

            if (start.getType() == ChanFractal.Type.BOTTOM && end.getType() == ChanFractal.Type.TOP) {
                bi.setDirection(ChanBi.Direction.UP);
            } else if (start.getType() == ChanFractal.Type.TOP && end.getType() == ChanFractal.Type.BOTTOM) {
                bi.setDirection(ChanBi.Direction.DOWN);
            } else {
                continue;
            }

            biList.add(bi);
        }

        return biList;
    }

    // =========================================================================
    // 4. 线段构建（特征序列法）
    // =========================================================================

    /**
     * 从笔序列构建线段，使用特征序列方法。
     *
     * <p>特征序列：</p>
     * <ul>
     *   <li>向上线段中，所有向下笔构成特征序列</li>
     *   <li>向下线段中，所有向上笔构成特征序列</li>
     *   <li>当特征序列出现分型时，线段可能被破坏</li>
     * </ul>
     */
    private List<ChanSegment> buildSegments(List<ChanBi> biList) {
        List<ChanSegment> segments = new ArrayList<>();
        if (biList.size() < 3) return segments;

        int segStart = 0;
        while (segStart < biList.size() - 2) {
            ChanBi firstBi = biList.get(segStart);
            ChanSegment.Direction dir = firstBi.getDirection() == ChanBi.Direction.UP
                    ? ChanSegment.Direction.UP : ChanSegment.Direction.DOWN;

            int segEnd = segStart + 2; // 至少3笔

            // 使用特征序列判断线段结束
            // 特征序列是与线段方向相反的笔
            for (int i = segStart + 4; i < biList.size(); i += 2) {
                ChanBi candidate = biList.get(i);

                if (dir == ChanSegment.Direction.UP) {
                    // 上升线段中，检查向上笔是否继续创新高
                    if (candidate.getEndPrice().compareTo(biList.get(segEnd).getEndPrice()) > 0) {
                        segEnd = i;
                    } else {
                        // 特征序列检查：向下笔（i-1）的低点是否跌破前一个向下笔的低点
                        if (i - 1 >= segStart + 1 && i - 3 >= segStart + 1) {
                            ChanBi currDown = biList.get(i - 1);
                            ChanBi prevDown = biList.get(i - 3);
                            // 特征序列出现底分型（低点不再创新低）→ 线段可能结束
                            if (currDown.getEndPrice().compareTo(prevDown.getEndPrice()) > 0) {
                                break;
                            }
                        }
                        // 笔破坏：直接跌破线段起点
                        if (candidate.getDirection() == ChanBi.Direction.DOWN
                                && candidate.getEndPrice().compareTo(biList.get(segStart).getStartPrice()) < 0) {
                            break;
                        }
                        break;
                    }
                } else {
                    // 下降线段中，检查向下笔是否继续创新低
                    if (candidate.getEndPrice().compareTo(biList.get(segEnd).getEndPrice()) < 0) {
                        segEnd = i;
                    } else {
                        // 特征序列检查：向上笔（i-1）的高点是否突破前一个向上笔的高点
                        if (i - 1 >= segStart + 1 && i - 3 >= segStart + 1) {
                            ChanBi currUp = biList.get(i - 1);
                            ChanBi prevUp = biList.get(i - 3);
                            if (currUp.getEndPrice().compareTo(prevUp.getEndPrice()) < 0) {
                                break;
                            }
                        }
                        if (candidate.getDirection() == ChanBi.Direction.UP
                                && candidate.getEndPrice().compareTo(biList.get(segStart).getStartPrice()) > 0) {
                            break;
                        }
                        break;
                    }
                }
            }

            List<ChanBi> segBiList = biList.subList(segStart, Math.min(segEnd + 1, biList.size()));
            ChanSegment seg = new ChanSegment();
            seg.setDirection(dir);
            seg.setBiList(new ArrayList<>(segBiList));
            seg.setStartPrice(segBiList.get(0).getStartPrice());
            seg.setEndPrice(segBiList.get(segBiList.size() - 1).getEndPrice());
            seg.setStartTime(segBiList.get(0).getStartTime());
            seg.setEndTime(segBiList.get(segBiList.size() - 1).getEndTime());
            segments.add(seg);

            segStart = segEnd + 1;
        }

        return segments;
    }

    // =========================================================================
    // 5. 中枢识别
    // =========================================================================

    /**
     * 从笔序列中识别中枢。
     * <p>中枢定义：至少3个连续笔的价格重叠区间。</p>
     * <ul>
     *   <li>ZG = min(各笔高点)：中枢上沿</li>
     *   <li>ZD = max(各笔低点)：中枢下沿</li>
     *   <li>中枢成立条件：ZG > ZD</li>
     * </ul>
     */
    private List<ChanZhongshu> findZhongshu(List<ChanBi> biList) {
        List<ChanZhongshu> zhongshuList = new ArrayList<>();
        if (biList.size() < 3) return zhongshuList;

        int i = 0;
        while (i < biList.size() - 2) {
            BigDecimal zg = minHigh(biList.get(i), biList.get(i + 1), biList.get(i + 2));
            BigDecimal zd = maxLow(biList.get(i), biList.get(i + 1), biList.get(i + 2));

            if (zg.compareTo(zd) <= 0) {
                i++;
                continue;
            }

            // 中枢成立，尝试扩展
            List<ChanBi> zhBiList = new ArrayList<>();
            zhBiList.add(biList.get(i));
            zhBiList.add(biList.get(i + 1));
            zhBiList.add(biList.get(i + 2));

            BigDecimal gg = maxHigh(biList.get(i), biList.get(i + 1), biList.get(i + 2));
            BigDecimal dd = minLow(biList.get(i), biList.get(i + 1), biList.get(i + 2));

            int j = i + 3;
            while (j < biList.size()) {
                BigDecimal bHigh = biHigh(biList.get(j));
                BigDecimal bLow = biLow(biList.get(j));

                // 笔与中枢有重叠 → 扩展（ZG和ZD不变）
                if (bHigh.compareTo(zd) > 0 && bLow.compareTo(zg) < 0) {
                    zhBiList.add(biList.get(j));
                    gg = gg.max(bHigh);
                    dd = dd.min(bLow);
                    j++;
                } else {
                    break;
                }
            }

            ChanZhongshu zh = new ChanZhongshu();
            zh.setZg(zg);
            zh.setZd(zd);
            zh.setGg(gg);
            zh.setDd(dd);
            zh.setBiList(zhBiList);
            zh.setStartTime(zhBiList.get(0).getStartTime());
            zh.setEndTime(zhBiList.get(zhBiList.size() - 1).getEndTime());
            zhongshuList.add(zh);

            i = j;
        }

        return zhongshuList;
    }

    // =========================================================================
    // 6. 走势类型判断
    // =========================================================================

    /**
     * 根据中枢数量和位置关系判断走势类型，结合 ADX 确认趋势强度。
     * <ul>
     *   <li>0个中枢：UNKNOWN</li>
     *   <li>1个中枢：CONSOLIDATION（盘整）</li>
     *   <li>≥2个中枢且依次抬高：TREND_UP</li>
     *   <li>≥2个中枢且依次降低：TREND_DOWN</li>
     *   <li>ADX<20 时，即使中枢有方向也降级为 CONSOLIDATION（TA4J 改进）</li>
     * </ul>
     */
    private TrendType classifyTrend(List<ChanZhongshu> zhongshuList, double adx) {
        if (zhongshuList.isEmpty()) return TrendType.UNKNOWN;
        if (zhongshuList.size() == 1) return TrendType.CONSOLIDATION;

        // 检查最后两个中枢的关系
        ChanZhongshu prev = zhongshuList.get(zhongshuList.size() - 2);
        ChanZhongshu curr = zhongshuList.get(zhongshuList.size() - 1);

        boolean rising = curr.getZd().compareTo(prev.getZg()) > 0
                || (curr.getCenter().compareTo(prev.getCenter()) > 0
                    && curr.getZd().compareTo(prev.getZd()) > 0);

        boolean falling = curr.getZg().compareTo(prev.getZd()) < 0
                || (curr.getCenter().compareTo(prev.getCenter()) < 0
                    && curr.getZg().compareTo(prev.getZg()) < 0);

        // ADX < 20：趋势极弱，即使中枢有方向也视为盘整
        // 这是 TA4J 对缠论纯结构分析的补充：避免在横盘市场中误判为趋势
        if (adx > 0 && adx < 20 && (rising || falling)) {
            log.info("[缠论] ADX={} < 20，趋势极弱，降级为盘整（原判断={}）",
                    String.format("%.1f", adx), rising ? "TREND_UP" : "TREND_DOWN");
            return TrendType.CONSOLIDATION;
        }

        if (rising) return TrendType.TREND_UP;
        if (falling) return TrendType.TREND_DOWN;

        return TrendType.CONSOLIDATION;
    }

    // =========================================================================
    // 7. 买卖点识别（三重背驰验证）
    // =========================================================================

    private List<ChanSignalPoint> findSignalPoints(List<ChanBi> biList,
                                                    List<ChanZhongshu> zhongshuList,
                                                    List<Kline> klines,
                                                    double[] macdHistogram,
                                                    double[] volumes,
                                                    double[] rsiArray,
                                                    double[] obvArray,
                                                    TrendType trendType) {
        List<ChanSignalPoint> points = new ArrayList<>();
        if (biList.size() < 3) return points;

        // 第一类买卖点：背驰（五重验证：价格幅度 + MACD面积 + 斜率 + 成交量 + RSI）
        findFirstTypePoints(biList, zhongshuList, klines, macdHistogram, volumes, rsiArray, obvArray, trendType, points);

        // 第二类买卖点：回调不破
        findSecondTypePoints(biList, points);

        // 第三类买卖点：中枢突破回踩（成交量 + OBV确认突破有效性）
        findThirdTypePoints(biList, zhongshuList, volumes, obvArray, points);

        return points;
    }

    /**
     * 第一类买卖点：五重背驰验证（TA4J 增强版）。
     *
     * <p>验证条件：</p>
     * <ol>
     *   <li>价格幅度：最后一笔幅度 < 前同向笔幅度</li>
     *   <li>MACD面积（TA4J）：最后一笔MACD柱状图面积 < 前同向笔面积</li>
     *   <li>斜率：最后一笔斜率(幅度/K线数) < 前同向笔斜率</li>
     *   <li>成交量：最后一笔平均成交量 < 前同向笔平均成交量（量价背离）</li>
     *   <li>RSI（TA4J 新增）：价格创新高/新低但RSI未创新高/新低 → RSI背驰</li>
     * </ol>
     *
     * <p>额外辅助：OBV（TA4J）斜率方向与价格方向不一致 → OBV背离加分</p>
     */
    private void findFirstTypePoints(List<ChanBi> biList, List<ChanZhongshu> zhongshuList,
                                      List<Kline> klines, double[] macdHistogram,
                                      double[] volumes, double[] rsiArray,
                                      double[] obvArray,
                                      TrendType trendType, List<ChanSignalPoint> points) {
        if (biList.size() < 5 || zhongshuList.isEmpty()) return;

        ChanBi lastBi = biList.get(biList.size() - 1);

        // 找倒数第二根同向笔
        ChanBi prevSameDir = null;
        for (int i = biList.size() - 3; i >= 0; i -= 2) {
            if (biList.get(i).getDirection() == lastBi.getDirection()) {
                prevSameDir = biList.get(i);
                break;
            }
        }
        if (prevSameDir == null) return;

        // 验证两段走势之间经过中枢
        boolean hasZhongshuBetween = false;
        for (ChanZhongshu zh : zhongshuList) {
            if (zh.getStartTime().isAfter(prevSameDir.getStartTime())
                    && zh.getEndTime().isBefore(lastBi.getEndTime())) {
                hasZhongshuBetween = true;
                break;
            }
        }
        if (!hasZhongshuBetween && zhongshuList.size() < 1) return;

        // --- 五重背驰验证 ---
        // 1) 价格幅度
        BigDecimal lastAmplitude = lastBi.getEndPrice().subtract(lastBi.getStartPrice()).abs();
        BigDecimal prevAmplitude = prevSameDir.getEndPrice().subtract(prevSameDir.getStartPrice()).abs();
        boolean amplitudeDivergence = lastAmplitude.compareTo(prevAmplitude) < 0;

        // 2) MACD面积（TA4J）
        double lastMacdArea = macdArea(macdHistogram,
                lastBi.getStartFractal().getIndex(), lastBi.getEndFractal().getIndex());
        double prevMacdArea = macdArea(macdHistogram,
                prevSameDir.getStartFractal().getIndex(), prevSameDir.getEndFractal().getIndex());
        boolean macdDivergence = lastMacdArea < prevMacdArea;

        // 3) 斜率
        double lastSlope = lastBi.getLength() > 0
                ? lastAmplitude.doubleValue() / lastBi.getLength() : 0;
        double prevSlope = prevSameDir.getLength() > 0
                ? prevAmplitude.doubleValue() / prevSameDir.getLength() : 0;
        boolean slopeDivergence = lastSlope < prevSlope;

        // 4) 成交量背驰
        double lastVolAvg = volumeAvg(volumes,
                lastBi.getStartFractal().getIndex(), lastBi.getEndFractal().getIndex());
        double prevVolAvg = volumeAvg(volumes,
                prevSameDir.getStartFractal().getIndex(), prevSameDir.getEndFractal().getIndex());
        boolean volumeDivergence = prevVolAvg > 0 && lastVolAvg < prevVolAvg;

        // 5) RSI背驰（TA4J 新增）
        //    向下笔: 价格创新低但RSI未创新低 → 底背驰
        //    向上笔: 价格创新高但RSI未创新高 → 顶背驰
        double lastRsiExtreme = rsiExtreme(rsiArray, lastBi, lastBi.getDirection());
        double prevRsiExtreme = rsiExtreme(rsiArray, prevSameDir, prevSameDir.getDirection());
        boolean rsiDivergence = false;
        if (lastBi.getDirection() == ChanBi.Direction.DOWN) {
            // 价格更低但 RSI 更高 → 底背驰
            boolean priceLower = lastBi.getEndPrice().compareTo(prevSameDir.getEndPrice()) < 0;
            rsiDivergence = priceLower && lastRsiExtreme > prevRsiExtreme;
        } else {
            // 价格更高但 RSI 更低 → 顶背驰
            boolean priceHigher = lastBi.getEndPrice().compareTo(prevSameDir.getEndPrice()) > 0;
            rsiDivergence = priceHigher && lastRsiExtreme < prevRsiExtreme;
        }

        // OBV 背离辅助（TA4J 新增）
        //   向下笔但 OBV 上升 → 资金未真正流出，支撑底背驰
        //   向上笔但 OBV 下降 → 资金未真正流入，支撑顶背驰
        double lastObvSlope = obvSlope(obvArray,
                lastBi.getStartFractal().getIndex(), lastBi.getEndFractal().getIndex());
        boolean obvDivergence = (lastBi.getDirection() == ChanBi.Direction.DOWN && lastObvSlope > 0)
                || (lastBi.getDirection() == ChanBi.Direction.UP && lastObvSlope < 0);

        // 核心三维（幅度+MACD+斜率）至少满足两个
        int coreCount = (amplitudeDivergence ? 1 : 0)
                + (macdDivergence ? 1 : 0)
                + (slopeDivergence ? 1 : 0);
        int totalCount = coreCount
                + (volumeDivergence ? 1 : 0)
                + (rsiDivergence ? 1 : 0);

        if (coreCount < 2) return;

        // 区分趋势背驰 vs 盘整背驰
        boolean isTrendDivergence = (trendType == TrendType.TREND_UP || trendType == TrendType.TREND_DOWN)
                && zhongshuList.size() >= 2;
        DivergenceType divType = isTrendDivergence
                ? DivergenceType.TREND_DIVERGENCE : DivergenceType.CONSOLIDATION_DIVERGENCE;

        ChanFractal endFractal = lastBi.getEndFractal();
        ChanFractal.Strength strength = endFractal.getStrength() != null
                ? endFractal.getStrength() : ChanFractal.Strength.WEAK;

        // 计算置信度
        double confidence = calculateFirstTypeConfidence(isTrendDivergence, strength,
                coreCount, volumeDivergence, rsiDivergence, obvDivergence);

        double volChangeRatio = prevVolAvg > 0 ? (lastVolAvg - prevVolAvg) / prevVolAvg * 100 : 0;

        String desc = String.format(
                "一%s[%s|%s分型]：幅度%.2f/%.2f MACD%.1f/%.1f 斜率%.4f/%.4f 量比%.1f%% RSI%.1f/%.1f (%d/5通过%s%s%s)",
                lastBi.getDirection() == ChanBi.Direction.DOWN ? "买" : "卖",
                isTrendDivergence ? "趋势背驰" : "盘整背驰",
                strength == ChanFractal.Strength.STRONG ? "强" : "弱",
                lastAmplitude, prevAmplitude, lastMacdArea, prevMacdArea,
                lastSlope, prevSlope, volChangeRatio,
                lastRsiExtreme, prevRsiExtreme, totalCount,
                volumeDivergence ? ",量价背离" : "",
                rsiDivergence ? ",RSI背驰" : "",
                obvDivergence ? ",OBV背离" : "");

        ChanSignalPoint point = new ChanSignalPoint();
        point.setPointType(lastBi.getDirection() == ChanBi.Direction.DOWN
                ? ChanSignalPoint.PointType.BUY_1 : ChanSignalPoint.PointType.SELL_1);
        point.setPrice(lastBi.getEndPrice());
        point.setTimestamp(lastBi.getEndTime());
        point.setConfidence(confidence);
        point.setDivergenceType(divType);
        point.setFractalStrength(strength);
        point.setDescription(desc);
        points.add(point);

        log.info("[缠论] {} | 置信度={} | 背驰维度: 幅度={} MACD={} 斜率={} 量={} RSI={} OBV={}",
                point.getPointType(), String.format("%.2f", confidence),
                amplitudeDivergence, macdDivergence, slopeDivergence,
                volumeDivergence, rsiDivergence, obvDivergence);
    }

    /**
     * 获取笔区间内的 RSI 极值（向下笔取最小值，向上笔取最大值）。
     */
    private double rsiExtreme(double[] rsiArray, ChanBi bi, ChanBi.Direction dir) {
        int from = Math.max(0, bi.getStartFractal().getIndex());
        int to = Math.min(rsiArray.length - 1, bi.getEndFractal().getIndex());
        double extreme = rsiArray[from];
        for (int i = from + 1; i <= to; i++) {
            if (dir == ChanBi.Direction.DOWN) {
                extreme = Math.min(extreme, rsiArray[i]);
            } else {
                extreme = Math.max(extreme, rsiArray[i]);
            }
        }
        return extreme;
    }

    /**
     * 计算第一类买卖点的置信度。
     */
    private double calculateFirstTypeConfidence(boolean isTrendDivergence,
                                                 ChanFractal.Strength strength,
                                                 int coreCount,
                                                 boolean volumeDivergence,
                                                 boolean rsiDivergence,
                                                 boolean obvDivergence) {
        double base;
        if (isTrendDivergence) {
            base = strength == ChanFractal.Strength.STRONG ? 0.90 : 0.80;
        } else {
            base = strength == ChanFractal.Strength.STRONG ? 0.70 : 0.60;
        }
        // 核心三维全部通过
        if (coreCount == 3) base += 0.02;
        // 成交量背驰
        if (volumeDivergence) base += 0.02;
        // RSI 背驰（独立且可靠的背驰信号）
        if (rsiDivergence) base += 0.03;
        // OBV 背离（资金流向与价格方向不一致）
        if (obvDivergence) base += 0.02;
        return Math.min(base, 0.95);
    }

    /**
     * 第二类买卖点：回调不破。
     *
     * <p>二买：前前笔下跌(一买区域) → 前笔上涨(反弹) → 最后笔下跌但低点高于前前笔低点</p>
     * <p>二卖：前前笔上涨(一卖区域) → 前笔下跌(回调) → 最后笔上涨但高点低于前前笔高点</p>
     */
    private void findSecondTypePoints(List<ChanBi> biList, List<ChanSignalPoint> points) {
        if (biList.size() < 3) return;

        ChanBi lastBi = biList.get(biList.size() - 1);
        ChanBi prevBi = biList.get(biList.size() - 2);
        ChanBi prevPrevBi = biList.get(biList.size() - 3);

        // 二买
        if (prevPrevBi.getDirection() == ChanBi.Direction.DOWN
                && prevBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getEndPrice().compareTo(prevPrevBi.getEndPrice()) > 0) {

            // 置信度根据低点抬高幅度调整
            BigDecimal liftRatio = lastBi.getEndPrice().subtract(prevPrevBi.getEndPrice())
                    .divide(prevPrevBi.getEndPrice().abs().max(BigDecimal.ONE), 4, RoundingMode.HALF_UP);
            double confidence = Math.min(0.70 + liftRatio.doubleValue() * 2, 0.85);

            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_2);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(confidence);
            bp.setFractalStrength(lastBi.getEndFractal().getStrength());
            bp.setDescription(String.format("二买：回调低点%.2f > 前低%.2f，低点抬高%.2f%%",
                    lastBi.getEndPrice(), prevPrevBi.getEndPrice(),
                    liftRatio.multiply(BigDecimal.valueOf(100))));
            points.add(bp);
        }

        // 二卖
        if (prevPrevBi.getDirection() == ChanBi.Direction.UP
                && prevBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getEndPrice().compareTo(prevPrevBi.getEndPrice()) < 0) {

            BigDecimal dropRatio = prevPrevBi.getEndPrice().subtract(lastBi.getEndPrice())
                    .divide(prevPrevBi.getEndPrice().abs().max(BigDecimal.ONE), 4, RoundingMode.HALF_UP);
            double confidence = Math.min(0.70 + dropRatio.doubleValue() * 2, 0.85);

            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_2);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(confidence);
            sp.setFractalStrength(lastBi.getEndFractal().getStrength());
            sp.setDescription(String.format("二卖：反弹高点%.2f < 前高%.2f，高点降低%.2f%%",
                    lastBi.getEndPrice(), prevPrevBi.getEndPrice(),
                    dropRatio.multiply(BigDecimal.valueOf(100))));
            points.add(sp);
        }
    }

    /**
     * 第三类买卖点：中枢突破回踩确认 + 成交量验证。
     *
     * <p>三买：向上突破中枢ZG后，回踩低点仍在ZG之上</p>
     * <p>三卖：向下跌破中枢ZD后，反弹高点仍在ZD之下</p>
     *
     * <p>成交量验证：</p>
     * <ul>
     *   <li>突破笔(prevBi)成交量放大(高于中枢内平均) → 突破有效，置信度+0.05</li>
     *   <li>回踩笔(lastBi)成交量萎缩 → 回踩只是洗盘，置信度+0.03</li>
     *   <li>突破笔缩量 → 突破可能是假突破，置信度-0.05</li>
     * </ul>
     */
    private void findThirdTypePoints(List<ChanBi> biList, List<ChanZhongshu> zhongshuList,
                                      double[] volumes, double[] obvArray,
                                      List<ChanSignalPoint> points) {
        if (zhongshuList.isEmpty() || biList.size() < 2) return;

        ChanZhongshu lastZh = zhongshuList.get(zhongshuList.size() - 1);
        ChanBi lastBi = biList.get(biList.size() - 1);
        ChanBi prevBi = biList.get(biList.size() - 2);

        // 计算中枢内平均成交量（作为基准）
        double zhVolAvg = 0;
        if (!lastZh.getBiList().isEmpty()) {
            ChanBi firstZhBi = lastZh.getBiList().get(0);
            ChanBi lastZhBi = lastZh.getBiList().get(lastZh.getBiList().size() - 1);
            zhVolAvg = volumeAvg(volumes,
                    firstZhBi.getStartFractal().getIndex(), lastZhBi.getEndFractal().getIndex());
        }

        // 突破笔和回踩笔的平均成交量
        double breakoutVolAvg = volumeAvg(volumes,
                prevBi.getStartFractal().getIndex(), prevBi.getEndFractal().getIndex());
        double pullbackVolAvg = volumeAvg(volumes,
                lastBi.getStartFractal().getIndex(), lastBi.getEndFractal().getIndex());

        // 三买
        if (prevBi.getDirection() == ChanBi.Direction.UP
                && prevBi.getEndPrice().compareTo(lastZh.getZg()) > 0
                && lastBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getEndPrice().compareTo(lastZh.getZg()) > 0) {

            BigDecimal margin = lastBi.getEndPrice().subtract(lastZh.getZg());
            BigDecimal zhRange = lastZh.getZg().subtract(lastZh.getZd());
            double marginRatio = zhRange.compareTo(BigDecimal.ZERO) > 0
                    ? margin.divide(zhRange, 4, RoundingMode.HALF_UP).doubleValue() : 0;
            double confidence = Math.min(0.75 + marginRatio * 0.15, 0.90);

            // 成交量确认
            boolean breakoutVolumeUp = zhVolAvg > 0 && breakoutVolAvg > zhVolAvg * 1.2;
            boolean pullbackVolumeShrink = breakoutVolAvg > 0 && pullbackVolAvg < breakoutVolAvg * 0.8;
            boolean breakoutVolumeWeak = zhVolAvg > 0 && breakoutVolAvg < zhVolAvg * 0.8;

            // OBV 确认（TA4J）：突破笔期间 OBV 上升 = 资金流入确认突破
            double breakoutObvSlope = obvSlope(obvArray,
                    prevBi.getStartFractal().getIndex(), prevBi.getEndFractal().getIndex());
            boolean obvConfirm = breakoutObvSlope > 0; // 三买需要 OBV 上升

            if (breakoutVolumeUp) confidence += 0.05;
            if (pullbackVolumeShrink) confidence += 0.03;
            if (breakoutVolumeWeak) confidence -= 0.05;
            if (obvConfirm) confidence += 0.02;
            confidence = Math.max(0.50, Math.min(confidence, 0.95));

            String volDesc = buildVolumeDesc(breakoutVolAvg, pullbackVolAvg, zhVolAvg,
                    breakoutVolumeUp, pullbackVolumeShrink, breakoutVolumeWeak);
            if (obvConfirm) volDesc += " OBV上升✓";

            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_3);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(confidence);
            bp.setFractalStrength(lastBi.getEndFractal().getStrength());
            bp.setDescription(String.format("三买：突破中枢[%.2f-%.2f]后回踩至%.2f，距上沿%.2f%% %s",
                    lastZh.getZd(), lastZh.getZg(), lastBi.getEndPrice(),
                    marginRatio * 100, volDesc));
            points.add(bp);
        }

        // 三卖
        if (prevBi.getDirection() == ChanBi.Direction.DOWN
                && prevBi.getEndPrice().compareTo(lastZh.getZd()) < 0
                && lastBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getEndPrice().compareTo(lastZh.getZd()) < 0) {

            BigDecimal margin = lastZh.getZd().subtract(lastBi.getEndPrice());
            BigDecimal zhRange = lastZh.getZg().subtract(lastZh.getZd());
            double marginRatio = zhRange.compareTo(BigDecimal.ZERO) > 0
                    ? margin.divide(zhRange, 4, RoundingMode.HALF_UP).doubleValue() : 0;
            double confidence = Math.min(0.75 + marginRatio * 0.15, 0.90);

            // 成交量确认（跌破时放量 = 有效跌破）
            boolean breakoutVolumeUp = zhVolAvg > 0 && breakoutVolAvg > zhVolAvg * 1.2;
            boolean pullbackVolumeShrink = breakoutVolAvg > 0 && pullbackVolAvg < breakoutVolAvg * 0.8;
            boolean breakoutVolumeWeak = zhVolAvg > 0 && breakoutVolAvg < zhVolAvg * 0.8;

            // OBV 确认（TA4J）：跌破笔期间 OBV 下降 = 资金流出确认跌破
            double breakoutObvSlope = obvSlope(obvArray,
                    prevBi.getStartFractal().getIndex(), prevBi.getEndFractal().getIndex());
            boolean obvConfirm = breakoutObvSlope < 0; // 三卖需要 OBV 下降

            if (breakoutVolumeUp) confidence += 0.05;
            if (pullbackVolumeShrink) confidence += 0.03;
            if (breakoutVolumeWeak) confidence -= 0.05;
            if (obvConfirm) confidence += 0.02;
            confidence = Math.max(0.50, Math.min(confidence, 0.95));

            String volDesc = buildVolumeDesc(breakoutVolAvg, pullbackVolAvg, zhVolAvg,
                    breakoutVolumeUp, pullbackVolumeShrink, breakoutVolumeWeak);
            if (obvConfirm) volDesc += " OBV下降✓";

            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_3);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(confidence);
            sp.setFractalStrength(lastBi.getEndFractal().getStrength());
            sp.setDescription(String.format("三卖：跌破中枢[%.2f-%.2f]后反弹至%.2f，距下沿%.2f%% %s",
                    lastZh.getZd(), lastZh.getZg(), lastBi.getEndPrice(),
                    marginRatio * 100, volDesc));
            points.add(sp);
        }
    }

    /**
     * 构建成交量描述信息。
     */
    private String buildVolumeDesc(double breakoutVol, double pullbackVol, double zhVol,
                                    boolean volUp, boolean volShrink, boolean volWeak) {
        StringBuilder sb = new StringBuilder("| 量:");
        if (zhVol > 0) {
            sb.append(String.format(" 突破均量=%.0f(中枢均量%.0f的%.0f%%)",
                    breakoutVol, zhVol, zhVol > 0 ? breakoutVol / zhVol * 100 : 0));
        }
        if (volUp) sb.append(" 突破放量✓");
        if (volShrink) sb.append(" 回踩缩量✓");
        if (volWeak) sb.append(" 突破缩量⚠");
        return sb.toString();
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private BigDecimal biHigh(ChanBi bi) {
        return bi.getStartPrice().max(bi.getEndPrice());
    }

    private BigDecimal biLow(ChanBi bi) {
        return bi.getStartPrice().min(bi.getEndPrice());
    }

    private BigDecimal minHigh(ChanBi... bis) {
        BigDecimal min = biHigh(bis[0]);
        for (int i = 1; i < bis.length; i++) min = min.min(biHigh(bis[i]));
        return min;
    }

    private BigDecimal maxLow(ChanBi... bis) {
        BigDecimal max = biLow(bis[0]);
        for (int i = 1; i < bis.length; i++) max = max.max(biLow(bis[i]));
        return max;
    }

    private BigDecimal maxHigh(ChanBi... bis) {
        BigDecimal max = biHigh(bis[0]);
        for (int i = 1; i < bis.length; i++) max = max.max(biHigh(bis[i]));
        return max;
    }

    private BigDecimal minLow(ChanBi... bis) {
        BigDecimal min = biLow(bis[0]);
        for (int i = 1; i < bis.length; i++) min = min.min(biLow(bis[i]));
        return min;
    }
}
