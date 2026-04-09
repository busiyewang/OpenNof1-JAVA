package com.crypto.trader.repository;

import com.crypto.trader.model.PredictionScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PredictionScoreRepository extends JpaRepository<PredictionScore, Long> {

    /** 查询指定 symbol 最近 N 条评分记录 */
    List<PredictionScore> findBySymbolOrderByScoredAtDesc(String symbol,
                                                          org.springframework.data.domain.Pageable pageable);

    /** 查询指定报告是否已评分 */
    boolean existsByReportIdAndScoreWindowHours(Long reportId, int scoreWindowHours);

    /** 统计指定 symbol 最近一段时间的平均得分 */
    @Query("SELECT AVG(ps.totalScore) FROM PredictionScore ps " +
           "WHERE ps.symbol = :symbol AND ps.scoredAt > :since")
    Double findAverageScoreSince(@Param("symbol") String symbol, @Param("since") Instant since);

    /** 统计趋势预测准确率 */
    @Query("SELECT COUNT(ps) FROM PredictionScore ps " +
           "WHERE ps.symbol = :symbol AND ps.trendCorrect = true AND ps.scoredAt > :since")
    long countCorrectTrendSince(@Param("symbol") String symbol, @Param("since") Instant since);

    /** 统计总预测数 */
    @Query("SELECT COUNT(ps) FROM PredictionScore ps " +
           "WHERE ps.symbol = :symbol AND ps.scoredAt > :since")
    long countTotalSince(@Param("symbol") String symbol, @Param("since") Instant since);

    /** 获取最近的错误预测（得分 < 50），用于 Prompt 反馈 */
    @Query("SELECT ps FROM PredictionScore ps " +
           "WHERE ps.symbol = :symbol AND ps.totalScore < 50 " +
           "ORDER BY ps.scoredAt DESC")
    List<PredictionScore> findRecentErrors(@Param("symbol") String symbol,
                                            org.springframework.data.domain.Pageable pageable);
}
