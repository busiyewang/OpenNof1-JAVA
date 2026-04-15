package com.crypto.trader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "signals", indexes = {
        @Index(name = "idx_signal_symbol_timestamp", columnList = "symbol, timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Signal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Action action;

    @Column(name = "price")
    private double price;

    @Column(name = "confidence")
    private double confidence;

    @Column(length = 100)
    private String strategyName;

    @Column(length = 500)
    private String reason;

    public enum Action {
        BUY, SELL, HOLD
    }
}
