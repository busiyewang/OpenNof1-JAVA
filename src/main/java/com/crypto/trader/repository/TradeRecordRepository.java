package com.crypto.trader.repository;

import com.crypto.trader.model.TradeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {

    /** 统计指定时间之后的成交记录数（用于日内交易次数风控）。 */
    long countByTimestampAfter(Instant timestamp);

    /** 统计指定交易对在指定时间之后的成交记录数。 */
    long countBySymbolAndTimestampAfter(String symbol, Instant timestamp);

    /** 获取指定交易对的所有成交记录（按时间升序），用于恢复仓位。 */
    List<TradeRecord> findBySymbolOrderByTimestampAsc(String symbol);

    /** 获取所有有交易记录的交易对列表。 */
    @Query("SELECT DISTINCT t.symbol FROM TradeRecord t")
    List<String> findDistinctSymbols();
}
