package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "klines", indexes = {
        @Index(columnList = "symbol, timestamp", unique = true)
})
@Data
public class Kline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String interval; // 1m, 5m, 1h etc.

    private Instant timestamp;

    @Column(precision = 20, scale = 8)
    private BigDecimal open;

    @Column(precision = 20, scale = 8)
    private BigDecimal high;

    @Column(precision = 20, scale = 8)
    private BigDecimal low;

    @Column(precision = 20, scale = 8)
    private BigDecimal close;

    @Column(precision = 20, scale = 8)
    private BigDecimal volume;
}
