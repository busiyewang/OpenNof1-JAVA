package com.crypto.trader.client.exchange;

import com.crypto.trader.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
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
     * @return K 线列表；无数据时返回空列表（不返回 null）
     */
    @Override
    public List<Kline> getKlines(String symbol, String interval, long startTime, long endTime) {
        try {
            // OKX 现货/合约 K 线接口：/api/v5/market/candles
            // 文档约定：最新一根在前（时间倒序），这里按照返回顺序构造实体，
            // 上层（StrategyExecutor）会在使用前统一按 timestamp 升序排序。
            String instId = toOkxInstId(symbol);
            String bar = toOkxBar(interval);

            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v5/market/candles")
                            .queryParam("instId", instId)
                            .queryParam("bar", bar)
                            .queryParam("after", startTime)
                            .queryParam("before", endTime)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !"0".equals(String.valueOf(response.get("code")))) {
                log.warn("OKX getKlines failed for {}:{}", symbol, interval);
                return List.of();
            }

            Object dataObj = response.get("data");
            if (!(dataObj instanceof List)) {
                return List.of();
            }

            List<?> data = (List<?>) dataObj;
            List<Kline> result = new ArrayList<>(data.size());

            for (Object item : data) {
                if (!(item instanceof List)) continue;
                List<?> arr = (List<?>) item;
                if (arr.size() < 6) continue;

                // OKX candles 格式（按文档）：[ ts, o, h, l, c, vol, ... ]
                long ts = Long.parseLong(String.valueOf(arr.get(0)));
                BigDecimal open = new BigDecimal(String.valueOf(arr.get(1)));
                BigDecimal high = new BigDecimal(String.valueOf(arr.get(2)));
                BigDecimal low = new BigDecimal(String.valueOf(arr.get(3)));
                BigDecimal close = new BigDecimal(String.valueOf(arr.get(4)));
                BigDecimal volume = new BigDecimal(String.valueOf(arr.get(5)));

                Kline k = new Kline();
                k.setSymbol(symbol);
                k.setInterval(interval);
                k.setTimestamp(Instant.ofEpochMilli(ts));
                k.setOpen(open);
                k.setHigh(high);
                k.setLow(low);
                k.setClose(close);
                k.setVolume(volume);
                result.add(k);
            }

            return result;
        } catch (Exception e) {
            log.error("Error fetching klines from OKX for {}:{} ", symbol, interval, e);
            return List.of();
        }
    }

    /**
     * 将内部 symbol（如 BTCUSDT）转换为 OKX instId（如 BTC-USDT）。
     */
    private String toOkxInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) {
            return symbol;
        }
        // 简单规则：最后 4 位视为报价币种（USDT、USDC 等），中间加连字符
        String base = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote;
    }

    /**
     * 将内部周期（如 1m, 5m, 1h）转换为 OKX 的 bar 参数。
     */
    private String toOkxBar(String interval) {
        if (interval == null) return "1m";
        return switch (interval) {
            case "1m", "3m", "5m", "15m", "30m" -> interval;
            case "1h" -> "1H";
            case "2h" -> "2H";
            case "4h" -> "4H";
            case "6h" -> "6H";
            case "12h" -> "12H";
            case "1d" -> "1D";
            case "1w" -> "1W";
            case "1M" -> "1M";
            default -> "1m";
        };
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
