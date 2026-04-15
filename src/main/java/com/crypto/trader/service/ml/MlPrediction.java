package com.crypto.trader.service.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * XGBoost 模型预测结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlPrediction {

    /** 预测类别: 0=看跌, 1=横盘, 2=看涨 */
    private int predictedClass;

    /** 各类别概率 [P(跌), P(横盘), P(涨)] */
    private float[] probabilities;

    /** 模型置信度（取预测类别的概率） */
    private double confidence;

    /** 人可读的方向描述 */
    private String direction;

    public static MlPrediction fromProbabilities(float[] probs) {
        int bestClass = 0;
        float bestProb = probs[0];
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > bestProb) {
                bestProb = probs[i];
                bestClass = i;
            }
        }

        String dir = switch (bestClass) {
            case 0 -> "BEARISH";
            case 2 -> "BULLISH";
            default -> "NEUTRAL";
        };

        return MlPrediction.builder()
                .predictedClass(bestClass)
                .probabilities(probs)
                .confidence(bestProb)
                .direction(dir)
                .build();
    }
}
