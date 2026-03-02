package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "onchain_metrics", indexes = {
        @Index(columnList = "symbol, metricName, timestamp", unique = true)
})
@Data
public class OnChainMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String metricName;
    private Instant timestamp;

    @Column(precision = 30, scale = 8)
    private BigDecimal value;
}
