package com.crypto.trader.repository;

import com.crypto.trader.model.Signal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SignalRepository extends JpaRepository<Signal, Long> {

    /** 查询指定时间范围内的所有非 HOLD 信号，按时间降序。 */
    List<Signal> findByTimestampBetweenAndActionNotOrderByTimestampDesc(
            Instant start, Instant end, Signal.Action excludeAction);

    /** 查询指定时间范围内的所有信号（含 HOLD），按时间降序。 */
    List<Signal> findByTimestampBetweenOrderByTimestampDesc(Instant start, Instant end);

    /** 统计指定时间范围内非 HOLD 信号数量。 */
    long countByTimestampBetweenAndActionNot(Instant start, Instant end, Signal.Action excludeAction);
}
