package com.crypto.trader.scheduler;

import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.service.analysis.AnalysisService;
import com.crypto.trader.service.notifier.AnalysisEmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分析报告定时调度器。
 * <p>
 * 每日早 8 点 + 每周一早 9 点自动执行分析并发送 HTML 邮件报告。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnalysisReportScheduler {

    private final AnalysisService analysisService;

    private final AnalysisEmailSender emailSender;

    @Value("${crypto.watch-list}")
    private List<String> watchList;

    /** 每日分析（默认早 8 点，Asia/Shanghai） */
    @Scheduled(cron = "${crypto.analysis.daily-cron:0 0 8 * * ?}", zone = "${crypto.analysis.timezone:Asia/Shanghai}")
    public void dailyAnalysis() {
        log.info("[分析调度] ========== 每日分析开始 ==========");
        for (String symbol : watchList) {
            runAnalysis(symbol, AnalysisReport.ReportType.DAILY);
        }
        log.info("[分析调度] ========== 每日分析结束 ==========");
    }

    /** 每周分析（默认周一早 9 点，Asia/Shanghai） */
    @Scheduled(cron = "${crypto.analysis.weekly-cron:0 0 9 ? * MON}", zone = "${crypto.analysis.timezone:Asia/Shanghai}")
    public void weeklyAnalysis() {
        log.info("[分析调度] ========== 每周分析开始 ==========");
        for (String symbol : watchList) {
            runAnalysis(symbol, AnalysisReport.ReportType.WEEKLY);
        }
        log.info("[分析调度] ========== 每周分析结束 ==========");
    }

    private void runAnalysis(String symbol, AnalysisReport.ReportType reportType) {
        try {
            AnalysisReport report = analysisService.analyzeSymbol(symbol, reportType);
            if (report != null) {
                emailSender.sendAnalysisReport(report);
            }
        } catch (Exception e) {
            log.error("[分析调度] {} {} 分析失败: {}", symbol, reportType, e.getMessage(), e);
        }
    }
}
