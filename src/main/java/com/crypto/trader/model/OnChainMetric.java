package com.crypto.trader.model;

import lombok.Data;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "onchain_metrics", uniqueConstraints = {
        @UniqueConstraint(name = "uk_onchain_symbol_metric_ts", columnNames = {"symbol", "metric_name", "timestamp"})
}, indexes = {
        @Index(name = "idx_onchain_symbol_ts", columnList = "symbol, timestamp")
})
@Data
public class OnChainMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "metric_name", nullable = false, length = 50)
    private String metricName;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "metric_value", precision = 30, scale = 8)
    private BigDecimal value;
}
