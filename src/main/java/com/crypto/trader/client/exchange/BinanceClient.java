package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import javax.annotation.PostConstruct;
import java.util.List;

@Service
public class BinanceClient implements ExchangeClient {

    /**
     * 注意：不要在构造器里直接使用 {@link #baseUrl} 来 build WebClient。
     *
     * <p>因为 {@code @Value} 字段注入发生在构造器之后；如果在构造器里读取，会得到 null，
     * 造成 WebClient 的 baseUrl 为空，最终请求拼接异常或打到错误地址。</p>
     */
    private WebClient webClient;

    /**
     * 统一的 WebClient builder（可在配置层集中设置超时、重试、拦截器、默认 header 等）。
     */
    private final WebClient.Builder webClientBuilder;

    @Value("${crypto.exchange.binance.base-url}")
    private String baseUrl;

    /**
     * 构造 Binance API 客户端。
     *
     * @param webClientBuilder Spring 注入的 WebClient builder
     */
    public BinanceClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 组件初始化回调。
     *
     * <p>可在此处增加 API Key 签名/鉴权拦截器、默认 header、超时等 WebClient 配置。</p>
     */
    @PostConstruct
    public void init() {
        // 在字段注入完成后再构造 WebClient，确保 baseUrl 可用。
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        // 可在此添加 API Key 拦截器 / 签名鉴权 / 默认 header 等
    }

    /**
     * 获取 K 线数据。
     *
     * <p>典型对应 Binance 接口 {@code /api/v3/klines}。当前实现为占位，返回空列表。</p>
     *
     * @param symbol    交易对（如 {@code BTCUSDT}）
     * @param interval  K 线周期（如 {@code 1m}）
     * @param startTime 起始时间（epoch millis）
     * @param endTime   结束时间（epoch millis）
     * @return K 线列表；当前返回空列表
     */
    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        // 调用 Binance API /api/v3/klines
        return List.of();
    }

    /**
     * 下单。
     *
     * <p>当前未实现；如需接入现货/合约下单，需要定义请求/响应 DTO 并完成签名鉴权。</p>
     *
     * @param request 下单请求
     * @return 下单结果
     * @throws UnsupportedOperationException 当前未实现
     */
    @Override
    public Object placeOrder(Object request) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * 查询账户余额/资产信息。
     *
     * @return 余额信息
     * @throws UnsupportedOperationException 当前未实现
     */
    @Override
    public Object getAccountBalance() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
