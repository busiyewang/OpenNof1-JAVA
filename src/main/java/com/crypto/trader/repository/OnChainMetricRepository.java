package com.crypto.trader.repository;

import com.crypto.trader.model.OnChainMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface OnChainMetricRepository extends JpaRepository<OnChainMetric, Long> {

    @Query("SELECT m FROM OnChainMetric m WHERE m.symbol = :symbol AND m.metricName = :metricName ORDER BY m.timestamp DESC")
    List<OnChainMetric> findLatest(@Param("symbol") String symbol,
                                   @Param("metricName") String metricName,
                                   org.springframework.data.domain.Pageable pageable);

    default List<OnChainMetric> findTop100BySymbol(String symbol, String metricName) {
        return findLatest(symbol, metricName, org.springframework.data.domain.PageRequest.of(0, 100));
    }

    boolean existsBySymbolAndMetricNameAndTimestamp(String symbol, String metricName, java.time.Instant timestamp);

    /**
     * 查询指定时间点之前最近的一条指标（用于 ML 训练时间对齐）。
     * 返回 timestamp <= asOf 的最新记录。
     */
    @Query("SELECT m FROM OnChainMetric m WHERE m.symbol = :symbol AND m.metricName = :metricName " +
           "AND m.timestamp <= :asOf ORDER BY m.timestamp DESC")
    List<OnChainMetric> findLatestBefore(@Param("symbol") String symbol,
                                         @Param("metricName") String metricName,
                                         @Param("asOf") java.time.Instant asOf,
                                         org.springframework.data.domain.Pageable pageable);

    default java.util.Optional<OnChainMetric> findLatestBefore(String symbol, String metricName, java.time.Instant asOf) {
        var list = findLatestBefore(symbol, metricName, asOf, org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }
}
