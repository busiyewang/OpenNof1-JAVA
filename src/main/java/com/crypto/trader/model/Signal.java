package com.crypto.trader.model;

import lombok.Builder;
import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "signals")
@Data
@Builder
public class Signal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private Instant timestamp;
    private Action action;   // BUY, SELL, HOLD
    private double price;
    private double confidence;
    private String strategyName;
    private String reason;

    public enum Action {
        BUY, SELL, HOLD
    }
}
