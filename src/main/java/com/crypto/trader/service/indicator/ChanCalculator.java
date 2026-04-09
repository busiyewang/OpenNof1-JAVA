package com.crypto.trader.service.indicator;

import com.crypto.trader.model.Kline;
import com.crypto.trader.service.indicator.chan.*;
import com.crypto.trader.service.indicator.chan.ChanResult.BigDecimalPair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 缠论核心计算器。
 *
 * <p>计算流程：K线包含处理 → 分型识别 → 笔构建 → 线段构建 → 中枢识别 → 背驰判断 → 买卖点生成</p>
 */
@Component
@Slf4j
public class ChanCalculator implements IndicatorCalculator<ChanResult> {

    /** 笔内最少K线数（顶底分型之间至少1根独立K线，共5根） */
    private static final int MIN_BI_KLINE_COUNT = 4;

    @Override
    public ChanResult calculate(List<Kline> klines) {
        if (klines == null || klines.size() < 10) {
            log.debug("[缠论] K线数据不足（需>=10，当前={}），无法计算", klines == null ? 0 : klines.size());
            return null;
        }

        ChanResult result = new ChanResult();

        // 1. K线包含处理
        List<BigDecimalPair> merged = mergeKlines(klines);
        result.setMergedBars(merged);

        // 2. 分型识别
        List<ChanFractal> fractals = findFractals(merged, klines);
        result.setFractals(fractals);

        if (fractals.size() < 2) {
            log.debug("[缠论] 分型数量不足（{}），无法构建笔", fractals.size());
            result.setBiList(List.of());
            result.setSegments(List.of());
            result.setZhongshuList(List.of());
            result.setSignalPoints(List.of());
            return result;
        }

        // 3. 笔构建
        List<ChanBi> biList = buildBiList(fractals);
        result.setBiList(biList);

        // 4. 线段构建
        List<ChanSegment> segments = buildSegments(biList);
        result.setSegments(segments);

        // 5. 中枢识别
        List<ChanZhongshu> zhongshuList = findZhongshu(biList);
        result.setZhongshuList(zhongshuList);

        // 6. 买卖点识别
        List<ChanSignalPoint> signalPoints = findSignalPoints(biList, zhongshuList, klines);
        result.setSignalPoints(signalPoints);

        log.info("[缠论] 分析完成: 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}, 买卖点={}",
                merged.size(), fractals.size(), biList.size(), segments.size(),
                zhongshuList.size(), signalPoints.size());

        return result;
    }

    // =========================================================================
    // 1. K线包含处理
    // =========================================================================

