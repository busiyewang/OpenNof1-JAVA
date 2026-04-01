package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import java.util.List;

public interface ExchangeClient {
    /**
     * 获取指定交易对在时间范围内的 K 线数据。
     *
     * @param symbol    交易对（如 {@code BTCUSDT}）
     * @param interval  K 线周期（如 {@code 1m}, {@code 1h}）
     * @param startTime 起始时间（epoch millis）
     * @param endTime   结束时间（epoch millis）
     * @return K 线列表；无数据时返回空列表
     */
    List<Kline> getKlines(String symbol, String interval, long startTime, long endTime);
}
