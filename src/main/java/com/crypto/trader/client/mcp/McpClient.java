package com.crypto.trader.client.mcp;

import com.crypto.trader.client.mcp.dto.McpRequest;
import com.crypto.trader.client.mcp.dto.McpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.time.Duration;

/**
 * DeepSeek 大模型调用客户端。
 *
 * <p>DeepSeek API 兼容 OpenAI Chat Completions 格式，使用相同的请求/响应结构。</p>
 */
@Service
@Slf4j
public class McpClient {

    private WebClient webClient;
    private final WebClient.Builder webClientBuilder;

    @Value("${crypto.mcp.base-url}")
    private String baseUrl;

    @Value("${crypto.mcp.api-key}")
    private String apiKey;

    @Value("${crypto.mcp.model:deepseek-chat}")
    private String model;

    @Value("${crypto.mcp.timeout-seconds:60}")
    private int timeoutSeconds;

    private static final String SYSTEM_PROMPT =
            "You are a professional cryptocurrency trading analyst. " +
            "Analyze the provided market data and on-chain metrics, then give a trading recommendation. " +
            "You MUST respond in exactly this format:\n" +
            "ACTION: BUY/SELL/HOLD\n" +
            "CONFIDENCE: 0.xx\n" +
            "REASON: your analysis reason\n" +
            "Do not include any other text outside this format.";

    public McpClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 调用 DeepSeek 大模型进行预测。
     *
     * <p>使用 system + user 双消息结构，system 消息约束输出格式，user 消息传递市场数据。</p>
     *
     * @param prompt 市场数据提示词
     * @return 模型返回的文本内容；无有效响应时返回空字符串
     */
    public String predict(String prompt) {
        McpRequest request = McpRequest.builder()
                .model(model)
                .messages(new McpRequest.Message[]{
                        McpRequest.Message.builder()
                                .role("system")
                                .content(SYSTEM_PROMPT)
                                .build(),
                        McpRequest.Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()
                })
                .temperature(0.3)
                .maxTokens(256)
                .build();

        try {
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
                String content = response.getChoices()[0].getMessage().getContent();
                log.debug("[McpClient] DeepSeek response: {}", content);
                return content;
            }

            log.warn("[McpClient] Empty response from DeepSeek");
            return "";
        } catch (Exception e) {
            log.error("[McpClient] DeepSeek API call failed: {}", e.getMessage());
            return "";
        }
    }
}
