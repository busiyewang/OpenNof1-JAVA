package com.crypto.trader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * 提供一个可注入的 {@link WebClient.Builder}。
     *
     * <p>各类外部客户端（交易所/链上/MCP）可基于此 builder 统一配置拦截器、超时、默认 header 等。</p>
     *
     * @return WebClient builder
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
