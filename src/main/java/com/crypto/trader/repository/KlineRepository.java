package com.crypto.trader.repository;

import com.crypto.trader.model.Kline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KlineRepository extends JpaRepository<Kline, Long> {

    List<Kline> findTop100BySymbolOrderByTimestampDesc(String symbol);

    @Query("SELECT k FROM Kline k WHERE k.symbol = :symbol AND k.interval = :interval AND k.timestamp >= :startTime ORDER BY k.timestamp ASC")
    List<Kline> findKlines(@Param("symbol") String symbol,
                           @Param("interval") String interval,
                           @Param("startTime") Instant startTime);

    /** 按 symbol + interval 查询最新 N 条 K 线（用于多时间框架分析） */
    @Query("SELECT k FROM Kline k WHERE k.symbol = :symbol AND k.interval = :interval ORDER BY k.timestamp DESC")
    List<Kline> findLatestBySymbolAndInterval(@Param("symbol") String symbol,
                                              @Param("interval") String interval,
                                              org.springframework.data.domain.Pageable pageable);

    default List<Kline> findLatestKlines(String symbol, String interval, int count) {
        return findLatestBySymbolAndInterval(symbol, interval,
                org.springframework.data.domain.PageRequest.of(0, count));
    }

    /**
     * 根据唯一键查找 K 线，用于 upsert 去重。
     */
    Optional<Kline> findBySymbolAndIntervalAndTimestamp(String symbol, String interval, Instant timestamp);

    /**
     * 判断指定 K 线是否已存在。
     */
    boolean existsBySymbolAndIntervalAndTimestamp(String symbol, String interval, Instant timestamp);

    /** 按 symbol 和时间范围查询 K 线（用于预测回溯评分） */
    List<Kline> findBySymbolAndTimestampBetween(String symbol, Instant from, Instant to);

    /** 批量查询已存在的时间戳（用于回填去重，替代 N+1 的 exists 查询） */
    @Query("SELECT k.timestamp FROM Kline k WHERE k.symbol = :symbol AND k.interval = :interval AND k.timestamp IN :timestamps")
    List<Instant> findExistingTimestamps(@Param("symbol") String symbol,
                                         @Param("interval") String interval,
                                         @Param("timestamps") List<Instant> timestamps);
}
