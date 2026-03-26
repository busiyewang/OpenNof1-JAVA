package com.crypto.trader.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 行情 K 线（蜡烛图）实体，对应交易所返回的单根 K 线。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>使用 {@link BigDecimal} 存储价格与成交量，避免精度丢失；统一设置为 20 位精度、8 位小数。</li>
 *   <li>{@link #timestamp} 采用 K 线的“开盘时间”（epoch millis），并与 {@code symbol} 组成唯一索引，
 *       用于防止重复插入同一根 K 线。</li>
 *   <li>{@link #interval} 记录周期（如 {@code 1m}, {@code 5m}, {@code 1h}），当前唯一索引未包含 interval，
 *       因此一个 symbol 同一时间点只能存一条记录（项目里目前只采 1m，暂不冲突）。</li>
 * </ul>
 */
@Entity
@Table(name = "klines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_symbol_interval_timestamp", columnNames = {"symbol", "kline_interval", "timestamp"})
}, indexes = {
        @Index(name = "idx_symbol_timestamp", columnList = "symbol, timestamp")
})
@Data
public class Kline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(length = 50)
    private String symbolName;

    /**
     * K 线周期：例如 {@code 1m}, {@code 5m}, {@code 1h} 等。
     * 使用 kline_interval 避免 MySQL 保留字冲突。
     */
    @Column(name = "kline_interval", nullable = false, length = 10)
    private String interval;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(precision = 20, scale = 8)
    private BigDecimal open;

    @Column(precision = 20, scale = 8)
    private BigDecimal high;

    @Column(precision = 20, scale = 8)
    private BigDecimal low;

    @Column(name = "close_price", precision = 20, scale = 8)
    private BigDecimal close;

    @Column(precision = 20, scale = 8)
    private BigDecimal volume;
}
