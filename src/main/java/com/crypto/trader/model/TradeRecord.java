package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_records")
@Data
public class TradeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private Instant timestamp;
    private String orderId;
    private String side;
    private String type;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal quoteQuantity;
    private BigDecimal fee;
    private String feeAsset;
    private String status;
}
