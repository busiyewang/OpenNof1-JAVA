package com.crypto.trader.client.mcp;

import com.crypto.trader.client.mcp.dto.McpRequest;
import com.crypto.trader.client.mcp.dto.McpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import javax.annotation.PostConstruct;
import java.time.Duration;

@Service
public class McpClient {

    /**
     * WebClient 的 baseUrl 来自配置注入；初始化应放在 {@link PostConstruct} 阶段。
     */
    private WebClient webClient;

    private final WebClient.Builder webClientBuilder;

    @Value("${crypto.mcp.base-url}")
    private String baseUrl;

    @Value("${crypto.mcp.api-key}")
    private String apiKey;

    @Value("${crypto.mcp.model:gpt-4}")
    private String model;

    @Value("${crypto.mcp.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 构造 MCP/大模型调用客户端。
     *
     * @param webClientBuilder Spring 注入的 WebClient builder
     */
    public McpClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 调用大模型进行预测/生成。
     *
     * <p>该方法内部使用阻塞式调用（{@code block()}），并应用配置的超时 {@code crypto.mcp.timeout-seconds}。
     * 适用于定时任务/后台线程；若用于 Web 请求链路，建议改为响应式返回以避免阻塞。</p>
     *
     * @param prompt 构造的提示词
     * @return 模型返回的文本内容；当无有效响应时返回空字符串（不返回 null）
     */
    public String predict(String prompt) {
        McpRequest request = McpRequest.builder()
                .model(model)
                .messages(new McpRequest.Message[]{
                        McpRequest.Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()
                })
                .temperature(0.7)
                .build();

        McpResponse response = webClient.post()
                .uri("")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(Mono.just(request), McpRequest.class)
                .retrieve()
                .bodyToMono(McpResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        if (response != null && response.getChoices() != null && response.getChoices().length > 0) {
            return response.getChoices()[0].getMessage().getContent();
        }
        return "";
    }
}
