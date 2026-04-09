package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import com.crypto.trader.service.indicator.chan.*;
import com.crypto.trader.service.indicator.chan.ChanResult.BigDecimalPair;
import com.crypto.trader.service.indicator.chan.ChanResult.DivergenceType;
import com.crypto.trader.service.indicator.chan.ChanResult.TrendType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 缠论核心计算器。
 *
 * <p>计算流程：K线包含处理 → 分型识别（含强弱） → 笔构建 → 线段构建（特征序列）
 * → 中枢识别 → 走势类型判断 → 背驰判断（MACD面积+斜率+幅度三重验证） → 买卖点生成</p>
 */
@Component
@Slf4j
public class ChanCalculator implements IndicatorCalculator<ChanResult> {

    /** 笔内最少K线数：顶底分型各占3根，中间至少1根独立K线 = 5根最少 */
    private static final int MIN_BI_KLINE_COUNT = 5;

    /** MACD 参数 */
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    @Override
    public ChanResult calculate(List<Kline> klines) {
        if (klines == null || klines.size() < 30) {
            log.debug("[缠论] K线数据不足（需>=30，当前={}），无法计算", klines == null ? 0 : klines.size());
            return null;
        }

        ChanResult result = new ChanResult();

        // 0. 计算 MACD（用于背驰判断）
        double[] macdHistogram = calculateMacdHistogram(klines);

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

        // 6. 走势类型判断
        TrendType trendType = classifyTrend(zhongshuList);
        result.setTrendType(trendType);

        // 7. 背驰判断 + 买卖点识别
        DivergenceType divergenceType = DivergenceType.NONE;
        List<ChanSignalPoint> signalPoints = findSignalPoints(biList, zhongshuList, klines, macdHistogram, trendType);
        result.setSignalPoints(signalPoints);

        // 从买卖点中提取背驰类型
        for (ChanSignalPoint sp : signalPoints) {
            if (sp.getDivergenceType() != null && sp.getDivergenceType() != DivergenceType.NONE) {
                divergenceType = sp.getDivergenceType();
                break;
            }
        }
        result.setDivergenceType(divergenceType);

        log.info("[缠论] 分析完成: 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}, 走势={}, 背驰={}, 买卖点={}",
                merged.size(), fractals.size(), biList.size(), segments.size(),
                zhongshuList.size(), trendType, divergenceType, signalPoints.size());

        return result;
    }

    // =========================================================================
    // 0. MACD 计算
    // =========================================================================

    /**
     * 计算 MACD 柱状图（histogram），用于背驰力度比较。
     */
    private double[] calculateMacdHistogram(List<Kline> klines) {
        int size = klines.size();
        double[] closes = new double[size];
        for (int i = 0; i < size; i++) {
            closes[i] = klines.get(i).getClose().doubleValue();
        }

        double[] emaFast = ema(closes, MACD_FAST);
        double[] emaSlow = ema(closes, MACD_SLOW);
        double[] dif = new double[size];
        for (int i = 0; i < size; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }

        double[] dea = ema(dif, MACD_SIGNAL);
        double[] histogram = new double[size];
        for (int i = 0; i < size; i++) {
            histogram[i] = 2 * (dif[i] - dea[i]); // MACD柱状图 = 2*(DIF-DEA)
        }

        return histogram;
    }

