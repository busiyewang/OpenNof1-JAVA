package com.crypto.trader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "analysis_reports", indexes = {
        @Index(name = "idx_report_symbol_created", columnList = "symbol, created_at"),
        @Index(name = "idx_report_type_created", columnList = "report_type, created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 20)
    private ReportType reportType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trend_direction", nullable = false, length = 30)
    private TrendDirection trendDirection;

    @Column(name = "trend_confidence")
    private double trendConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_assessment", length = 20)
    private RiskLevel riskAssessment;

    @Column(name = "price_current", precision = 20, scale = 8)
    private BigDecimal priceCurrent;

    @Column(name = "price_support", precision = 20, scale = 8)
    private BigDecimal priceSupport;

    @Column(name = "price_resistance", precision = 20, scale = 8)
    private BigDecimal priceResistance;

    @Column(name = "short_term_outlook", columnDefinition = "TEXT")
    private String shortTermOutlook;

    @Column(name = "medium_term_outlook", columnDefinition = "TEXT")
    private String mediumTermOutlook;

    /** 每个时间框架的分析摘要 (JSON) */
    @Column(name = "timeframes_summary", columnDefinition = "MEDIUMTEXT")
    private String timeframesSummary;

    /** 关键技术指标解读 (JSON) */
    @Column(name = "key_indicators", columnDefinition = "MEDIUMTEXT")
    private String keyIndicators;

    /** 链上数据分析摘要 (JSON) */
    @Column(name = "onchain_summary", columnDefinition = "MEDIUMTEXT")
    private String onChainSummary;

    /** 风险因子列表 (JSON array) */
    @Column(name = "risk_factors", columnDefinition = "TEXT")
    private String riskFactors;

    /** DeepSeek 完整分析文本 */
    @Column(name = "deepseek_analysis", columnDefinition = "MEDIUMTEXT")
    private String deepseekAnalysis;

    public enum ReportType {
        DAILY, WEEKLY, ON_DEMAND
    }

    public enum TrendDirection {
        STRONGLY_BULLISH, BULLISH, NEUTRAL, BEARISH, STRONGLY_BEARISH
    }

    public enum RiskLevel {
        LOW, MODERATE, HIGH, EXTREME
    }
}
