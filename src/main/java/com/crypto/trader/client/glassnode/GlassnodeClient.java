package com.crypto.trader.client.glassnode;

import com.crypto.trader.model.OnChainMetric;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;

@Service
public class GlassnodeClient {

    /**
     * 与交易所客户端类似：baseUrl/apiKey 来自 {@code @Value} 字段注入，
     * 不能在构造器阶段就依赖这些字段完成 WebClient 初始化。
     */
    private WebClient webClient;

    private final WebClient.Builder webClientBuilder;

    @Value("${crypto.glassnode.base-url}")
    private String baseUrl;

    @Value("${crypto.glassnode.api-key}")
    private String apiKey;

    /**
     * 构造 Glassnode API 客户端。
     *
     * @param webClientBuilder Spring 注入的 WebClient builder
     */
    public GlassnodeClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 获取指定资产在时间范围内的巨鲸交易数量指标。
     *
     * <p>当前实现为占位，返回空列表。实际接入时需要拼接 endpoint、注入 {@code api_key}，
     * 并将返回值映射为 {@link OnChainMetric}。</p>
     *
     * @param asset 资产/标的（Glassnode 通常使用 {@code BTC}、{@code ETH} 等；若传交易对需先做映射）
     * @param from  起始时间（含）
     * @param to    结束时间（含）
     * @return 指标列表；无数据时返回空列表（不返回 null）
     */
    public List<OnChainMetric> getWhaleTransactionCount(String asset, Instant from, Instant to) {
        // 调用 Glassnode API
        return List.of();
    }
}
