package com.crypto.trader.repository;

import com.crypto.trader.model.TradeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {
}
