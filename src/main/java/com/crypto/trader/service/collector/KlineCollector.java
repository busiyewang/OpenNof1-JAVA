package com.crypto.trader.service.collector;

import com.crypto.trader.client.exchange.ExchangeClient;
import com.crypto.trader.client.exchange.OkxWebSocketClient;
import com.crypto.trader.model.Kline;
import com.crypto.trader.repository.KlineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * K 线数据采集器。
 *
 * <p>双模式运行：</p>
 * <ul>
 *   <li><b>WebSocket 模式（主）</b>：注册 OkxWebSocketClient 回调，实时接收 K 线推送并入库</li>
 *   <li><b>REST 模式（备）</b>：由 DataCollectionScheduler 定时调用 {@link #collect(String, String)}，
 *       用于补漏或 WebSocket 断线期间回补数据</li>
 * </ul>
 */
@Service
@Slf4j
public class KlineCollector {

    @Autowired
    private ExchangeClient exchangeClient;

    @Autowired
    private KlineRepository klineRepository;

    @Autowired
    private OkxWebSocketClient okxWebSocketClient;

    /**
     * 启动时注册 WebSocket K 线回调。
     */
    @PostConstruct
    public void initWebSocketCallback() {
        okxWebSocketClient.onKline(this::onKlineReceived);
        log.info("[KlineCollector] WebSocket kline callback registered");
    }

    /**
     * WebSocket K 线推送回调：去重后入库。
     */
    private void onKlineReceived(Kline kline) {
        try {
            boolean exists = klineRepository.existsBySymbolAndIntervalAndTimestamp(
                    kline.getSymbol(), kline.getInterval(), kline.getTimestamp());
            if (exists) {
                // 已存在则更新（WebSocket 会推送未完成 K 线的实时更新）
                klineRepository.findBySymbolAndIntervalAndTimestamp(
                        kline.getSymbol(), kline.getInterval(), kline.getTimestamp()
                ).ifPresent(existing -> {
                    existing.setOpen(kline.getOpen());
                    existing.setHigh(kline.getHigh());
                    existing.setLow(kline.getLow());
                    existing.setClose(kline.getClose());
                    existing.setVolume(kline.getVolume());
                    klineRepository.save(existing);
                });
            } else {
                klineRepository.save(kline);
                log.debug("[KlineCollector] WS kline saved: {} {} ts={}",
                        kline.getSymbol(), kline.getInterval(), kline.getTimestamp());
            }
        } catch (Exception e) {
            log.error("[KlineCollector] Failed to save WS kline for {}", kline.getSymbol(), e);
        }
    }

    /**
     * REST 回补采集（由 DataCollectionScheduler 定时调用）。
     *
     * <p>拉取最近 5 分钟的数据，去重入库。用于 WebSocket 断线后的数据补漏。</p>
     */
    public void collect(String symbol, String interval) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(5 * 60);
        List<Kline> klines = exchangeClient.getKlines(symbol, interval, start.toEpochMilli(), end.toEpochMilli());
        if (klines.isEmpty()) {
            return;
        }

        List<Kline> newKlines = klines.stream()
                .filter(k -> !klineRepository.existsBySymbolAndIntervalAndTimestamp(
                        k.getSymbol(), k.getInterval(), k.getTimestamp()))
                .collect(Collectors.toList());

        if (!newKlines.isEmpty()) {
            klineRepository.saveAll(newKlines);
            log.info("[KlineCollector] REST backfill: saved {} new klines for {} (fetched {}, skipped {} duplicates)",
                    newKlines.size(), symbol, klines.size(), klines.size() - newKlines.size());
        }
    }
}
