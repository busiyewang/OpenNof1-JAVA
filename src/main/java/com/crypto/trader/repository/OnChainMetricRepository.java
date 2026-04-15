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
}
