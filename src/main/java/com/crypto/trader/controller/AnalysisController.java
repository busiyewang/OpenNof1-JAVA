package com.crypto.trader.controller;

import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.model.PredictionScore;
import com.crypto.trader.repository.AnalysisReportRepository;
import com.crypto.trader.repository.PredictionScoreRepository;
import com.crypto.trader.service.analysis.AnalysisService;
import com.crypto.trader.service.analysis.PredictionScorerService;
import com.crypto.trader.service.notifier.AnalysisEmailSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private AnalysisReportRepository reportRepository;

    @Autowired
    private AnalysisEmailSender emailSender;

    @Autowired
    private PredictionScoreRepository scoreRepository;

    @Autowired
    private PredictionScorerService scorerService;

    /** 按需触发分析（同时发送邮件） */
    @PostMapping("/{symbol}")
    public ResponseEntity<AnalysisReport> triggerAnalysis(@PathVariable String symbol) {
        AnalysisReport report = analysisService.analyzeSymbol(symbol.toUpperCase(), AnalysisReport.ReportType.ON_DEMAND);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        emailSender.sendAnalysisReport(report);
        return ResponseEntity.ok(report);
    }

    /** 获取最新一条分析报告 */
    @GetMapping("/{symbol}/latest")
    public ResponseEntity<AnalysisReport> getLatest(@PathVariable String symbol) {
        return reportRepository.findTopBySymbolOrderByCreatedAtDesc(symbol.toUpperCase())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 查询历史报告（默认最近 7 天） */
    @GetMapping("/{symbol}/history")
    public List<AnalysisReport> getHistory(
            @PathVariable String symbol,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        if (to == null) to = Instant.now();
        if (from == null) from = to.minus(7, ChronoUnit.DAYS);
        return reportRepository.findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
                symbol.toUpperCase(), from, to);
    }

    /** 查看预测表现统计 */
    @GetMapping("/{symbol}/performance")
    public ResponseEntity<Map<String, Object>> getPerformance(@PathVariable String symbol) {
        String sym = symbol.toUpperCase();
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);

        long total = scoreRepository.countTotalSince(sym, since);
        if (total == 0) {
            return ResponseEntity.ok(Map.of("message", "暂无评分数据", "total", 0));
        }

        long correct = scoreRepository.countCorrectTrendSince(sym, since);
        Double avgScore = scoreRepository.findAverageScoreSince(sym, since);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", sym);
        result.put("period", "近30天");
        result.put("totalPredictions", total);
        result.put("correctTrend", correct);
        result.put("trendAccuracy", String.format("%.1f%%", (double) correct / total * 100));
        result.put("averageScore", String.format("%.1f", avgScore != null ? avgScore : 0));

        // 最近 10 条评分
        List<PredictionScore> recent = scoreRepository.findBySymbolOrderByScoredAtDesc(
                sym, PageRequest.of(0, 10));
        result.put("recentScores", recent);

        return ResponseEntity.ok(result);
    }

    /** 手动触发预测评分 */
    @PostMapping("/{symbol}/score")
    public ResponseEntity<String> triggerScoring(@PathVariable String symbol) {
        String sym = symbol.toUpperCase();
        scorerService.scoreUnscored(sym, 24);
        scorerService.scoreUnscored(sym, 72);
        return ResponseEntity.ok("评分完成: " + sym);
    }
}
