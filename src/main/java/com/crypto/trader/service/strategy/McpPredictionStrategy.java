package com.crypto.trader.service.strategy;

import com.crypto.trader.client.mcp.McpClient;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class McpPredictionStrategy implements TradingStrategy {

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${crypto.mcp.enabled:false}")
    private boolean mcpEnabled;

    /**
     * 调用大模型对短期趋势进行预测，并将预测结果映射为交易信号。
     *
     * <p>当未启用 MCP（{@code crypto.mcp.enabled=false}）或缺少 K 线数据时直接返回 HOLD。
     * 预测失败（网络/超时/解析异常等）会被捕获并记录日志，最终返回 HOLD，避免影响整体调度。</p>
     *
     * @param symbol      交易对
     * @param klines      K 线数据（用于构造提示词与取最后价格）
     * @param onChainData 链上数据（用于构造提示词摘要，可能为空）
     * @return 交易信号
     */
    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        if (!mcpEnabled || klines.isEmpty()) {
            return holdSignal(symbol);
        }

        try {
            // 构建提示词：将最近 K 线和链上数据摘要发送给大模型
            String prompt = buildPrompt(symbol, klines, onChainData);
            String prediction = mcpClient.predict(prompt);

            // 解析预测结果，期望模型返回类似 "BUY", "SELL", "HOLD" 的文本
            Signal.Action action = parsePrediction(prediction);
            double confidence = extractConfidence(prediction); // 简单从文本提取置信度

            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(action)
                    .price(klines.get(klines.size()-1).getClose().doubleValue())
                    .confidence(confidence)
                    .strategyName(getName())
                    .reason("MCP prediction: " + prediction.substring(0, Math.min(50, prediction.length())))
                    .build();
        } catch (Exception e) {
            log.error("MCP prediction failed for {}", symbol, e);
            return holdSignal(symbol);
        }
    }

    /**
     * 构造用于大模型预测的提示词（prompt）。
     *
     * <p>当前实现取最近 5 根 K 线的收盘价，并附加少量链上指标样本，要求模型按固定格式返回
     * ACTION/CONFIDENCE/REASON。</p>
     *
     * @param symbol      交易对
     * @param klines      K 线数据
     * @param onChainData 链上数据
     * @return prompt 文本
     */
    private String buildPrompt(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        // 取最近5条K线
        List<Kline> recent = klines.subList(Math.max(0, klines.size()-5), klines.size());
        StringBuilder sb = new StringBuilder();
        sb.append("You are a crypto trading assistant. Given the following market data for ")
          .append(symbol).append(", predict the short-term trend (BUY/SELL/HOLD) and confidence (0-1).\n\n");
        sb.append("Recent price data (timestamp, close):\n");
        recent.forEach(k -> sb.append(k.getTimestamp()).append(": ").append(k.getClose()).append("\n"));
        sb.append("\nOn-chain metrics (whale transaction count):\n");
        onChainData.stream().limit(5).forEach(m -> sb.append(m.getTimestamp()).append(": ").append(m.getValue()).append("\n"));
        sb.append("\nPlease respond in format: ACTION: BUY/SELL/HOLD, CONFIDENCE: 0.xx, REASON: ...");
        return sb.toString();
    }

    /**
     * 将模型返回文本解析为交易动作。
     *
     * <p>当前实现为宽松匹配：包含 BUY 视为 BUY，包含 SELL 视为 SELL，否则为 HOLD。</p>
     *
     * @param text 模型输出文本
     * @return 交易动作
     */
    private Signal.Action parsePrediction(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("BUY")) return Signal.Action.BUY;
        if (upper.contains("SELL")) return Signal.Action.SELL;
        return Signal.Action.HOLD;
    }

    /**
     * 从模型文本中提取置信度数值。
     *
     * <p>期望文本包含类似 {@code CONFIDENCE: 0.85} 的片段；解析失败时返回默认值 {@code 0.5}。</p>
     *
     * @param text 模型输出文本
     * @return 置信度（0~1 的双精度数）
     */
    private double extractConfidence(String text) {
        // 简单提取类似 "CONFIDENCE: 0.85" 的数值
        try {
            String lower = text.toLowerCase();
            int idx = lower.indexOf("confidence:");
            if (idx >= 0) {
                String part = lower.substring(idx + 11).trim().split("\\s+")[0];
                return Double.parseDouble(part);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0.5;
    }

    /**
     * 构造 HOLD 信号。
     *
     * @param symbol 交易对
     * @return HOLD 信号
     */
    private Signal holdSignal(String symbol) {
        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(Signal.Action.HOLD)
                .strategyName(getName())
                .build();
    }

    /**
     * @return 策略名称
     */
    @Override
    public String getName() {
        return "MCP";
    }
}
