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

    /**
     * 根据唯一键查找 K 线，用于 upsert 去重。
     */
    Optional<Kline> findBySymbolAndIntervalAndTimestamp(String symbol, String interval, Instant timestamp);

    /**
     * 判断指定 K 线是否已存在。
     */
    boolean existsBySymbolAndIntervalAndTimestamp(String symbol, String interval, Instant timestamp);
}
