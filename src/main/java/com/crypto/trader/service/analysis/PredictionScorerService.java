package com.crypto.trader.service.analysis;

import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.PredictionScore;
import com.crypto.trader.repository.AnalysisReportRepository;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.PredictionScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 预测回溯评分服务。
 *
 * <p>对已过期的分析报告进行回溯评分：</p>
 * <ol>
 *   <li>获取预测时间点之后 N 小时内的实际 K 线数据</li>
 *   <li>比较预测方向与实际走势</li>
 *   <li>检查支撑位/阻力位的有效性</li>
 *   <li>生成综合评分（0-100）</li>
 *   <li>提取错误模式摘要（用于 Prompt 反馈）</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PredictionScorerService {

    private final AnalysisReportRepository reportRepository;

    private final KlineRepository klineRepository;

    private final PredictionScoreRepository scoreRepository;

    /** 容差百分比：支撑/阻力位判定的容差 */
    private static final double TOLERANCE_PERCENT = 1.0;

    /**
     * 对指定 symbol 的所有未评分报告进行回溯评分。
     *
     * @param windowHours 评分窗口（24 或 72 小时）
     */
    public void scoreUnscored(String symbol, int windowHours) {
        Instant cutoff = Instant.now().minus(windowHours, ChronoUnit.HOURS);

        // 查找 windowHours 前的报告（确保有足够的后续数据）
        List<AnalysisReport> reports = reportRepository
                .findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
                        symbol,
                        cutoff.minus(7, ChronoUnit.DAYS),
                        cutoff);

        int scored = 0;
        for (AnalysisReport report : reports) {
            if (scoreRepository.existsByReportIdAndScoreWindowHours(report.getId(), windowHours)) {
                continue;
            }

            PredictionScore score = scoreReport(report, windowHours);
            if (score != null) {
                scoreRepository.save(score);
                scored++;
                log.info("[预测评分] {} 报告#{} {}h评分: {} 分 | 趋势{} | {}",
                        symbol, report.getId(), windowHours, score.getTotalScore(),
                        score.isTrendCorrect() ? "正确" : "错误",
                        score.getErrorSummary() != null ? score.getErrorSummary() : "无误");
            }
        }

        if (scored > 0) {
            log.info("[预测评分] {} 完成 {} 条报告的 {}h 回溯评分", symbol, scored, windowHours);
        }
    }

    /**
     * 对单个报告进行回溯评分。
     */
    private PredictionScore scoreReport(AnalysisReport report, int windowHours) {
        Instant from = report.getCreatedAt();
        Instant to = from.plus(windowHours, ChronoUnit.HOURS);

        // 获取窗口内的 K 线数据
        List<Kline> klines = klineRepository.findBySymbolAndTimestampBetween(
                report.getSymbol(), from, to);

        if (klines == null || klines.size() < 5) {
            log.debug("[预测评分] {} 报告#{} 窗口内K线不足，跳过", report.getSymbol(), report.getId());
            return null;
        }

        klines.sort(Comparator.comparing(Kline::getTimestamp));

        // 计算实际走势
        BigDecimal startPrice = report.getPriceCurrent();
        BigDecimal endPrice = klines.get(klines.size() - 1).getClose();
        BigDecimal high = klines.stream().map(Kline::getHigh).max(BigDecimal::compareTo).orElse(startPrice);
        BigDecimal low = klines.stream().map(Kline::getLow).min(BigDecimal::compareTo).orElse(startPrice);

        double changePercent = startPrice.compareTo(BigDecimal.ZERO) > 0
                ? endPrice.subtract(startPrice)
                    .divide(startPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0;

        // ---- 评分 ----
        int totalScore = 0;
        StringBuilder errorSb = new StringBuilder();

        // 1. 趋势方向评分（满分 50 分）
        boolean trendCorrect = isTrendCorrect(report.getTrendDirection(), changePercent);
        if (trendCorrect) {
            totalScore += 50;
        } else {
            errorSb.append(String.format("趋势判断错误：预测%s，实际%s%.2f%%；",
                    trendDirectionChinese(report.getTrendDirection()),
                    changePercent >= 0 ? "上涨" : "下跌", Math.abs(changePercent)));
        }

        // 2. 支撑位评分（满分 25 分）
        boolean supportValid = true;
        if (report.getPriceSupport() != null && report.getPriceSupport().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal tolerance = report.getPriceSupport().multiply(BigDecimal.valueOf(TOLERANCE_PERCENT / 100));
            supportValid = low.compareTo(report.getPriceSupport().subtract(tolerance)) >= 0;
            if (supportValid) {
                totalScore += 25;
            } else {
                errorSb.append(String.format("支撑位失效：预测%.2f，实际最低%.2f；",
                        report.getPriceSupport(), low));
            }
        } else {
            totalScore += 10; // 未给出支撑位，给基础分
        }

        // 3. 阻力位评分（满分 25 分）
        boolean resistanceValid = true;
        if (report.getPriceResistance() != null && report.getPriceResistance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal tolerance = report.getPriceResistance().multiply(BigDecimal.valueOf(TOLERANCE_PERCENT / 100));
            resistanceValid = high.compareTo(report.getPriceResistance().add(tolerance)) <= 0;
            if (resistanceValid) {
                totalScore += 25;
            } else {
                errorSb.append(String.format("阻力位失效：预测%.2f，实际最高%.2f；",
                        report.getPriceResistance(), high));
            }
        } else {
            totalScore += 10;
        }

        // 4. 置信度校准加分/扣分
        // 高置信度但错误 → 额外扣分；低置信度但正确 → 加分
        if (!trendCorrect && report.getTrendConfidence() > 0.8) {
            totalScore = Math.max(0, totalScore - 10);
            errorSb.append(String.format("高置信度(%.0f%%)但方向错误，需降低此场景的置信度；",
                    report.getTrendConfidence() * 100));
        }
        if (trendCorrect && report.getTrendConfidence() < 0.5) {
            totalScore = Math.min(100, totalScore + 5);
        }

        return PredictionScore.builder()
                .reportId(report.getId())
                .symbol(report.getSymbol())
                .scoredAt(Instant.now())
                .scoreWindowHours(windowHours)
                .predictedTrend(report.getTrendDirection() != null ? report.getTrendDirection().name() : null)
                .predictedConfidence(report.getTrendConfidence())
                .predictedSupport(report.getPriceSupport())
                .predictedResistance(report.getPriceResistance())
                .priceAtPrediction(startPrice)
                .priceAtScoring(endPrice)
                .actualHigh(high)
                .actualLow(low)
                .actualChangePercent(changePercent)
                .trendCorrect(trendCorrect)
                .supportValid(supportValid)
                .resistanceValid(resistanceValid)
                .totalScore(totalScore)
                .errorSummary(errorSb.length() > 0 ? errorSb.toString() : null)
                .build();
    }

    /**
     * 判断趋势预测是否正确。
     * BULLISH/STRONGLY_BULLISH → 实际涨幅 > 0
     * BEARISH/STRONGLY_BEARISH → 实际跌幅 < 0
     * NEUTRAL → 实际波动 < 2%
     */
    private boolean isTrendCorrect(AnalysisReport.TrendDirection predicted, double actualChangePercent) {
        if (predicted == null) return false;
        return switch (predicted) {
            case STRONGLY_BULLISH, BULLISH -> actualChangePercent > 0;
            case STRONGLY_BEARISH, BEARISH -> actualChangePercent < 0;
            case NEUTRAL -> Math.abs(actualChangePercent) < 2.0;
        };
    }

    private String trendDirectionChinese(AnalysisReport.TrendDirection dir) {
        if (dir == null) return "未知";
        return switch (dir) {
            case STRONGLY_BULLISH -> "强烈看多";
            case BULLISH -> "看多";
            case NEUTRAL -> "中性";
            case BEARISH -> "看空";
            case STRONGLY_BEARISH -> "强烈看空";
        };
    }

    // =========================================================================
    // 历史表现统计（供 PromptBuilder 使用）
    // =========================================================================

    /**
     * 获取指定 symbol 最近 30 天的预测表现摘要，用于注入 Prompt。
     */
    public String getPerformanceSummary(String symbol) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);

        long total = scoreRepository.countTotalSince(symbol, since);
        if (total == 0) return null;

        long correct = scoreRepository.countCorrectTrendSince(symbol, since);
        Double avgScore = scoreRepository.findAverageScoreSince(symbol, since);
        double accuracy = total > 0 ? (double) correct / total * 100 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("过去30天预测表现：共%d次分析，趋势准确率%.0f%%，平均得分%.0f/100。\n",
                total, accuracy, avgScore != null ? avgScore : 0));

        // 提取最近的错误模式
        List<PredictionScore> recentErrors = scoreRepository.findRecentErrors(
                symbol, org.springframework.data.domain.PageRequest.of(0, 3));

        if (!recentErrors.isEmpty()) {
            sb.append("近期典型错误：\n");
            for (PredictionScore error : recentErrors) {
                if (error.getErrorSummary() != null) {
                    sb.append("- ").append(error.getErrorSummary()).append("\n");
                }
            }
            sb.append("请在本次分析中特别注意避免以上错误模式。\n");
        }

        if (accuracy < 50) {
            sb.append("注意：近期准确率偏低，请更谨慎地给出方向判断，适当降低置信度。\n");
        }

        return sb.toString();
    }
}
