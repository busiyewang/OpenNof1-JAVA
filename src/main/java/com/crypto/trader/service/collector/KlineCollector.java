package com.crypto.trader.service.collector;

import com.crypto.trader.client.exchange.ExchangeClient;
import com.crypto.trader.model.Kline;
import com.crypto.trader.repository.KlineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KlineCollector {

    @Autowired
    private ExchangeClient exchangeClient;

    @Autowired
    private KlineRepository klineRepository;

    /**
     * 拉取指定交易对的 K 线数据并持久化到数据库（去重）。
     *
     * <p>拉取最近 5 分钟的数据窗口（1m 级别约 5 根 K 线），通过唯一键判断跳过已存在的记录，
     * 只保存新增数据，避免重复插入。</p>
     *
     * @param symbol   交易对（如 {@code BTCUSDT}）
     * @param interval 周期（如 {@code 1m}）
     */
    public void collect(String symbol, String interval) {
        Instant end = Instant.now();
        // 缩小拉取窗口为 5 分钟，减少不必要的重复数据
        Instant start = end.minusSeconds(5 * 60);
        List<Kline> klines = exchangeClient.getKlines(symbol, interval, start.toEpochMilli(), end.toEpochMilli());
        if (klines.isEmpty()) {
            return;
        }

        // 过滤已存在的 K 线（基于唯一键去重）
        List<Kline> newKlines = klines.stream()
                .filter(k -> !klineRepository.existsBySymbolAndIntervalAndTimestamp(
                        k.getSymbol(), k.getInterval(), k.getTimestamp()))
                .collect(Collectors.toList());

        if (!newKlines.isEmpty()) {
            klineRepository.saveAll(newKlines);
            log.info("Saved {} new klines for {} (fetched {}, skipped {} duplicates)",
                    newKlines.size(), symbol, klines.size(), klines.size() - newKlines.size());
        }
    }
}
