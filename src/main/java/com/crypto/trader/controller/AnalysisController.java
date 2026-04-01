package com.crypto.trader.controller;

import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.repository.AnalysisReportRepository;
import com.crypto.trader.service.analysis.AnalysisService;
import com.crypto.trader.service.notifier.AnalysisEmailSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private AnalysisReportRepository reportRepository;

    @Autowired
    private AnalysisEmailSender emailSender;

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
}
