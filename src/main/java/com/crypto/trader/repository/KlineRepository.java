package com.crypto.trader.repository;

import com.crypto.trader.model.Kline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface KlineRepository extends JpaRepository<Kline, Long> {

    List<Kline> findTop100BySymbolOrderByTimestampDesc(String symbol);

    @Query("SELECT k FROM Kline k WHERE k.symbol = :symbol AND k.interval = :interval AND k.timestamp >= :startTime ORDER BY k.timestamp ASC")
    List<Kline> findKlines(@Param("symbol") String symbol,
                           @Param("interval") String interval,
                           @Param("startTime") Instant startTime);
}
