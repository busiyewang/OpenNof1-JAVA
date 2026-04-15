package com.crypto.trader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预测回溯评分实体。
 *
 * <p>每次 AI 分析后，系统会在 24h/72h 后回溯比较预测与实际走势，
 * 生成评分记录，用于后续 Prompt 进化。</p>
 */
@Entity
@Table(name = "prediction_scores", indexes = {
        @Index(name = "idx_ps_symbol_scored", columnList = "symbol, scored_at"),
        @Index(name = "idx_ps_report_id", columnList = "report_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的分析报告 ID */
    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(nullable = false, length = 20)
    private String symbol;

    /** 评分时间 */
    @Column(name = "scored_at", nullable = false)
    private Instant scoredAt;

    /** 评分窗口（小时数：24 或 72） */
    @Column(name = "score_window_hours", nullable = false)
    private int scoreWindowHours;

    // ---- 预测值 ----

    /** 预测趋势方向 */
    @Column(name = "predicted_trend", length = 30)
    private String predictedTrend;

    /** 预测置信度 */
    @Column(name = "predicted_confidence")
    private double predictedConfidence;

    /** 预测支撑位 */
    @Column(name = "predicted_support", precision = 20, scale = 8)
    private BigDecimal predictedSupport;

    /** 预测阻力位 */
    @Column(name = "predicted_resistance", precision = 20, scale = 8)
    private BigDecimal predictedResistance;

    /** 分析时的价格 */
    @Column(name = "price_at_prediction", precision = 20, scale = 8)
    private BigDecimal priceAtPrediction;

    // ---- 实际值 ----

    /** 评分时的实际价格 */
    @Column(name = "price_at_scoring", precision = 20, scale = 8)
    private BigDecimal priceAtScoring;

    /** 窗口内最高价 */
    @Column(name = "actual_high", precision = 20, scale = 8)
    private BigDecimal actualHigh;

    /** 窗口内最低价 */
    @Column(name = "actual_low", precision = 20, scale = 8)
    private BigDecimal actualLow;

    /** 实际涨跌幅 (%) */
    @Column(name = "actual_change_percent")
    private double actualChangePercent;

    // ---- 评分结果 ----

    /** 趋势方向是否正确 */
    @Column(name = "trend_correct")
    private boolean trendCorrect;

    /** 支撑位是否有效（最低价未跌破支撑位，或跌破幅度 < 1%） */
    @Column(name = "support_valid")
    private boolean supportValid;

    /** 阻力位是否有效（最高价未突破阻力位，或突破幅度 < 1%） */
    @Column(name = "resistance_valid")
    private boolean resistanceValid;

    /** 综合得分 0-100 */
    @Column(name = "total_score")
    private int totalScore;

    /** 错误分析摘要（用于反馈给 Prompt） */
    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;
}
