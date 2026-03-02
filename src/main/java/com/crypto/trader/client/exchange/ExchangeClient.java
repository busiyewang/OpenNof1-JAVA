package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import java.util.List;

public interface ExchangeClient {
    /**
     * 获取指定交易对在时间范围内的 K 线数据。
     *
     * @param symbol    交易对（如 {@code BTCUSDT}）
     * @param interval  K 线周期（如 {@code 1m}, {@code 5m}）
     * @param startTime 起始时间（epoch millis，含）
     * @param endTime   结束时间（epoch millis，含/不含由具体实现决定，建议实现方在文档中固定约定）
     * @return K 线列表；无数据时返回空列表（不返回 null）
     */
    List<Kline> getKlines(String symbol, String interval, long startTime, long endTime);


    /**
     * 下单。
     *
     * <p>请求/返回类型按具体交易所实现决定；当前使用 {@link Object} 作为占位。</p>
     *
     * @param request 下单请求对象
     * @return 交易所返回的下单结果对象
     */
    Object placeOrder(Object request);

    /**
     * 查询账户余额/资产信息。
     *
     * @return 余额信息对象
     */
    Object getAccountBalance();
}
