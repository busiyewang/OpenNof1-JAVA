package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.ml.MlModelService;
import com.crypto.trader.service.ml.MlPrediction;
import com.crypto.trader.service.ml.FeatureEngineerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 基于 Smile GradientTreeBoost 专用模型的交易策略。
 *
 * <p>与 McpPredictionStrategy（LLM预测）不同，此策略使用历史数据训练的
 * GradientTreeBoost 分类模型进行预测，具有以下优势：</p>
 * <ul>
 *   <li>基于28维量化特征，不依赖自然语言</li>
 *   <li>可回测、可量化准确率</li>
 *   <li>预测延迟 < 10ms，可高频调用</li>
 *   <li>概率输出，天然给出置信度</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MlPredictionStrategy implements TradingStrategy {

    private final MlModelService mlModelService;
    private final FeatureEngineerService featureEngineer;

    /** 最小置信度，低于此值输出 HOLD */
    private static final double MIN_CONFIDENCE = 0.45;

    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        if (!mlModelService.isModelReady(symbol, "1h")) {
            log.debug("[ML策略] {} 模型未就绪，返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        if (klines == null || klines.size() < FeatureEngineerService.MIN_KLINES) {
            log.debug("[ML策略] {} K线不足 {}，返回 HOLD", symbol, FeatureEngineerService.MIN_KLINES);
            return holdSignal(symbol);
        }

        // 构建链上数据 map
        Map<String, BigDecimal> onChainMap = featureEngineer.buildOnChainMap(onChainData);

        // 调用模型预测
        MlPrediction prediction = mlModelService.predict(symbol, "1h", klines, onChainMap);
        if (prediction == null) {
            return holdSignal(symbol);
        }

        double price = klines.get(klines.size() - 1).getClose().doubleValue();
        float[] probs = prediction.getProbabilities();

        String reason = String.format("ML-GBT: %s (%d-bar ahead, P[跌]=%.1f%%, P[横盘]=%.1f%%, P[涨]=%.1f%%)",
                prediction.getDirection(), prediction.getPredictionHorizon(),
                probs[0] * 100, probs[1] * 100, probs[2] * 100);

        // 置信度不足 → HOLD
        if (prediction.getConfidence() < MIN_CONFIDENCE) {
            log.info("[ML策略] {} 置信度 {}% < {}%，返回 HOLD",
                    symbol, String.format("%.1f", prediction.getConfidence() * 100),
                    String.format("%.0f", MIN_CONFIDENCE * 100));
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.HOLD)
                    .price(price)
                    .confidence(prediction.getConfidence())
                    .strategyName(getName())
                    .reason(reason)
                    .build();
        }

        // 根据预测方向输出信号
        Signal.Action action = switch (prediction.getPredictedClass()) {
            case 0 -> Signal.Action.SELL;
            case 2 -> Signal.Action.BUY;
            default -> Signal.Action.HOLD;
        };

        log.info("[ML策略] {} → {} | 置信度={}%", symbol, action,
                String.format("%.1f", prediction.getConfidence() * 100));

        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(action)
                .price(price)
                .confidence(prediction.getConfidence())
                .strategyName(getName())
                .reason(reason)
                .build();
    }

    @Override
    public String getName() {
        return "GBT-ML";
    }

    private Signal holdSignal(String symbol) {
        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(Signal.Action.HOLD)
                .strategyName(getName())
                .build();
    }
}
