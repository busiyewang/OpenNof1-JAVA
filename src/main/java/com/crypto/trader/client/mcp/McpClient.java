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

    /** 旧版交易信号 prompt（保留兼容） */
    private static final String SYSTEM_PROMPT =
            "You are a professional cryptocurrency trading analyst. " +
            "Analyze the provided market data and on-chain metrics, then give a trading recommendation. " +
            "You MUST respond in exactly this format:\n" +
            "ACTION: BUY/SELL/HOLD\n" +
            "CONFIDENCE: 0.xx\n" +
            "REASON: your analysis reason\n" +
            "Do not include any other text outside this format.";

    /** 分析系统 prompt：要求返回结构化 JSON */
    private static final String ANALYSIS_SYSTEM_PROMPT =
            "You are a professional cryptocurrency market analyst with deep expertise in technical analysis, " +
            "on-chain data interpretation, and market cycle theory. " +
            "Analyze the provided multi-timeframe market data, technical indicators, and on-chain metrics.\n\n" +
            "You MUST respond with a JSON object containing the following fields:\n" +
            "{\n" +
            "  \"trendDirection\": \"STRONGLY_BULLISH|BULLISH|NEUTRAL|BEARISH|STRONGLY_BEARISH\",\n" +
            "  \"confidence\": 0.00-1.00,\n" +
            "  \"shortTermOutlook\": \"1-3 day price outlook with reasoning (in Chinese)\",\n" +
            "  \"mediumTermOutlook\": \"1-2 week price outlook with reasoning (in Chinese)\",\n" +
            "  \"supportLevel\": numeric_price,\n" +
            "  \"resistanceLevel\": numeric_price,\n" +
            "  \"riskLevel\": \"LOW|MODERATE|HIGH|EXTREME\",\n" +
            "  \"riskFactors\": [\"factor1\", \"factor2\"],\n" +
            "  \"keyIndicatorAnalysis\": {\n" +
            "    \"MACD\": \"interpretation in Chinese\",\n" +
            "    \"BollingerBands\": \"interpretation in Chinese\"\n" +
            "  },\n" +
            "  \"onChainInsights\": {\n" +
            "    \"NUPL\": \"interpretation in Chinese\",\n" +
            "    \"SOPR\": \"interpretation in Chinese\",\n" +
            "    \"exchangeFlow\": \"interpretation in Chinese\",\n" +
            "    \"whaleActivity\": \"interpretation in Chinese\"\n" +
            "  },\n" +
            "  \"reasoning\": \"Detailed multi-paragraph analysis in Chinese...\"\n" +
            "}\n\n" +
            "Respond ONLY with valid JSON. No markdown, no code blocks, no extra text.";

    public McpClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 调用 DeepSeek 进行结构化市场分析（返回 JSON）。
     *
     * @param prompt 包含多时间框架数据和链上指标的提示词
     * @return DeepSeek 返回的 JSON 字符串；失败时返回空字符串
     */
    public String analyze(String prompt) {
        return callDeepSeek(ANALYSIS_SYSTEM_PROMPT, prompt, 0.2, 2048);
    }

    /**
     * 调用 DeepSeek 大模型进行交易预测（旧版，保留兼容）。
     */
    public String predict(String prompt) {
        return callDeepSeek(SYSTEM_PROMPT, prompt, 0.3, 256);
    }

    private String callDeepSeek(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        McpRequest request = McpRequest.builder()
                .model(model)
                .messages(new McpRequest.Message[]{
                        McpRequest.Message.builder()
                                .role("system")
                                .content(systemPrompt)
                                .build(),
                        McpRequest.Message.builder()
                                .role("user")
                                .content(userPrompt)
                                .build()
                })
                .temperature(temperature)
                .maxTokens(maxTokens)
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
                log.debug("[McpClient] DeepSeek response length: {} chars", content.length());
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
