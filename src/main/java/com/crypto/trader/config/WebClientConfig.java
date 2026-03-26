package com.crypto.trader.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 全局配置：超时、代理、响应体大小。
 *
 * <p>代理配置通过 {@code crypto.proxy.*} 控制，支持 HTTP 代理（如 Clash、V2Ray 等）。</p>
 */
@Configuration
@Slf4j
public class WebClientConfig {

    @Value("${crypto.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${crypto.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${crypto.proxy.port:7890}")
    private int proxyPort;

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        // 代理配置
        if (proxyEnabled) {
            httpClient = httpClient.proxy(proxy -> proxy
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(proxyHost)
                    .port(proxyPort));
            log.info("[WebClient] HTTP 代理已启用: {}:{}", proxyHost, proxyPort);
        } else {
            log.info("[WebClient] 未启用代理，直连");
        }

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }
}
