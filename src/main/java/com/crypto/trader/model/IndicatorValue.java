package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "indicator_values", indexes = {
        @Index(columnList = "symbol, indicatorName, timestamp", unique = true)
})
@Data
public class IndicatorValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String indicatorName;
    private Instant timestamp;

    @Column(precision = 30, scale = 8)
    private BigDecimal value;
}