    /**
     * K线包含处理：当两根K线存在包含关系时，按趋势方向合并。
     * <ul>
     *   <li>上升趋势：取高点的高值、低点的高值</li>
     *   <li>下降趋势：取高点的低值、低点的低值</li>
     * </ul>
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
                // 判断趋势方向
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
                // 替换最后一根
                merged.set(merged.size() - 1, new BigDecimalPair(newHigh, newLow,
                        klines.get(i).getTimestamp(), i));
            } else {
                merged.add(new BigDecimalPair(curHigh, curLow, klines.get(i).getTimestamp(), i));
            }
        }
        return merged;
    }

    // =========================================================================
    // 2. 分型识别
    // =========================================================================

    /**
     * 在合并后的K线序列中识别顶分型和底分型。
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
                            fractals.set(fractals.size() - 1, buildFractal(isTop, curr));
                        } else if (isBottom && curr.getLow().compareTo(lastFractal.getLow()) < 0) {
                            fractals.set(fractals.size() - 1, buildFractal(isTop, curr));
                        }
                        continue;
                    }
                }

                fractals.add(buildFractal(isTop, curr));
            }
        }

        return fractals;
    }

    private ChanFractal buildFractal(boolean isTop, BigDecimalPair bar) {
        ChanFractal f = new ChanFractal();
        f.setType(isTop ? ChanFractal.Type.TOP : ChanFractal.Type.BOTTOM);
        f.setIndex(bar.getOriginalIndex());
        f.setTimestamp(bar.getTimestamp());
        f.setHigh(bar.getHigh());
        f.setLow(bar.getLow());
        f.setExtremePrice(isTop ? bar.getHigh() : bar.getLow());
        return f;
    }

    // =========================================================================
    // 3. 笔构建
    // =========================================================================

    /**
     * 从分型序列构建笔。要求顶底分型交替且之间有足够距离。
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
                continue; // 不合法的笔
            }

            biList.add(bi);
        }

        return biList;
    }

    // =========================================================================
    // 4. 线段构建
    // =========================================================================

    /**
     * 从笔序列构建线段。一条线段由至少3笔组成，方向一致。
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

            // 延伸线段：检查后续笔是否继续创新高/新低
            for (int i = segStart + 2; i < biList.size(); i += 2) {
                if (dir == ChanSegment.Direction.UP) {
                    // 上升线段：后续向上笔应创新高
                    if (biList.get(i).getEndPrice().compareTo(biList.get(segEnd).getEndPrice()) > 0) {
                        segEnd = i;
                    } else {
                        break;
                    }
                } else {
                    // 下降线段：后续向下笔应创新低
                    if (biList.get(i).getEndPrice().compareTo(biList.get(segEnd).getEndPrice()) < 0) {
                        segEnd = i;
                    } else {
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
     * 中枢至少由3个连续的有重叠区间的笔构成。
     */
    private List<ChanZhongshu> findZhongshu(List<ChanBi> biList) {
        List<ChanZhongshu> zhongshuList = new ArrayList<>();
        if (biList.size() < 3) return zhongshuList;

        int i = 0;
        while (i < biList.size() - 2) {
            // 取连续3笔，计算重叠区间
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
                BigDecimal biHigh = biList.get(j).getStartPrice().max(biList.get(j).getEndPrice());
                BigDecimal biLow = biList.get(j).getStartPrice().min(biList.get(j).getEndPrice());

                // 笔与中枢有重叠
                if (biHigh.compareTo(zd) > 0 && biLow.compareTo(zg) < 0) {
                    zhBiList.add(biList.get(j));
                    gg = gg.max(biHigh);
                    dd = dd.min(biLow);
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

    private BigDecimal biHigh(ChanBi bi) {
        return bi.getStartPrice().max(bi.getEndPrice());
    }

    private BigDecimal biLow(ChanBi bi) {
        return bi.getStartPrice().min(bi.getEndPrice());
    }

    private BigDecimal minHigh(ChanBi... bis) {
        BigDecimal min = biHigh(bis[0]);
        for (int i = 1; i < bis.length; i++) {
            min = min.min(biHigh(bis[i]));
        }
        return min;
    }

    private BigDecimal maxLow(ChanBi... bis) {
        BigDecimal max = biLow(bis[0]);
        for (int i = 1; i < bis.length; i++) {
            max = max.max(biLow(bis[i]));
        }
        return max;
    }

    private BigDecimal maxHigh(ChanBi... bis) {
        BigDecimal max = biHigh(bis[0]);
        for (int i = 1; i < bis.length; i++) {
            max = max.max(biHigh(bis[i]));
        }
        return max;
    }

    private BigDecimal minLow(ChanBi... bis) {
        BigDecimal min = biLow(bis[0]);
        for (int i = 1; i < bis.length; i++) {
            min = min.min(biLow(bis[i]));
        }
        return min;
    }

    // =========================================================================
    // 6. 买卖点识别
    // =========================================================================

    /**
     * 基于笔、中枢和背驰关系识别三类买卖点。
     */
    private List<ChanSignalPoint> findSignalPoints(List<ChanBi> biList,
                                                    List<ChanZhongshu> zhongshuList,
                                                    List<Kline> klines) {
        List<ChanSignalPoint> points = new ArrayList<>();

        if (biList.size() < 3) return points;

        // ---- 第一类买卖点：趋势背驰 ----
        findFirstTypePoints(biList, klines, points);

        // ---- 第二类买卖点：回调不破 ----
        findSecondTypePoints(biList, points);

        // ---- 第三类买卖点：中枢突破回踩 ----
        findThirdTypePoints(biList, zhongshuList, points);

        return points;
    }

    /**
     * 第一类买卖点：通过比较最后两段同向笔的力度（用MACD面积近似）判断背驰。
     */
    private void findFirstTypePoints(List<ChanBi> biList, List<Kline> klines,
                                      List<ChanSignalPoint> points) {
        if (biList.size() < 5) return;

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

        // 用笔的价格幅度作为力度近似（简化版背驰判断）
        BigDecimal lastStrength = lastBi.getEndPrice().subtract(lastBi.getStartPrice()).abs();
        BigDecimal prevStrength = prevSameDir.getEndPrice().subtract(prevSameDir.getStartPrice()).abs();

        // 背驰：最后一笔力度小于前一笔
        boolean isDivergence = lastStrength.compareTo(prevStrength) < 0;

        if (!isDivergence) return;

        if (lastBi.getDirection() == ChanBi.Direction.DOWN) {
            // 下跌背驰 → 一买
            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_1);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(0.85);
            bp.setDescription(String.format("一买：下跌趋势背驰，当前笔幅度%.2f < 前笔幅度%.2f",
                    lastStrength, prevStrength));
            points.add(bp);
        } else {
            // 上涨背驰 → 一卖
            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_1);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(0.85);
            sp.setDescription(String.format("一卖：上涨趋势背驰，当前笔幅度%.2f < 前笔幅度%.2f",
                    lastStrength, prevStrength));
            points.add(sp);
        }
    }

    /**
     * 第二类买卖点：一买/一卖后的回调/反弹不创新低/新高。
     */
    private void findSecondTypePoints(List<ChanBi> biList, List<ChanSignalPoint> points) {
        if (biList.size() < 3) return;

        ChanBi lastBi = biList.get(biList.size() - 1);
        ChanBi prevBi = biList.get(biList.size() - 2);
        ChanBi prevPrevBi = biList.get(biList.size() - 3);

        // 二买：前前笔下跌 → 前笔上涨（反弹）→ 最后笔下跌但低点高于前前笔低点
        if (prevPrevBi.getDirection() == ChanBi.Direction.DOWN
                && prevBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getEndPrice().compareTo(prevPrevBi.getEndPrice()) > 0) {

            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_2);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(0.75);
            bp.setDescription("二买：回调不创新低，低点抬高");
            points.add(bp);
        }

        // 二卖：前前笔上涨 → 前笔下跌（回调）→ 最后笔上涨但高点低于前前笔高点
        if (prevPrevBi.getDirection() == ChanBi.Direction.UP
                && prevBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getEndPrice().compareTo(prevPrevBi.getEndPrice()) < 0) {

            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_2);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(0.75);
            sp.setDescription("二卖：反弹不创新高，高点降低");
            points.add(sp);
        }
    }

    /**
     * 第三类买卖点：离开中枢后回踩/反弹不回到中枢内。
     */
    private void findThirdTypePoints(List<ChanBi> biList, List<ChanZhongshu> zhongshuList,
                                      List<ChanSignalPoint> points) {
        if (zhongshuList.isEmpty() || biList.size() < 2) return;

        ChanZhongshu lastZh = zhongshuList.get(zhongshuList.size() - 1);
        ChanBi lastBi = biList.get(biList.size() - 1);
        ChanBi prevBi = biList.get(biList.size() - 2);

        // 三买：前笔向上突破中枢上沿 → 最后笔回调但低点在中枢上沿之上
        if (prevBi.getDirection() == ChanBi.Direction.UP
                && prevBi.getEndPrice().compareTo(lastZh.getZg()) > 0
                && lastBi.getDirection() == ChanBi.Direction.DOWN
                && lastBi.getEndPrice().compareTo(lastZh.getZg()) > 0) {

            ChanSignalPoint bp = new ChanSignalPoint();
            bp.setPointType(ChanSignalPoint.PointType.BUY_3);
            bp.setPrice(lastBi.getEndPrice());
            bp.setTimestamp(lastBi.getEndTime());
            bp.setConfidence(0.80);
            bp.setDescription(String.format("三买：突破中枢[%.2f-%.2f]后回踩不入中枢",
                    lastZh.getZd(), lastZh.getZg()));
            points.add(bp);
        }

        // 三卖：前笔向下跌破中枢下沿 → 最后笔反弹但高点在中枢下沿之下
        if (prevBi.getDirection() == ChanBi.Direction.DOWN
                && prevBi.getEndPrice().compareTo(lastZh.getZd()) < 0
                && lastBi.getDirection() == ChanBi.Direction.UP
                && lastBi.getEndPrice().compareTo(lastZh.getZd()) < 0) {

            ChanSignalPoint sp = new ChanSignalPoint();
            sp.setPointType(ChanSignalPoint.PointType.SELL_3);
            sp.setPrice(lastBi.getEndPrice());
            sp.setTimestamp(lastBi.getEndTime());
            sp.setConfidence(0.80);
            sp.setDescription(String.format("三卖：跌破中枢[%.2f-%.2f]后反弹不入中枢",
                    lastZh.getZd(), lastZh.getZg()));
            points.add(sp);
        }
    }
}
