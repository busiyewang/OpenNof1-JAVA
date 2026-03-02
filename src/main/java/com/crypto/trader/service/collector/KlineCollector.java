package com.crypto.trader.service.collector;

import com.crypto.trader.client.exchange.ExchangeClient;
import com.crypto.trader.model.Kline;
import com.crypto.trader.repository.KlineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class KlineCollector {

    @Autowired
    private ExchangeClient exchangeClient;

    @Autowired
    private KlineRepository klineRepository;

    /**
     * 拉取指定交易对的 K 线数据并持久化到数据库。
     *
     * <p>当前实现固定拉取最近 1 小时的数据区间（{@code now-1h ~ now}）。若交易所返回空列表则不落库。</p>
     *
     * <p>注意事项：</p>
     * <ul>
     *   <li>调度周期为 60 秒，但每次拉取 1 小时窗口，会与前一次窗口大量重叠；真实接入时应做去重/幂等（如 upsert）。</li>
     *   <li>`Kline` 表当前的唯一索引是 {@code (symbol, timestamp)}，没有包含 interval；
     *       因此同一 symbol 同一 timestamp 的不同 interval 会冲突（当前只采 1m 时问题不明显）。</li>
     * </ul>
     *
     * @param symbol   交易对（如 {@code BTCUSDT}）
     * @param interval 周期（如 {@code 1m}）
     */
    public void collect(String symbol, String interval) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(60 * 60);
        List<Kline> klines = exchangeClient.getKlines(symbol, interval, start.toEpochMilli(), end.toEpochMilli());
        if (!klines.isEmpty()) {
            klineRepository.saveAll(klines);
            log.info("Saved {} klines for {}", klines.size(), symbol);
        }
    }
}
