package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "indicator_values", uniqueConstraints = {
        @UniqueConstraint(name = "uk_indicator_symbol_name_ts", columnNames = {"symbol", "indicator_name", "timestamp"})
}, indexes = {
        @Index(name = "idx_indicator_symbol_ts", columnList = "symbol, timestamp")
})
@Data
public class IndicatorValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "indicator_name", nullable = false, length = 50)
    private String indicatorName;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "indicator_value", precision = 30, scale = 8)
    private BigDecimal value;
}
