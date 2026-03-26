package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_records", indexes = {
        @Index(name = "idx_trade_symbol_timestamp", columnList = "symbol, timestamp"),
        @Index(name = "idx_trade_timestamp", columnList = "timestamp"),
        @Index(name = "idx_trade_order_id", columnList = "orderId")
})
@Data
public class TradeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(length = 64)
    private String orderId;

    @Column(nullable = false, length = 10)
    private String side;

    @Column(name = "order_type", nullable = false, length = 20)
    private String type;

    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    @Column(precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(precision = 20, scale = 8)
    private BigDecimal quoteQuantity;

    @Column(precision = 20, scale = 8)
    private BigDecimal fee;

    @Column(length = 10)
    private String feeAsset;

    @Column(nullable = false, length = 20)
    private String status;
}
