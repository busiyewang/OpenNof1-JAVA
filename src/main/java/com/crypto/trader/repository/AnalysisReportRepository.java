package com.crypto.trader.repository;

import com.crypto.trader.model.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    /** 获取指定 symbol 和报告类型的最新一条报告 */
    Optional<AnalysisReport> findTopBySymbolAndReportTypeOrderByCreatedAtDesc(
            String symbol, AnalysisReport.ReportType reportType);

    /** 获取指定 symbol 的最新一条报告（不限类型） */
    Optional<AnalysisReport> findTopBySymbolOrderByCreatedAtDesc(String symbol);

    /** 查询指定时间范围内的报告 */
    List<AnalysisReport> findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
            String symbol, Instant start, Instant end);

    /** 查询指定类型的近期报告 */
    List<AnalysisReport> findByReportTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            AnalysisReport.ReportType reportType, Instant since);
}
