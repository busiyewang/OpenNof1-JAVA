package com.crypto.trader.service.ml;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Z-score 特征归一化器。
 *
 * <p>训练时调用 {@link #fit(double[][])} 计算每个特征的均值和标准差，
 * 预测时调用 {@link #transform(float[])} 用相同参数归一化。</p>
 *
 * <p>实现 Serializable 以便和模型一起持久化。</p>
 */
public class FeatureScaler implements Serializable {

    private static final long serialVersionUID = 1L;

    private double[] mean;
    private double[] std;
    private int featureCount;

    /**
     * 从训练数据中拟合均值和标准差。
     */
    public void fit(double[][] data) {
        if (data == null || data.length == 0) return;
        featureCount = data[0].length;
        mean = new double[featureCount];
        std = new double[featureCount];

        int n = data.length;

        // 计算均值
        for (double[] row : data) {
            for (int j = 0; j < featureCount; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < featureCount; j++) {
            mean[j] /= n;
        }

        // 计算标准差
        for (double[] row : data) {
            for (int j = 0; j < featureCount; j++) {
                double diff = row[j] - mean[j];
                std[j] += diff * diff;
            }
        }
        for (int j = 0; j < featureCount; j++) {
            std[j] = Math.sqrt(std[j] / n);
            // 防止除零：标准差为0说明该特征是常量，设为1避免NaN
            if (std[j] < 1e-10) std[j] = 1.0;
        }
    }

    /**
     * 原地归一化 double[][] 数据（训练集用）。
     */
    public void transformInPlace(double[][] data) {
        for (double[] row : data) {
            for (int j = 0; j < featureCount; j++) {
                row[j] = (row[j] - mean[j]) / std[j];
            }
        }
    }

    /**
     * 归一化单条 float[] 特征（预测用）。返回新的 float[]。
     */
    public float[] transform(float[] features) {
        float[] result = new float[features.length];
        for (int j = 0; j < Math.min(features.length, featureCount); j++) {
            result[j] = (float) ((features[j] - mean[j]) / std[j]);
        }
        return result;
    }

    public boolean isFitted() {
        return mean != null && std != null;
    }
}