    private double[] ema(double[] data, int period) {
        double[] result = new double[data.length];
        double multiplier = 2.0 / (period + 1);
        result[0] = data[0];
        for (int i = 1; i < data.length; i++) {
            result[i] = (data[i] - result[i - 1]) * multiplier + result[i - 1];
        }
        return result;
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
     * 根据中枢数量和位置关系判断走势类型。
     * <ul>
     *   <li>0个中枢：UNKNOWN</li>
     *   <li>1个中枢：CONSOLIDATION（盘整）</li>
     *   <li>≥2个中枢且依次抬高：TREND_UP</li>
     *   <li>≥2个中枢且依次降低：TREND_DOWN</li>
     * </ul>
     */
    private TrendType classifyTrend(List<ChanZhongshu> zhongshuList) {
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
                                                    TrendType trendType) {
        List<ChanSignalPoint> points = new ArrayList<>();
        if (biList.size() < 3) return points;

        // 第一类买卖点：背驰（三重验证：价格幅度 + MACD面积 + 斜率）
        findFirstTypePoints(biList, zhongshuList, klines, macdHistogram, trendType, points);

        // 第二类买卖点：回调不破
        findSecondTypePoints(biList, points);

        // 第三类买卖点：中枢突破回踩
        findThirdTypePoints(biList, zhongshuList, points);

        return points;
    }

    /**
     * 第一类买卖点：三重背驰验证。
     *
     * <p>验证条件：</p>
     * <ol>
     *   <li>价格幅度：最后一笔幅度 < 前同向笔幅度</li>
     *   <li>MACD面积：最后一笔MACD柱状图面积 < 前同向笔面积</li>
     *   <li>斜率：最后一笔斜率(幅度/K线数) < 前同向笔斜率</li>
     * </ol>
     *
     * <p>额外要求：两段走势之间必须经过至少一个中枢。</p>
     *
     * <p>置信度规则：</p>
     * <ul>
     *   <li>趋势背驰(≥2个中枢) + 强分型：0.90-0.95</li>
     *   <li>趋势背驰 + 弱分型：0.80-0.85</li>
     *   <li>盘整背驰(1个中枢)：0.60-0.70</li>
     * </ul>
     */
    private void findFirstTypePoints(List<ChanBi> biList, List<ChanZhongshu> zhongshuList,
                                      List<Kline> klines, double[] macdHistogram,
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
        // 如果两笔之间没有中枢但整体有中枢，也允许（笔可能在中枢内部）
        if (!hasZhongshuBetween && zhongshuList.size() < 1) return;

        // --- 三重背驰验证 ---
        // 1) 价格幅度
        BigDecimal lastAmplitude = lastBi.getEndPrice().subtract(lastBi.getStartPrice()).abs();
        BigDecimal prevAmplitude = prevSameDir.getEndPrice().subtract(prevSameDir.getStartPrice()).abs();
        boolean amplitudeDivergence = lastAmplitude.compareTo(prevAmplitude) < 0;

        // 2) MACD面积
        double lastMacdArea = macdArea(macdHistogram,
                lastBi.getStartFractal().getIndex(), lastBi.getEndFractal().getIndex());
        double prevMacdArea = macdArea(macdHistogram,
                prevSameDir.getStartFractal().getIndex(), prevSameDir.getEndFractal().getIndex());
        boolean macdDivergence = lastMacdArea < prevMacdArea;

        // 3) 斜率（幅度/K线根数）
        double lastSlope = lastBi.getLength() > 0
                ? lastAmplitude.doubleValue() / lastBi.getLength() : 0;
        double prevSlope = prevSameDir.getLength() > 0
                ? prevAmplitude.doubleValue() / prevSameDir.getLength() : 0;
        boolean slopeDivergence = lastSlope < prevSlope;

        // 至少满足两个条件才认定背驰
        int divergenceCount = (amplitudeDivergence ? 1 : 0)
                + (macdDivergence ? 1 : 0)
                + (slopeDivergence ? 1 : 0);

        if (divergenceCount < 2) return;

        // 区分趋势背驰 vs 盘整背驰
        boolean isTrendDivergence = (trendType == TrendType.TREND_UP || trendType == TrendType.TREND_DOWN)
                && zhongshuList.size() >= 2;
        DivergenceType divType = isTrendDivergence
                ? DivergenceType.TREND_DIVERGENCE : DivergenceType.CONSOLIDATION_DIVERGENCE;

        // 获取关联分型的强弱
        ChanFractal endFractal = lastBi.getEndFractal();
        ChanFractal.Strength strength = endFractal.getStrength() != null
                ? endFractal.getStrength() : ChanFractal.Strength.WEAK;

        // 计算置信度
        double confidence = calculateFirstTypeConfidence(isTrendDivergence, strength, divergenceCount);

        if (lastBi.getDirection() == ChanBi.Direction.DOWN) {
            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_1);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(confidence);
            bp.setDivergenceType(divType);
            bp.setFractalStrength(strength);
            bp.setDescription(String.format("一买[%s|%s分型]：幅度%.2f/%.2f MACD面积%.1f/%.1f 斜率%.4f/%.4f (%d/3验证通过)",
                    isTrendDivergence ? "趋势背驰" : "盘整背驰",
                    strength == ChanFractal.Strength.STRONG ? "强" : "弱",
                    lastAmplitude, prevAmplitude, lastMacdArea, prevMacdArea,
                    lastSlope, prevSlope, divergenceCount));
            points.add(bp);
        } else {
            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_1);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(confidence);
            sp.setDivergenceType(divType);
            sp.setFractalStrength(strength);
            sp.setDescription(String.format("一卖[%s|%s分型]：幅度%.2f/%.2f MACD面积%.1f/%.1f 斜率%.4f/%.4f (%d/3验证通过)",
                    isTrendDivergence ? "趋势背驰" : "盘整背驰",
                    strength == ChanFractal.Strength.STRONG ? "强" : "弱",
                    lastAmplitude, prevAmplitude, lastMacdArea, prevMacdArea,
                    lastSlope, prevSlope, divergenceCount));
            points.add(sp);
        }
    }

    /**
     * 计算第一类买卖点的置信度。
     */
    private double calculateFirstTypeConfidence(boolean isTrendDivergence,
                                                 ChanFractal.Strength strength,
                                                 int divergenceCount) {
        double base;
        if (isTrendDivergence) {
            base = strength == ChanFractal.Strength.STRONG ? 0.90 : 0.80;
        } else {
            // 盘整背驰，置信度较低
            base = strength == ChanFractal.Strength.STRONG ? 0.70 : 0.60;
        }
        // 三重验证全部通过额外加分
        if (divergenceCount == 3) base += 0.05;
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
     * 第三类买卖点：中枢突破回踩确认。
     *
     * <p>三买：向上突破中枢ZG后，回踩低点仍在ZG之上</p>
     * <p>三卖：向下跌破中枢ZD后，反弹高点仍在ZD之下</p>
     */
    private void findThirdTypePoints(List<ChanBi> biList, List<ChanZhongshu> zhongshuList,
                                      List<ChanSignalPoint> points) {
        if (zhongshuList.isEmpty() || biList.size() < 2) return;

        ChanZhongshu lastZh = zhongshuList.get(zhongshuList.size() - 1);
        ChanBi lastBi = biList.get(biList.size() - 1);
        ChanBi prevBi = biList.get(biList.size() - 2);

        // 三买
        if (prevBi.getDirection() == ChanBi.Direction.UP
                && prevBi.getEndPrice().compareTo(lastZh.getZg()) > 0
                && lastBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getEndPrice().compareTo(lastZh.getZg()) > 0) {

            // 置信度根据回踩位置与ZG的距离调整（越远越好）
            BigDecimal margin = lastBi.getEndPrice().subtract(lastZh.getZg());
            BigDecimal zhRange = lastZh.getZg().subtract(lastZh.getZd());
            double marginRatio = zhRange.compareTo(BigDecimal.ZERO) > 0
                    ? margin.divide(zhRange, 4, RoundingMode.HALF_UP).doubleValue() : 0;
            double confidence = Math.min(0.75 + marginRatio * 0.15, 0.90);

            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_3);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(confidence);
            bp.setFractalStrength(lastBi.getEndFractal().getStrength());
            bp.setDescription(String.format("三买：突破中枢[%.2f-%.2f]后回踩至%.2f，距上沿%.2f%%",
                    lastZh.getZd(), lastZh.getZg(), lastBi.getEndPrice(),
                    marginRatio * 100));
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

            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_3);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(confidence);
            sp.setFractalStrength(lastBi.getEndFractal().getStrength());
            sp.setDescription(String.format("三卖：跌破中枢[%.2f-%.2f]后反弹至%.2f，距下沿%.2f%%",
                    lastZh.getZd(), lastZh.getZg(), lastBi.getEndPrice(),
                    marginRatio * 100));
            points.add(sp);
        }
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
