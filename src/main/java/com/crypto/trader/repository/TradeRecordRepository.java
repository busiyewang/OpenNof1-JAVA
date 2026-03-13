package com.crypto.trader.repository;

import com.crypto.trader.model.TradeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {

    /** 统计指定时间之后的成交记录数（用于日内交易次数风控）。 */
    long countByTimestampAfter(Instant timestamp);

    /** 统计指定交易对在指定时间之后的成交记录数。 */
    long countBySymbolAndTimestampAfter(String symbol, Instant timestamp);
}
